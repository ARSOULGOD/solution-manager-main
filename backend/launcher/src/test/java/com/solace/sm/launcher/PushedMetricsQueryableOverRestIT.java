package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.IngestSample;
import com.solace.sm.dp.api.metrics.ingest.SourceDiscriminator;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.launcher.DeclareFixtures.World;
import com.solace.sm.launcher.MonitoringItSupport.Http;
import com.solace.sm.launcher.MonitoringItSupport.Seeded;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 011 path 2's REST half, over the real boot (created by P11-7.2, the second of P11-5.1 and P11-7.2 to merge): a
 * sample that arrived by BROKER PUSH, through the one ingest seam, lands in {@code metrics_raw} under a
 * {@code broker-push} series and is then answered by {@code queryRange} like any polled one. P11-5.1's own tests prove
 * the subscriber and its filter against a broker; this proves the stored result is reachable over the console's HTTP
 * surface by a principal with the verb, with no further wiring.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PushedMetricsQueryableOverRestIT {

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TelemetryIngestPort ingest;

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        MonitoringItSupport.entitle(world, provisioned, 9);
    }

    @Test
    void aBrokerPushedSampleIsQueryableOverTheConsoleSurface() throws Exception {
        Http http = new Http(port);
        Seeded s = MonitoringItSupport.seedInstances(http, ingest, ownerJdbc, world, 3, 9351);
        // A metric the polled seed never wrote, so the only series for it is the pushed one.
        Instant last = Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        List<IngestSample> pushed = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Instant ts = last.minusSeconds(10L * (4 - i));
            pushed.add(new IngestSample("cache.hit_rate", ts, 0.5 + i / 10.0, ts));
        }
        UUID connection = UUID.randomUUID();
        IngestResult result = ingest.ingest(new IngestBatch(world.organization(), connection, s.placementA(),
                s.placementA().toString(), SourceDiscriminator.BROKER_PUSH, pushed, List.of()));
        assertEquals(IngestResult.Committed.class, result.getClass(), "ingest: " + result);
        assertEquals("broker-push", ownerJdbc.queryForObject("SELECT source_discriminator FROM sm_metrics.series"
                + " WHERE tenant_id = :t AND metric_name = 'cache.hit_rate'", Map.of("t", world.organization()), String.class));

        ObjectNode request = MonitoringItSupport.MAPPER.createObjectNode();
        request.put("queryVersion", 1).put("metric", "cache.hit_rate");
        ObjectNode selector = request.putObject("selector");
        selector.put("entityType", "cache-engine");
        selector.putArray("instanceIds").add(s.a().toString());
        selector.putObject("labels");
        request.putObject("range").put("from", last.minusSeconds(600).toString()).put("to", Instant.now().toString());
        request.put("resolution", "auto").put("seriesBy", "instance");
        request.putObject("aggregation").put("acrossReplicas", "auto").put("acrossInstances", "none");

        HttpResponse<String> response = http.post("/api/console/v1/query/range", http.login(DeclareFixtures.OPERATOR_ONE),
                request);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode series = MonitoringItSupport.json(response).get("series");
        assertEquals(1, series.size());
        JsonNode points = series.get(0).get("points");
        assertEquals(5, points.size(), "every pushed sample is a stored, queryable point");
        assertEquals(0.5, points.get(0).get(1).asDouble(), 1e-9);
        assertEquals(0.9, points.get(4).get(1).asDouble(), 1e-9);
        assertEquals(s.a().toString(), series.get(0).at("/seriesRef/instanceId").asText());
        assertTrue(MonitoringItSupport.json(response).at("/partial/unavailable").isEmpty());
    }
}
