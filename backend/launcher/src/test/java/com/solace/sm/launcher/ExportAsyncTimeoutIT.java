package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Plan 011 P11-7.2 Step 0 (MF18): the export's async timeout is the CONFIGURED value, and one export is one authorization,
 * one rate count and one audit row.
 *
 * <p>The timeout is set to 2 s (the container's own default is 30 s) and the client stalls for 6 s with a 30 MB stream
 * backed up against it: the stream is cut and audited {@code cut}. {@code ExportAsyncCompletionIT} is the other
 * direction (the configured 30 minutes lets a stream longer than the container's 30 s complete).
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ExportItSupport.CountingAuthorization.class)
class ExportAsyncTimeoutIT {

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
        registry.add("sm.gateway.metrics.export-async-timeout", () -> "2s");
        registry.add("sm.dp.metrics.export.row-limit", () -> 5_000_000);
        ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        MonitoringItSupport.entitle(world, provisioned, 9);
    }

    @Test
    void theExportAsyncTimeoutIsTheConfiguredValueNotTheContainerDefault() throws Exception {
        Seeded s = MonitoringItSupport.seedInstances(new Http(port), ingest, ownerJdbc, world, 5, 9331);
        ExportItSupport.bulkSeries(ownerJdbc, world, s.a(), s.placementA());
        Login operator = new Http(port).login(DeclareFixtures.OPERATOR_ONE);
        ExportItSupport.EXPORT_AUTHORIZATIONS.set(0);
        long rateBefore = gate.checks();

        ExportItSupport.Streamed streamed = ExportItSupport.stalledExport(port, operator,
                "/api/console/v1/export/range?metric=cache.bulk&from=" + ExportItSupport.monthStart() + "&to="
                        + ExportItSupport.monthStart().plusSeconds(ExportItSupport.BULK_ROWS) + "&instanceId=" + s.a(),
                Duration.ofSeconds(6));

        assertEquals(200, streamed.status());
        assertTrue(streamed.dataRows() < ExportItSupport.BULK_ROWS,
                "cut at the configured 2 s, not run to the end: client got " + streamed.dataRows() + " rows");
        long cut = 0;
        for (int i = 0; i < 100 && cut == 0; i++) {
            cut = ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.audit_log WHERE verb = 'metrics.export'"
                    + " AND detail ->> 'new_status' = 'cut'", Map.of(), Long.class);
            if (cut == 0) {
                Thread.sleep(200);
            }
        }
        assertEquals(1, cut, "audited as cut");
        assertEquals(1L, ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.audit_log WHERE verb = 'metrics.export'",
                Map.of(), Long.class), "one export is one audit row");
        assertEquals(1, ExportItSupport.EXPORT_AUTHORIZATIONS.get(), "one export is one authorization decision");
        assertEquals(1, gate.checks() - rateBefore, "one export is one rate count");
    }
}
