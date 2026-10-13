package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.dp.api.metrics.export.ExportCommand;
import com.solace.sm.dp.api.metrics.export.MetricsExportService;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import com.solace.sm.launcher.MonitoringItSupport.Http;
import com.solace.sm.launcher.MonitoringItSupport.Seeded;
import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 011 P11-7.2: {@code exportRange} (row 60) over the REAL boot: a CSV streamed from a bound {@code SELECT}, one audit
 * row per export, the row limit refused before any byte, and the concurrency cap answering a clean {@code 429}
 * from the streaming thread. ⚠️ The route is wired and tested here; it is not counted served until P11-8.1's 105 M-row
 * measurement exists.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ExportItSupport.CountingAuthorization.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExportEndpointIT {

    private static final String EXPORT = "/api/console/v1/export/range";
    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static Seeded seeded;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TelemetryIngestPort ingest;

    @Autowired
    private MetricsExportService exportService;

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        registry.add("sm.dp.metrics.export.row-limit", () -> 30);
        ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        MonitoringItSupport.entitle(world, provisioned, 9);
    }

    private Seeded seeded() throws Exception {
        if (seeded == null) {
            seeded = MonitoringItSupport.seedInstances(new Http(port), ingest, ownerJdbc, world, 20, 9321);
        }
        return seeded;
    }

    private long exportAuditRows(String status) {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.audit_log WHERE verb = 'metrics.export'"
                + " AND detail ->> 'new_status' = :s", Map.of("s", status), Long.class);
    }

    /** OR-112 (6), W4-04: the DENY rows of refused exports, by their fixed reason code. */
    private long denyRows(String reason) {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.audit_log WHERE verb = 'metrics.export'"
                + " AND outcome = 'deny' AND detail ->> 'reason_code' = :r", Map.of("r", reason), Long.class);
    }

    /** The newest DENY row's detail joins to the refused response by its correlation id, and carries nothing else. */
    private void assertNewestDenyRowJoins(HttpResponse<String> refused, String reason) throws Exception {
        JsonNode detail = MonitoringItSupport.MAPPER.readTree(ownerJdbc.queryForObject(
                "SELECT detail::text FROM sm_control.audit_log WHERE verb = 'metrics.export' AND outcome = 'deny'"
                        + " ORDER BY audit_id DESC LIMIT 1", Map.of(), String.class));
        assertEquals(reason, detail.get("reason_code").asText());
        assertEquals(refused.headers().firstValue("X-Correlation-Id").orElseThrow(), detail.get("correlation_id").asText(),
                "the DENY row joins to the HTTP response");
        assertEquals(2, detail.size(), "a refusal records its reason and its correlation id and nothing of the request");
    }

    @Test
    @Order(1)
    void exportStreamsCsvAndWritesOneAuditRow() throws Exception {
        Seeded s = seeded();
        Http http = new Http(port);
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long before = exportAuditRows("completed");
        HttpResponse<String> response = http.get(EXPORT + "?metric=cache.size&from=" + s.start() + "&to="
                + s.start().plusSeconds(30L * 20) + "&instanceId=" + s.a(), operator);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
        assertTrue(response.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment"));
        String[] lines = response.body().split("\r\n");
        assertEquals("instance_id,placement_id,timestamp,value", lines[0]);
        assertEquals(21, lines.length, "a header and one row per stored sample");
        assertTrue(lines[1].startsWith(s.a().toString()));
        assertEquals(before + 1, exportAuditRows("completed"), "one export is one audit row");
        JsonNode detail = MonitoringItSupport.MAPPER.readTree(ownerJdbc.queryForObject(
                "SELECT detail::text FROM sm_control.audit_log WHERE verb = 'metrics.export' ORDER BY audit_id DESC LIMIT 1",
                Map.of(), String.class));
        assertEquals(20, detail.get("count").asLong());
        assertFalse(detail.toString().contains("cache.size"), "the audit row names no metric, selector or range");
    }

    @Test
    @Order(2)
    void aCallerWithoutTheExportVerbIsRefusedAndOutOfReachInstancesAreOmitted() throws Exception {
        Seeded s = seeded();
        Http http = new Http(port);
        long forbidden = denyRows("forbidden");
        HttpResponse<String> viewer = http.get(EXPORT + "?metric=cache.size&from=" + s.start() + "&to=" + Instant.now(),
                http.login(DeclareFixtures.VIEWER));
        assertEquals(403, viewer.statusCode());
        assertEquals(forbidden + 1, denyRows("forbidden"), "a refused export is one DENY row (W4-04)");
        assertNewestDenyRowJoins(viewer, "forbidden");
        // Operator one reaches instance a only: asking for b is a header-only export, exactly as for an id that is not there.
        HttpResponse<String> foreign = http.get(EXPORT + "?metric=cache.size&from=" + s.start() + "&to="
                + s.start().plusSeconds(600) + "&instanceId=" + s.b(), http.login(DeclareFixtures.OPERATOR_ONE));
        HttpResponse<String> missing = http.get(EXPORT + "?metric=cache.size&from=" + s.start() + "&to="
                + s.start().plusSeconds(600) + "&instanceId=" + UUID.randomUUID(), http.login(DeclareFixtures.OPERATOR_ONE));
        assertEquals(200, foreign.statusCode());
        assertEquals("instance_id,placement_id,timestamp,value\r\n", foreign.body());
        assertEquals(missing.body(), foreign.body());
        assertEquals(400, http.get(EXPORT + "?metric=Bad%20Name&from=" + s.start() + "&to=" + Instant.now(),
                http.login(DeclareFixtures.OPERATOR_ONE)).statusCode());
        assertEquals(400, http.get(EXPORT + "?metric=cache.size&from=" + Instant.now() + "&to=" + s.start(),
                http.login(DeclareFixtures.OPERATOR_ONE)).statusCode(), "to must be after from");
    }

    @Test
    @Order(3)
    void exportPastTheRowLimitIs422BeforeAnyByte() throws Exception {
        Seeded s = seeded();
        Http http = new Http(port);
        long audits = exportAuditRows("completed") + exportAuditRows("failed") + exportAuditRows("cut");
        long tooLarge = denyRows("export_too_large");
        HttpResponse<String> response = http.get(EXPORT + "?metric=cache.size&from=" + s.start() + "&to="
                + s.start().plusSeconds(30L * 400) + "&instanceId=" + s.a(), http.login(DeclareFixtures.OPERATOR_ONE));
        assertEquals(422, response.statusCode(), response.body());
        JsonNode problem = MonitoringItSupport.json(response);
        assertEquals("EXPORT_TOO_LARGE", problem.get("code").asText());
        assertEquals(30, problem.get("limit").asLong());
        assertTrue(problem.get("projectedRows").asLong() > 30);
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
        assertTrue(response.headers().firstValue("Content-Disposition").isEmpty(), "nothing of a download was sent");
        assertEquals(audits, exportAuditRows("completed") + exportAuditRows("failed") + exportAuditRows("cut"),
                "a refused export is not an export");
        assertEquals(tooLarge + 1, denyRows("export_too_large"), "it is one DENY row instead (W4-04)");
        assertNewestDenyRowJoins(response, "export_too_large");
    }

    @Test
    @Order(4)
    void aSecondExportOfTheOrganizationIsACleanTooManyRequestsFromTheStreamingThread() throws Exception {
        Seeded s = seeded();
        Http http = new Http(port);
        // Hold the organization's one export slot with a stream whose output blocks until released.
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                exportService.stream(world.organization(), new ExportCommand("cache.size", List.of(s.a()), s.start(),
                        s.start().plusSeconds(600), UUID.randomUUID()), new OutputStream() {
                            @Override
                            public void write(int b) throws IOException {
                                write(new byte[] {(byte) b}, 0, 1);
                            }

                            @Override
                            public void write(byte[] bytes, int off, int len) throws IOException {
                                held.countDown();
                                try {
                                    release.await(60, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        });
            } catch (Exception ignored) {
                // released by the test
            }
        });
        holder.start();
        try {
            long rateLimited = denyRows("rate_limited");
            assertTrue(held.await(30, TimeUnit.SECONDS), "the holder owns the slot");
            HttpResponse<String> second = http.get(EXPORT + "?metric=cache.size&from=" + s.start() + "&to="
                    + s.start().plusSeconds(600) + "&instanceId=" + s.a(), http.login(DeclareFixtures.OPERATOR_ONE));
            assertEquals(429, second.statusCode(), second.body());
            JsonNode problem = MonitoringItSupport.json(second);
            assertEquals("RATE_LIMITED", problem.get("code").asText());
            assertEquals("export-concurrency", problem.get("reason").asText());
            assertNotNull(second.headers().firstValue("Retry-After").orElse(null));
            assertTrue(second.headers().firstValue("Content-Disposition").isEmpty(),
                    "a refusal from the streaming thread carries no download headers");
            assertEquals(rateLimited + 1, denyRows("rate_limited"),
                    "the 429 from the streaming thread's async dispatch is one DENY row (W4-04)");
            assertNewestDenyRowJoins(second, "rate_limited");
        } finally {
            release.countDown();
            holder.join(30_000);
        }
    }
}
