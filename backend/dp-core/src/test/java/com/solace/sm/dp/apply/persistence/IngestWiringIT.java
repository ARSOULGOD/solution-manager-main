package com.solace.sm.dp.apply.persistence;

import static com.solace.sm.dp.apply.persistence.IngestFixture.base;
import static com.solace.sm.dp.apply.persistence.IngestFixture.metric;
import static com.solace.sm.dp.apply.persistence.IngestFixture.sample;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.dp.metrics.MetricsConfiguration;
import com.solace.sm.dp.metrics.MetricsDbBudget;
import com.solace.sm.dp.metrics.ingest.IngestService;
import com.solace.sm.dp.metrics.ingest.MetricsIngestConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-3.1: the production wiring. The same configuration classes {@code DataPlaneConfiguration} imports, on a
 * real Spring context over the runtime credential: the one {@link TelemetryIngestPort} bean ingests end to end, the
 * pool size is the one written down (Step 0), the shared budget is one bean, and an install that skipped the metrics
 * migration refuses to start naming the step.
 */
@Testcontainers(disabledWithoutDocker = true)
class IngestWiringIT {

    @Configuration(proxyBeanMethods = false)
    static class Clocks {
        @Bean
        Clock smDataClock() {
            return Clock.systemUTC();
        }
    }

    private static ApplicationContextRunner runner(MetricsDb db, String... extra) {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Clocks.class,
                        ApplyPersistenceConfiguration.class, MetricsPersistenceConfiguration.class,
                        MetricDefinitionPersistenceConfiguration.class, IngestPersistenceConfiguration.class,
                        MetricsConfiguration.class, MetricsIngestConfiguration.class, RollupPersistenceConfiguration.class,
                        com.solace.sm.dp.metrics.rollup.MetricsRollupConfiguration.class)
                .withPropertyValues("sm.data.runtime.url=" + db.url(), "sm.data.runtime.username=sm_dp_runtime",
                        "sm.data.runtime.password=" + SmDataPostgres.RUNTIME_PASSWORD);
        return extra.length == 0 ? runner : runner.withPropertyValues(extra);
    }

    @Test
    void theProductionWiringIngestsEndToEndAndTheBudgetIsOneBean() {
        MetricsDb db = MetricsDb.fresh("ingest_wiring");
        runner(db).run(context -> {
            assertTrue(context.getStartupFailure() == null, String.valueOf(context.getStartupFailure()));
            assertEquals(1, context.getBeansOfType(TelemetryIngestPort.class).size());
            assertEquals(1, context.getBeansOfType(MetricsDbBudget.class).size());

            IngestFixture.Site site = siteOn(db);
            TelemetryIngestPort port = context.getBean(TelemetryIngestPort.class);
            IngestBatch batch = IngestBatch.polled(site.tenant(), site.placement(),
                    List.of(sample("cache.size", base(), 7, null), sample("cache.unknown", base(), 1, null)));
            IngestResult.Committed committed = assertInstanceOf(IngestResult.Committed.class, port.ingest(batch));
            assertEquals(1, committed.accepted());
            assertEquals(1, committed.dropped().get(CollectorReason.UNDECLARED_DROPPED));
            LocalDate today = java.time.LocalDate.now(ZoneOffset.UTC);
            assertNotNull(db.superuserJdbc().queryForObject("SELECT count FROM sm_metrics.ingest_counter WHERE tenant_id = :t"
                            + " AND reason = 'undeclared_dropped' AND day IN (:d, :y)",
                    new MapSqlParameterSource().addValue("t", site.tenant()).addValue("d", today)
                            .addValue("y", today.minusDays(1)), Long.class));
        });
    }

    @Test
    void theRollupsTheRunwayKeeperAndTheDiskAlarmAreWiredAndTheKeeperKeepsTheRunway() throws Exception {
        MetricsDb db = MetricsDb.fresh("ingest_wiring_rollup");
        runner(db).run(context -> {
            assertTrue(context.getStartupFailure() == null, String.valueOf(context.getStartupFailure()));
            assertNotNull(context.getBean(com.solace.sm.dp.metrics.rollup.RollupService.class));
            com.solace.sm.dp.metrics.rollup.PartitionRunway runway = context.getBean(com.solace.sm.dp.metrics.rollup.PartitionRunway.class);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && runway.runwayEnd().isEmpty()) {
                Thread.sleep(50);
            }
            assertTrue(runway.runwayEnd().isPresent(), "the boot run extended the runway on the shared executor");
            assertTrue(!runway.runwayShort(), "this month + 3 ahead is more than a month of runway");
            assertEquals(com.solace.sm.dp.api.metrics.health.DiskAlarmView.State.UNCONFIGURED,
                    context.getBean(com.solace.sm.dp.metrics.rollup.MetricsDiskAlarm.class).view().orElseThrow().state());
        });
    }

    /** One site built through the fixture's own SQL, on this database. */
    private static IngestFixture.Site siteOn(MetricsDb db) {
        try (IngestFixture fx = IngestFixture.on(db)) {
            return fx.site("via-ssh", "2.4", List.of(metric("cache.size", "gauge", "entries", "cache", 30)), List.of());
        }
    }

    @Test
    void thePoolSizeIsWrittenDownNotHikarisDefault() {
        MetricsDb db = MetricsDb.fresh("ingest_wiring_pool");
        runner(db).run(context -> {
            assertEquals(10, context.getBean(HikariDataSource.class).getMaximumPoolSize(),
                    "MF16: 10 is the pool arithmetic's pool; query+export 4, ingest 1, the rest 5");
        });
        runner(db, "sm.data.pool.maximum-size=7").run(context -> {
            assertEquals(7, context.getBean(HikariDataSource.class).getMaximumPoolSize());
        });
    }

    @Test
    void anInstallThatSkippedTheMetricsMigrationRefusesToStart() {
        MetricsDb db = MetricsDb.fresh("ingest_wiring_unmigrated", MetricsDb.Migrated.SM_DATA_ONLY);
        runner(db).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure, "a context over an unmigrated sm_metrics must not start");
            StringBuilder chain = new StringBuilder();
            for (Throwable t = failure; t != null; t = t.getCause()) {
                chain.append(t.getMessage()).append(" | ");
            }
            assertTrue(chain.toString().contains("sm_metrics is not migrated"), chain.toString());
        });
    }
}
