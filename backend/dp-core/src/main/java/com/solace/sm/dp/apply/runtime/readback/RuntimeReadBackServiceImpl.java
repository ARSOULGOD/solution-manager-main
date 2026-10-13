package com.solace.sm.dp.apply.runtime.readback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.dp.api.readback.FieldPathTable;
import com.solace.sm.dp.api.readback.ReadBackCandidate;
import com.solace.sm.dp.api.readback.ReadBackRefusedException;
import com.solace.sm.dp.api.readback.ReadBackRefusedException.Reason;
import com.solace.sm.dp.api.readback.RuntimeReadBackService;
import com.solace.sm.dp.api.secret.SecretCompare;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigHashUnverifiableException;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.ConfigReadBack;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * D-ACCEPT's data-plane half (plan 010 T10.4; {@code BE-D39}-{@code BE-D41}): read one placement's file back through the
 * seam's read-only {@code readConfig}, parse it ({@link ConfigFileParser}), map its declared paths
 * ({@link ReadBackMapper}) and report what could not be adopted.
 *
 * <p>⛔ <b>{@code I6}, in one place.</b> The file is the host's own and holds every secret SM rendered, in clear. It lives
 * here as a {@link ConfigReadBack} (zeroed in a {@code finally}), is parsed once, and a secret field's node is handed to the
 * compare-only {@link SecretCompare} port and nowhere else: this class holds no {@code SecretResolver}, so it cannot fetch
 * a stored secret, store a value, or return one. What it returns names paths and carries {@code same}/{@code differs}.
 *
 * <p><b>No transaction.</b> The read is one bounded SSH read in the caller's thread. This class has no transaction template
 * and the gateway holds no datasource, so no database transaction is open across it ({@code m3}).
 *
 * <p><b>{@code I5}.</b> The placement is looked up by {@code (tenant, instance, placement)} and must be live; the request
 * names no host, path or command.
 */
public final class RuntimeReadBackServiceImpl implements RuntimeReadBackService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Whether {@code (tenant, instance, placement)} is a live placement: a retired or foreign one is not. */
    @FunctionalInterface
    public interface LivePlacements {
        boolean isLive(UUID tenant, UUID instance, UUID placement);
    }

    private final LivePlacements placements;
    private final Supplier<RuntimeAdapter> adapter;
    private final Supplier<RenderedConfigSource> renderer;
    private final SecretCompare secrets;
    private final ConfigHasher hasher;
    private final SmOverlayKeys overlay;
    private final ConfiguredLevelPaths levels;

    /** The level paths the instance's latest generation configures (T9.4): SM wrote them, so they are SM's in the file. */
    @FunctionalInterface
    public interface ConfiguredLevelPaths {
        java.util.Set<String> of(UUID organizationId, UUID instanceId);
    }

    public RuntimeReadBackServiceImpl(LivePlacements placements, Supplier<RuntimeAdapter> adapter,
            Supplier<RenderedConfigSource> renderer, SecretCompare secrets, ConfigHasher hasher, SmOverlayKeys overlay,
            ConfiguredLevelPaths levels) {
        this.placements = Objects.requireNonNull(placements, "placements");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.overlay = Objects.requireNonNull(overlay, "overlay");
        this.levels = Objects.requireNonNull(levels, "levels");
    }

    @Override
    public ReadBackCandidate readBack(UUID organizationId, UUID instanceId, UUID placementId, FieldPathTable table) {
        Objects.requireNonNull(organizationId, "organization");
        Objects.requireNonNull(instanceId, "instance");
        Objects.requireNonNull(placementId, "placement");
        Objects.requireNonNull(table, "table");
        if (!placements.isLive(organizationId, instanceId, placementId)) {
            throw new ReadBackRefusedException(Reason.PLACEMENT_NOT_FOUND);
        }
        PlacementRef placement = placementRef(organizationId, instanceId, placementId);
        RuntimeAdapter runtime = adapter.get();
        if (runtime == null) {
            throw new ReadBackRefusedException(Reason.HOST_UNREADABLE);
        }
        try (ConfigReadBack back = read(runtime, placement)) {
            byte[] bytes = back.bytes();
            JsonNode tree;
            try {
                tree = ConfigFileParser.parse(bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
            java.util.Set<String> levelPaths = levels.of(organizationId, instanceId);
            ReadBackMapper.Walked walked = ReadBackMapper.walk(tree, table,
                    path -> overlay.owns(path) || levelPaths.contains(path),
                    (field, node) -> compare(organizationId, field, node));
            List<ReadBackCandidate.MappedValue> values = new ArrayList<>();
            for (ReadBackMapper.Mapped mapped : walked.values()) {
                values.add(new ReadBackCandidate.MappedValue(mapped.field().instanceKey(), mapped.field().fieldKey(),
                        json(mapped.value())));
            }
            return new ReadBackCandidate(placementId, values, walked.notAccepted(), walked.truncated(), back.hash());
        }
    }

    /**
     * The in-memory comparison of a secret field's node with the stored secret ({@code V-4}). The candidate text is built
     * for this one call and zeroed straight after; a node that is not a scalar cannot equal a stored string and is not
     * compared. Nothing of either side is returned.
     */
    private SecretCompare.Outcome compare(UUID organizationId, FieldPathTable.Entry field, JsonNode node) {
        if (field.secretRef() == null) {
            // SM holds no secret for this field and the file carries one: that is a difference, and needs no stored value
            return SecretCompare.Outcome.DIFFERS;
        }
        if (!node.isValueNode() || node.isNull()) {
            return SecretCompare.Outcome.DIFFERS;
        }
        byte[] candidate = node.asText().getBytes(StandardCharsets.UTF_8);
        try {
            return secrets.compare(organizationId, field.secretRef(), candidate);
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
    }

    private ConfigReadBack read(RuntimeAdapter runtime, PlacementRef placement) {
        try {
            List<TargetReplica> replicas = runtime.replicas(placement);
            if (replicas.isEmpty()) {
                throw new ReadBackRefusedException(Reason.HOST_UNREADABLE);
            }
            // v1 is single-placement (BE-D23) and an SSH placement has exactly one replica: the file is the placement's
            return runtime.readConfig(replicas.get(0));
        } catch (RuntimeAdapterException e) {
            throw new ReadBackRefusedException(classify(e.errorClass().token()));
        }
    }

    /** A classifier from the adapter to a closed reason. Anything it does not know is the host not answering. */
    static Reason classify(String token) {
        if ("config.read.absent".equals(token)) {
            return Reason.FILE_ABSENT;
        }
        if (token.endsWith("config.not.regular") || token.endsWith("config.too.large")) {
            return Reason.FILE_NOT_READABLE;
        }
        if ("placement.unresolvable".equals(token)) {
            return Reason.PLACEMENT_NOT_FOUND;
        }
        return Reason.HOST_UNREADABLE;
    }

    @Override
    public boolean fileDiffersFromRendering(UUID organizationId, UUID instanceId, UUID placementId, long generation,
            ReadBackCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        RenderedConfigSource source = renderer.get();
        if (source == null) {
            return true;
        }
        PlacementRef placement = placementRef(organizationId, instanceId, placementId);
        byte[] rendered = null;
        try {
            rendered = source.render(placement, generation).bytes();
            return !hasher.verifies(placement.tenant(), candidate.readBackHash(), rendered);
        } catch (ConfigHashUnverifiableException e) {
            return true;   // "could not compare" is reported as "may differ", never as "the same"
        } catch (RuntimeException e) {
            return true;
        } finally {
            if (rendered != null) {
                Arrays.fill(rendered, (byte) 0);
            }
        }
    }

    private static PlacementRef placementRef(UUID organizationId, UUID instanceId, UUID placementId) {
        return new PlacementRef(new TenantId(organizationId), new InstanceId(instanceId), new PlacementId(placementId));
    }

    private static String json(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a mapped value could not be written as JSON");
        }
    }

    /** Names nothing. */
    @Override
    public String toString() {
        return "RuntimeReadBackServiceImpl";
    }
}
