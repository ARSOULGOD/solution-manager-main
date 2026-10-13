package com.solace.sm.dp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.GenerationView;
import com.solace.sm.dp.api.config.LoggerSupport;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.production.ConfigGenerationRenderedConfigSource;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.TestHashers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * OR-103 / BE-D37 at the renderer: the overlay a stage stored reaches the file, and the renderer refuses an
 * overlay-keyed entry at a path {@link OverlayKeys} does not own, so the renderer and the read-back strip share one
 * predicate. No database: the renderer reads one row and (here) no secret.
 */
class OverlayRenderTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final PlacementRef PLACEMENT = new PlacementRef(TENANT, new InstanceId(UUID.randomUUID()),
            new PlacementId(UUID.randomUUID()));

    /** Serves one stored rendered set; everything else is unreachable from the renderer. */
    private static final class OneRow implements ConfigGenerationRows {
        final String json;

        OneRow(String json) {
            this.json = json;
        }

        @Override public Optional<String> renderedEntries(TenantId t, InstanceId i, long generation) {
            return Optional.of(json);
        }

        @Override public Optional<InstanceHead> lockInstance(TenantId t, InstanceId i) { throw new AssertionError(); }
        @Override public Optional<GenerationRecord> latest(TenantId t, InstanceId i) { throw new AssertionError(); }
        @Override public Optional<String> layout(TenantId t, InstanceId i) { throw new AssertionError(); }
        @Override public void saveLayout(TenantId t, InstanceId i, String l, UUID a, java.time.Instant at) { throw new AssertionError(); }
        @Override public long insert(GenerationRecord row) { throw new AssertionError(); }
        @Override public long advanceHead(TenantId t, InstanceId i, long last) { throw new AssertionError(); }
        @Override public int pruneBeyondNewest(TenantId t, InstanceId i, int keep) { throw new AssertionError(); }
        @Override public boolean namedByAnApplyRequest(TenantId t, InstanceId i, long g) { throw new AssertionError(); }
        @Override public Optional<GenerationRecord> find(TenantId t, InstanceId i, long g) { throw new AssertionError(); }
        @Override public Optional<GenerationView> view(TenantId t, InstanceId i, long g) { throw new AssertionError(); }
        @Override public List<GenerationView> history(TenantId t, InstanceId i, int limit) { throw new AssertionError(); }
    }

    private static ConfigGenerationRenderedConfigSource source(String json) {
        return new ConfigGenerationRenderedConfigSource(new OneRow(json),
                (tenant, secret, purpose) -> { throw new AssertionError("no secret in this set"); },
                TestHashers.SHA256);
    }

    /** What a stage stores: the same canonical text the renderer reads, taken through the real stage path. */
    private static String stored(LoggerSupport support) throws Exception {
        List<RenderedEntryValue> fields = List.of(
                RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"5m\""));
        return CanonicalContent.of(new StageCommand(1, "cache", "2.4", "{\"instances\":[]}", "{}", fields,
                GenerationOrigin.EDIT, null, UUID.randomUUID(), null, support)).renderedEntries();
    }

    private static JsonNode yaml(byte[] bytes) throws Exception {
        return YAML.readTree(new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void theOverlayAStageStoredIsRenderedBesideThePackagesFieldsAndMovesNoPort() throws Exception {
        JsonNode file = yaml(source(stored(new LoggerSupport(List.of("health", "info", "metrics"))))
                .render(PLACEMENT, 1).bytes());

        assertEquals("5m", file.at("/cache-engine/ttl").asText());
        assertEquals("health,info,loggers,metrics",
                file.at("/management/endpoints/web/exposure/include").asText());
        assertEquals("read-only", file.at("/management/endpoint/loggers/access").asText());
        assertTrue(file.at("/management/server").isMissingNode(), "no management port or address: OR-103");
    }

    @Test
    void aGenerationWithNoOverlayRendersNoManagementKeyAtAll() throws Exception {
        JsonNode file = yaml(source(stored(null)).render(PLACEMENT, 1).bytes());

        assertTrue(file.at("/management").isMissingNode());
    }

    @Test
    void theRendererRefusesAnOverlayKeyedEntryAtAPathTheStripFunctionDoesNotOwn() {
        String forged = "[{\"instanceKey\":\"" + OverlayKeys.INSTANCE_KEY
                + "\",\"renderedPath\":\"management.server.port\",\"value\":9999}]";

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> source(forged).render(PLACEMENT, 1));

        assertFalse(String.valueOf(refused.getMessage()).contains("9999"));
        assertEquals(null, refused.getCause());
    }

    @Test
    void everyPathTheRendererWritesForTheOverlayIsOnesTheStripFunctionOwns() throws Exception {
        List<String> written = new ArrayList<>();
        for (OverlayKeys.Entry e : OverlayKeys.forLoggerSupport(new LoggerSupport(List.of("health")))) {
            written.add(e.path());
        }

        assertFalse(written.isEmpty());
        assertTrue(written.stream().allMatch(OverlayKeys::owns), written.toString());
    }
}
