package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.gateway.http.metrics.MetricsRateGate;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import com.solace.sm.launcher.MonitoringItSupport.Http;
import com.solace.sm.launcher.MonitoringItSupport.Seeded;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 011 P11-7.2 Step 0 (MF18), the other direction: with the DEFAULT configured timeout (30 minutes), a stream that
 * lasts longer than the servlet container's own default (Tomcat's {@code asyncTimeout}, 30 s, measured against its
 * documentation) completes and is audited {@code completed}; and the ASYNC re-dispatch adds no authorization decision,
 * no rate count and no audit row. The client stalls for 36 s.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ExportItSupport.CountingAuthorization.class)
class ExportAsyncCompletionIT {

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TelemetryIngestPort ingest;

    @Autowired
    private MetricsRateGate gate;

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        registry.add("sm.dp.metrics.export.row-limit", () -> 5_000_000);
        ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        MonitoringItSupport.entitle(world, provisioned, 9);
    }

    @Test
    void aStreamLongerThanTheContainerDefaultCompletesAndOneExportIsOneOfEverything() throws Exception {
        Seeded s = MonitoringItSupport.seedInstances(new Http(port), ingest, ownerJdbc, world, 5, 9341);
        ExportItSupport.bulkSeries(ownerJdbc, world, s.a(), s.placementA());
        Login operator = new Http(port).login(DeclareFixtures.OPERATOR_ONE);
        ExportItSupport.EXPORT_AUTHORIZATIONS.set(0);
        long rateBefore = gate.checks();

        ExportItSupport.Streamed streamed = ExportItSupport.stalledExport(port, operator,
                "/api/console/v1/export/range?metric=cache.bulk&from=" + ExportItSupport.monthStart() + "&to="
                        + ExportItSupport.monthStart().plusSeconds(ExportItSupport.BULK_ROWS) + "&instanceId=" + s.a(),
                Duration.ofSeconds(36));

        assertEquals(200, streamed.status());
        assertEquals(false, streamed.endedEarly());
        assertEquals(ExportItSupport.BULK_ROWS, streamed.dataRows(), "every row, after a stream longer than 30 s");
        long completed = 0;
        for (int i = 0; i < 100 && completed == 0; i++) {
            completed = ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.audit_log WHERE verb = 'metrics.export'"
                    + " AND detail ->> 'new_status' = 'completed'", Map.of(), Long.class);
            if (completed == 0) {
                Thread.sleep(200);
            }
        }
        assertEquals(1, completed);
        assertEquals(1L, ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.audit_log WHERE verb = 'metrics.export'",
                Map.of(), Long.class), "one export is one audit row");
        assertEquals(1, ExportItSupport.EXPORT_AUTHORIZATIONS.get(), "one export is one authorization decision");
        assertEquals(1, gate.checks() - rateBefore, "one export is one rate count");
    }
}
