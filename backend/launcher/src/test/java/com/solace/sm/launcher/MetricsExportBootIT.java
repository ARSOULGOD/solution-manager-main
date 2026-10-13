package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.api.metrics.export.ExportBusyException;
import com.solace.sm.dp.api.metrics.export.ExportCommand;
import com.solace.sm.dp.api.metrics.export.MetricsExportService;
import com.solace.sm.dp.metrics.MetricsDbBudget;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 011 P11-6.2, in the PRODUCTION boot: the new export configurations are reached through
 * {@code DataPlaneConfiguration} (the launcher imports nothing of its own for them), there is one
 * {@link MetricsExportService}, and it spends the ONE {@link MetricsDbBudget} the query side will also use.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplyItSupport.ExecutorConfiguration.class)
class MetricsExportBootIT {

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandaloneWithOwnExecutor();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.dp.metrics.export.budget-wait", () -> "100ms");
    }

    @Test
    void theProductionBootHasOneExportServiceOverTheOneBudget() throws Exception {
        assertEquals(1, context.getBeansOfType(MetricsExportService.class).size());
        assertEquals(1, context.getBeansOfType(MetricsDbBudget.class).size());
        MetricsExportService service = context.getBean(MetricsExportService.class);
        MetricsDbBudget budget = context.getBean(MetricsDbBudget.class);
        ExportCommand command = new ExportCommand("cache.size", List.of(UUID.randomUUID()),
                Instant.parse("2026-10-06T00:00:00Z"), Instant.parse("2026-10-06T00:01:00Z"), UUID.randomUUID());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, service.stream(UUID.randomUUID(), command, out));
        assertEquals("instance_id,placement_id,timestamp,value\r\n", out.toString());

        List<MetricsDbBudget.Permit> held = new ArrayList<>();
        for (int i = 0; i < budget.size(); i++) {
            held.add(budget.acquire(Duration.ofMillis(100)));
        }
        try {
            ExportBusyException busy = assertThrows(ExportBusyException.class,
                    () -> service.stream(UUID.randomUUID(), command, new ByteArrayOutputStream()));
            assertEquals(ExportBusyException.Reason.DATABASE_BUDGET, busy.reason());
        } finally {
            held.forEach(MetricsDbBudget.Permit::close);
        }
    }
}
