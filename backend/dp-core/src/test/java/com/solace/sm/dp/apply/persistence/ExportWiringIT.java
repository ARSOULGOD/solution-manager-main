package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.api.metrics.export.ExportBusyException;
import com.solace.sm.dp.api.metrics.export.ExportCommand;
import com.solace.sm.dp.api.metrics.export.MetricsExportService;
import com.solace.sm.dp.metrics.MetricsConfiguration;
import com.solace.sm.dp.metrics.MetricsDbBudget;
import com.solace.sm.dp.metrics.export.ExportProperties;
import com.solace.sm.dp.metrics.export.MetricsExportConfiguration;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-6.2: the production wiring. The same configuration classes {@code DataPlaneConfiguration} imports, on a real
 * context over the runtime credential: one {@link MetricsExportService}, built over the ONE {@link MetricsDbBudget} bean
 * (the budget is spent for the export when a query holds every permit), with its limits bound from {@code sm.dp.metrics.export}.
 */
@Testcontainers(disabledWithoutDocker = true)
class ExportWiringIT {

    @Configuration(proxyBeanMethods = false)
    static class Clocks {
        @Bean
        Clock smDataClock() {
            return Clock.systemUTC();
        }
    }

    private static ApplicationContextRunner runner(MetricsDb db, String... extra) {
        return new ApplicationContextRunner().withUserConfiguration(Clocks.class, ApplyPersistenceConfiguration.class,
                        MetricsPersistenceConfiguration.class, MetricsConfiguration.class, ExportPersistenceConfiguration.class,
                        MetricsExportConfiguration.class)
                .withPropertyValues("sm.data.runtime.url=" + db.url(), "sm.data.runtime.username=sm_dp_runtime",
                        "sm.data.runtime.password=" + SmDataPostgres.RUNTIME_PASSWORD)
                .withPropertyValues(extra);
    }

    @Test
    void theProductionWiringHasOneServiceOverTheOneBudgetAndBindsItsLimits() {
        MetricsDb db = MetricsDb.fresh("export_wiring");
        runner(db, "sm.dp.metrics.export.row-limit=1234", "sm.dp.metrics.export.budget-wait=100ms").run(context -> {
            assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
            assertEquals(1, context.getBeansOfType(MetricsExportService.class).size());
            assertEquals(1, context.getBeansOfType(MetricsDbBudget.class).size());
            assertEquals(1234, context.getBean(ExportProperties.class).rowLimit());
            assertEquals(Duration.ofMinutes(30), context.getBean(ExportProperties.class).statementTimeout());

            MetricsExportService service = context.getBean(MetricsExportService.class);
            MetricsDbBudget budget = context.getBean(MetricsDbBudget.class);
            ExportCommand command = new ExportCommand("cache.size", List.of(UUID.randomUUID()), Instant.parse("2026-10-06T00:00:00Z"),
                    Instant.parse("2026-10-06T00:00:10Z"), UUID.randomUUID());

            // Free budget: an export of nothing is just a header.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertEquals(0, service.stream(UUID.randomUUID(), command, out));
            assertEquals("instance_id,placement_id,timestamp,value\r\n", out.toString());

            // Every permit held by other work (queries): the export waits its bound and is refused, so it is the SAME budget.
            List<MetricsDbBudget.Permit> held = new ArrayList<>();
            for (int i = 0; i < budget.size(); i++) {
                held.add(budget.acquire(Duration.ofMillis(100)));
            }
            ExportBusyException busy = assertThrows(ExportBusyException.class,
                    () -> service.stream(UUID.randomUUID(), command, new ByteArrayOutputStream()));
            assertSame(ExportBusyException.Reason.DATABASE_BUDGET, busy.reason());
            held.forEach(MetricsDbBudget.Permit::close);
            assertEquals(budget.size(), budget.available());
        });
    }

    @Test
    void anInstallThatSkippedTheMetricsMigrationStillRefusesToStart() {
        MetricsDb db = MetricsDb.fresh("export_wiring_unmigrated", MetricsDb.Migrated.SM_DATA_ONLY);
        runner(db).run(context -> {
            Throwable failure = context.getStartupFailure();
            if (failure == null) {
                throw new AssertionError("a context over an unmigrated sm_metrics must not start");
            }
        });
    }
}
