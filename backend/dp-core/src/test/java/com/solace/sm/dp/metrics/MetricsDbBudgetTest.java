package com.solace.sm.dp.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.metrics.ingest.IngestProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Plan 011 P11-3.1: the one shared query/export connection budget (M-Q22 (6), M-Q19). */
class MetricsDbBudgetTest {

    @Test
    void theBudgetDefaultsToFourAndIsConfigurable() {
        new ApplicationContextRunner().withUserConfiguration(MetricsConfiguration.class).run(context -> {
            assertEquals(4, context.getBean(MetricsDbBudget.class).size());
        });
        new ApplicationContextRunner().withUserConfiguration(MetricsConfiguration.class)
                .withPropertyValues("sm.dp.metrics.db-budget=6").run(context -> {
                    assertEquals(6, context.getBean(MetricsDbBudget.class).size());
                });
    }

    @Test
    void aFifthHolderWaitsThenTimesOut() {
        MetricsDbBudget budget = new MetricsDbBudget(4);
        List<MetricsDbBudget.Permit> held = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            held.add(budget.acquire(Duration.ofMillis(50)));
        }
        long t0 = System.nanoTime();
        assertThrows(MetricsBusyException.class, () -> budget.acquire(Duration.ofMillis(200)));
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        assertTrue(ms >= 180, "it waited its bound before giving up: " + ms + " ms");
        held.get(0).close();
        try (MetricsDbBudget.Permit again = budget.acquire(Duration.ofMillis(200))) {
            assertEquals(0, budget.available());
        }
        held.forEach(MetricsDbBudget.Permit::close);
        assertEquals(4, budget.available());
    }

    @Test
    void aPermitIsReleasedOnException() {
        MetricsDbBudget budget = new MetricsDbBudget(1);
        assertThrows(IllegalStateException.class, () -> {
            try (MetricsDbBudget.Permit permit = budget.acquire(Duration.ofMillis(50))) {
                throw new IllegalStateException("the query failed");
            }
        });
        assertEquals(1, budget.available(), "the permit came back through close()");
    }

    @Test
    void closingAPermitTwiceReleasesOnce() {
        MetricsDbBudget budget = new MetricsDbBudget(2);
        MetricsDbBudget.Permit p = budget.acquire(Duration.ofMillis(50));
        p.close();
        p.close();
        assertEquals(2, budget.available(), "a double close must not mint a permit");
    }

    @Test
    void exactlyOneBudgetBeanExists() {
        new ApplicationContextRunner().withUserConfiguration(MetricsConfiguration.class).run(context -> {
            assertEquals(1, context.getBeansOfType(MetricsDbBudget.class).size());
            assertEquals(1, context.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class).size(),
                    "one shared metricsExecutor beside it");
        });
    }

    @Test
    void aBudgetBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new MetricsDbBudget(0));
    }

    @Test
    void theIngestDefaultsAreTheRuledOnes() {
        IngestProperties p = IngestProperties.defaults();
        assertEquals(20, p.bufferTicks());
        assertEquals(500, p.maxSeriesPerInstance());
        assertEquals(30, p.defaultIntervalSeconds());
    }
}
