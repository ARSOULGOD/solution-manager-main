package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.TestHashers;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SshDockerConfigEditorTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final PlacementRef PLACEMENT = new PlacementRef(new TenantId(UUID.randomUUID()),
            new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));

    @Test
    void editsOnlyTheSingleCacheEngineTtl() {
        byte[] source = ("cache-engine:\n  ttl: 120m\n  solace:\n    password: retained\nother: value\n")
                .getBytes(StandardCharsets.UTF_8);
        SshDockerConfigEditor editor = new SshDockerConfigEditor(value -> placement(), transport(source), TestHashers.SHA256);

        String result = new String(editor.withTtl(PLACEMENT, "90m").bytes(), StandardCharsets.UTF_8);

        assertEquals("cache-engine:\n  ttl: 90m\n  solace:\n    password: retained\nother: value\n", result);
    }

    @Test
    void refusesAmbiguousOrUnsafeTtlEdits() {
        SshDockerConfigEditor editor = new SshDockerConfigEditor(value -> placement(),
                transport("cache-engine:\n  ttl: 1m\n  ttl: 2m\n".getBytes(StandardCharsets.UTF_8)),
                TestHashers.SHA256);

        assertThrows(RuntimeAdapterException.class, () -> editor.withTtl(PLACEMENT, "5m"));
        assertThrows(RuntimeAdapterException.class, () -> editor.withTtl(PLACEMENT, "5m\npassword: leaked"));
    }

    @Test
    void rendersOnlyAllowlistedFieldsAndRetainsRemoteSecret() throws Exception {
        byte[] source = ("cache-engine:\n  ttl: 120m\n  namespace: original\n  solace:\n    password: retained\n"
                + "    host: old-broker\nother: value\n").getBytes(StandardCharsets.UTF_8);
        SshDockerConfigEditor editor = new SshDockerConfigEditor(value -> placement(), transport(source), TestHashers.SHA256);
        ObjectNode values = new ObjectMapper().createObjectNode();
        ObjectNode cache = values.putObject("cache-engine");
        cache.put("ttl", "90m");
        cache.put("namespace", "orders");
        cache.put("key-source", "HEADER");
        cache.put("solace.host", "broker.example");
        cache.put("solace.enabled", true);
        cache.putArray("solace.queues").add("cache-q");

        JsonNode rendered = YAML.readTree(editor.withCacheConfiguration(PLACEMENT, values).bytes());

        assertEquals("90m", rendered.path("cache-engine").path("ttl").asText());
        assertEquals("orders", rendered.path("cache-engine").path("namespace").asText());
        assertEquals("HEADER", rendered.path("cache-engine").path("key-source").asText());
        assertEquals("broker.example", rendered.path("cache-engine").path("solace").path("host").asText());
        assertEquals("retained", rendered.path("cache-engine").path("solace").path("password").asText());
        assertEquals("value", rendered.path("other").asText());
    }

    @Test
    void rejectsSecretAndUnknownFieldsBeforeRendering() {
        ObjectNode secret = new ObjectMapper().createObjectNode();
        secret.putObject("cache-engine").put("solace.password", "browser-secret");
        ObjectNode unknown = new ObjectMapper().createObjectNode();
        unknown.putObject("cache-engine").put("shell.command", "unsafe");

        assertThrows(RuntimeAdapterException.class, () -> SshDockerConfigEditor.validateCacheConfiguration(secret));
        assertThrows(RuntimeAdapterException.class, () -> SshDockerConfigEditor.validateCacheConfiguration(unknown));
    }

    @Test
    void permitsAConfiguredBrokerStateWithoutAcceptingItsSecret() throws Exception {
        byte[] source = ("cache-engine:\n  ttl: 120m\n  solace:\n    enabled: true\n    password: retained\n")
                .getBytes(StandardCharsets.UTF_8);
        SshDockerConfigEditor editor = new SshDockerConfigEditor(value -> placement(), transport(source), TestHashers.SHA256);
        ObjectNode values = new ObjectMapper().createObjectNode();
        values.putObject("cache-engine").put("solace.enabled", true);

        JsonNode rendered = YAML.readTree(editor.withCacheConfiguration(PLACEMENT, values).bytes());

        assertEquals(true, rendered.path("cache-engine").path("solace").path("enabled").booleanValue());
        assertEquals("retained", rendered.path("cache-engine").path("solace").path("password").asText());
    }

    /** OR-102: the editor names the paths it edited, which are the package's browser-safe, non-secret ones. */
    @Test
    void theEditedConfigNamesTheNonSecretPathsItWrote() {
        byte[] source = ("cache-engine:\n  ttl: 120m\n  solace:\n    password: retained\n")
                .getBytes(StandardCharsets.UTF_8);
        SshDockerConfigEditor editor = new SshDockerConfigEditor(value -> placement(), transport(source), TestHashers.SHA256);
        ObjectNode values = new ObjectMapper().createObjectNode();
        ObjectNode cache = values.putObject("cache-engine");
        cache.put("ttl", "90m");
        cache.put("solace.host", "broker.example");

        assertEquals(java.util.Set.of("cache-engine.ttl", "cache-engine.solace.host"),
                editor.withCacheConfiguration(PLACEMENT, values).nonSecretPaths());
        assertEquals(java.util.Set.of("cache-engine.ttl"), editor.withTtl(PLACEMENT, "90m").nonSecretPaths());
    }

    private static SshDockerPlacement placement() {
        return new SshDockerPlacement(
                new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), "host.example", 22, "demo", java.util.UUID.randomUUID(), "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "cache", "/srv/config", "/srv/config", "/app/config", "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1);
    }

    private static SshDockerTransport transport(byte[] source) {
        return new SshDockerTransport() {
            @Override public ContainerSnapshot inspect(SshDockerPlacement ignored) { throw new UnsupportedOperationException(); }
            @Override public void verifyConfigDirectoryWritable(SshDockerPlacement ignored) { }
            @Override public Optional<byte[]> readFile(SshDockerPlacement ignored, String path) { return Optional.of(source); }
            @Override public void writeFileAtomically(SshDockerPlacement ignored, String path, byte[] value) { }
            @Override public void deleteFile(SshDockerPlacement ignored, String path) { }
            @Override public boolean restart(SshDockerPlacement ignored) { return false; }
            @Override public boolean stop(SshDockerPlacement ignored) { return false; }
        };
    }
}
