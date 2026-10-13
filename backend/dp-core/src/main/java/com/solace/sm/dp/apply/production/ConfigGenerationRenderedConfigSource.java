package com.solace.sm.dp.apply.production;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.config.OverlayKeys;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretResolver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The durable worker's {@link RenderedConfigSource} (plan 010 T6.2): generation {@code G}'s bytes, rebuilt from the
 * config of record alone, so a resumed apply in a fresh process re-renders exactly what the first process journaled.
 *
 * <p><b>Two collaborators, and only two.</b> It reads the generation's {@code rendered_entries} through
 * {@link ConfigGenerationRows} (tenant, instance and generation all bound) and resolves each {@code secretRef}
 * through {@link SecretResolver}. It has no package reader and no placement reader: projection happened at stage
 * (OR-34), so the worker path never re-evaluates the package, and a package change after staging cannot change what a
 * resumed apply writes. The constructor admits nothing else, and a test pins that.
 *
 * <p><b>{@code I6}.</b> A secret is resolved in memory, copied into the one leaf that needs it, and its material is
 * closed (buffer zeroed) before the next is read; the rendered bytes this method works on are zeroed once
 * {@link RenderedConfig} has taken its own copy. The residue is the JDK's: the leaf value is a {@code String}, which no
 * code here can scrub (D-KEY 9). Every exception thrown here has a fixed message and no cause; the engine does not read
 * it either and classifies the failure itself as {@code render.failed}.
 */
public final class ConfigGenerationRenderedConfigSource implements RenderedConfigSource {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConfigGenerationRows rows;
    private final SecretResolver secrets;
    private final ConfigHasher hasher;
    private final YamlConfigRenderer renderer = new YamlConfigRenderer();

    public ConfigGenerationRenderedConfigSource(ConfigGenerationRows rows, SecretResolver secrets,
            ConfigHasher hasher) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    @Override
    public RenderedConfig render(PlacementRef placement, long generation) {
        TenantId tenant = placement.tenant();
        String json = rows.renderedEntries(tenant, placement.instance(), generation)
                .orElseThrow(() -> new IllegalStateException("the generation has no rendered set"));
        byte[] bytes = renderer.render(leaves(tenant, json));
        try {
            return RenderedConfig.of(tenant, bytes, hasher, plainPaths(json));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * OR-102: the dotted paths this generation renders as a plain value, as declared at stage. An entry carrying a
     * {@code secretRef} is a secret-flagged path and is NOT listed, so nothing downstream can digest it. Paths only.
     */
    private static java.util.Set<String> plainPaths(String json) {
        java.util.Set<String> paths = new java.util.TreeSet<>();
        try {
            for (JsonNode entry : JSON.readTree(json)) {
                if (entry.get("secretRef") == null && entry.get("value") != null) {
                    paths.add(entry.get("renderedPath").textValue());
                }
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the rendered set is unreadable");
        }
        return paths;
    }

    private List<YamlConfigRenderer.Leaf> leaves(TenantId tenant, String json) {
        JsonNode array;
        try {
            array = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            // the parser's message quotes the text it choked on; the cause is dropped on purpose
            throw new IllegalStateException("the rendered set is unreadable");
        }
        if (array == null || !array.isArray()) {
            throw new IllegalStateException("the rendered set is not a list");
        }
        List<YamlConfigRenderer.Leaf> leaves = new ArrayList<>();
        for (JsonNode entry : array) {
            JsonNode path = entry.get("renderedPath");
            if (path == null || !path.isTextual()) {
                throw new IllegalStateException("a rendered entry has no path");
            }
            // OR-103 / BE-D37: an entry under the overlay's reserved key is SM's, and only at a path OverlayKeys owns -
            // the one predicate the read-back strip uses. A stage already refuses anything else; this is the second line,
            // for a row a second writer put there.
            JsonNode instanceKey = entry.get("instanceKey");
            if (instanceKey != null && OverlayKeys.INSTANCE_KEY.equals(instanceKey.asText())
                    && !OverlayKeys.owns(path.textValue())) {
                throw new IllegalStateException("an overlay entry is not at a path SM owns");
            }
            JsonNode ref = entry.get("secretRef");
            JsonNode value = entry.get("value");
            if ((ref == null) == (value == null)) {
                throw new IllegalStateException("a rendered entry is not exactly one of a value or a reference");
            }
            leaves.add(new YamlConfigRenderer.Leaf(path.textValue(), ref != null ? resolve(tenant, ref) : value));
        }
        return leaves;
    }

    private JsonNode resolve(TenantId tenant, JsonNode ref) {
        UUID secretId;
        try {
            secretId = UUID.fromString(ref.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("a secret reference is malformed");
        }
        try (SecretMaterial material = secrets.resolve(tenant.value(), secretId, SecretPurpose.CONFIG_FIELD)) {
            return TextNode.valueOf(new String(material.bytes(), StandardCharsets.UTF_8));
        }
    }

    /** Names no collaborator state. */
    @Override
    public String toString() {
        return "ConfigGenerationRenderedConfigSource";
    }
}
