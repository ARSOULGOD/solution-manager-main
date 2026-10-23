package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.logs.LogExportStream;
import com.solace.sm.gateway.http.metrics.MetricsRejectionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Plan 016 task 10 re-review N-3: the log export's own executor refuses with the log-specific 429 and leaves no header behind. */
class LogExportExecutionTest {

    @Test
    void anExecutorThatIsFullRefusesWithTheLogSpecificReasonNotTheMetricsOne() throws Exception {
        LogExportExecution execution = new LogExportExecution(1, 30_000L);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        try {
            execution.executor().submit(() -> {
                running.countDown();
                release.await(10, TimeUnit.SECONDS);
                return null;
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            execution.executor().submit(() -> null);        // the one queued slot

            MetricsRejectionException refused = assertThrows(MetricsRejectionException.class,
                    () -> execution.executor().submit(() -> null));

            assertEquals(MetricsRejectionException.LOG_EXPORT_CONCURRENCY, refused.reason());
            assertEquals(ErrorCode.RATE_LIMITED, refused.code());
            assertEquals(5, refused.retryAfterSeconds());
        } finally {
            release.countDown();
            execution.destroy();
        }
    }

    @Test
    void buildingTheDownloadTouchesNoHeaderSoARefusedTaskLeavesNoStrayDisposition() {
        LogExportExecution execution = new LogExportExecution(1, 30_000L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        LogExportStream stream = new LogExportStream() {
            @Override public String next() { return null; }
            @Override public void close() { }
        };

        LogExportController.download(stream, response, "logs-x.log", execution, () -> { });

        assertNull(response.getHeader("Content-Disposition"));
        assertNull(response.getHeader("Cache-Control"));
        execution.destroy();
    }

    @Test
    void thePermitComesBackWhenThePumpFinishesNotBeforeAndOnlyOnce() {
        LogExportExecution execution = new LogExportExecution(2, 30_000L);
        assertTrue(execution.tryAcquire());
        assertTrue(execution.tryAcquire());
        Runnable releaser = execution.releaser();
        assertTrue(!execution.tryAcquire());

        releaser.run();
        releaser.run();

        assertTrue(execution.tryAcquire(), "one permit came back");
        assertTrue(!execution.tryAcquire(), "and only one, however often the releaser ran");
        execution.destroy();
    }
}
