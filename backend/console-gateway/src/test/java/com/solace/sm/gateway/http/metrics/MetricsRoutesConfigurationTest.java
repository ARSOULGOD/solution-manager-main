package com.solace.sm.gateway.http.metrics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** W4-09: the export executor is bounded, so a burst cannot create a thread per admitted request. */
class MetricsRoutesConfigurationTest {

    @Test
    void theExportExecutorRefusesAnythingPastItsThreadBoundInsteadOfSpawningOrQueueing() throws Exception {
        ThreadPoolTaskExecutor executor = MetricsRoutesConfiguration.boundedExecutor(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(2);
        try {
            for (int i = 0; i < 2; i++) {
                assertDoesNotThrow(() -> executor.submit(() -> {
                    running.countDown();
                    release.await(30, TimeUnit.SECONDS);
                    return null;
                }));
            }
            assertTrue(running.await(10, TimeUnit.SECONDS));
            assertThrows(TaskRejectedException.class, () -> executor.submit(() -> null),
                    "a third export has no thread, and nothing is queued behind the two");
            assertEquals(2, executor.getPoolSize(), "no thread was created for the refused one");
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void theBoundIsAConfiguredPositiveNumberWithADefaultAboveTheDataPlanesInstallCap() {
        assertEquals(8, MetricsGatewayProperties.defaults().exportMaxThreads());
        assertThrows(IllegalArgumentException.class, () -> new MetricsGatewayProperties(10,
                java.time.Duration.ofMinutes(30), 0, 30));
    }
}
