package com.solace.sm.gateway.http;

import java.time.Duration;
import java.util.Objects;
import com.solace.sm.gateway.http.metrics.MetricsRejectionException;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * The log export's OWN threads and timeout (plan 016 task 10, review I-4): a download runs for minutes, so it must not share the
 * metrics export's executor, where a saturated metrics pool would refuse it with metrics wording and a log download would hold
 * a metrics thread.
 *
 * <ul>
 *   <li>{@code sm.gateway.logs.export-max-threads} (default 4): downloads running at once on this gateway. A permit is taken
 *       BEFORE the data plane is asked ({@link #tryAcquire}), so a full pool is a declared {@code 429 log-export-concurrency}
 *       before a byte. The pool has as many threads as permits plus an equal queue, which only absorbs the instant between a
 *       download releasing its permit and its thread returning to the pool: a permit holder is never rejected by the executor.</li>
 *   <li>The async timeout is {@code sm.dp.logs.export-deadline} (default 5m, the same property the data plane ends the export
 *       by) plus one minute, so the data plane's own marker always comes first; the timeout is only a backstop.</li>
 * </ul>
 */
@Component
final class LogExportExecution implements DisposableBean {

    private static final Duration BACKSTOP = Duration.ofMinutes(1);

    private final Semaphore permits;
    private final ThreadPoolTaskExecutor executor;
    private final long timeoutMillis;
    private final AsyncTaskExecutor refusing;

    @org.springframework.beans.factory.annotation.Autowired
    LogExportExecution(@Value("${sm.gateway.logs.export-max-threads:4}") int threads,
            @Value("${sm.dp.logs.export-deadline:5m}") Duration deadline) {
        this(threads, Objects.requireNonNull(deadline, "deadline").plus(BACKSTOP).toMillis());
    }

    /** @param timeoutMillis the async backstop, explicitly (tests) */
    LogExportExecution(int threads, long timeoutMillis) {
        if (threads < 1) {
            throw new IllegalArgumentException("at least one log export thread is required");
        }
        this.timeoutMillis = timeoutMillis;
        this.permits = new Semaphore(threads);
        this.executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("sm-log-export-");
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(threads);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(30);
        executor.initialize();
        this.refusing = new AsyncTaskExecutor() {
            @Override
            public void execute(Runnable task) {
                try {
                    executor.execute(task);
                } catch (TaskRejectedException rejected) {
                    throw busy();
                }
            }

            @Override
            public Future<?> submit(Runnable task) {
                try {
                    return executor.submit(task);
                } catch (TaskRejectedException rejected) {
                    throw busy();
                }
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                try {
                    return executor.submit(task);
                } catch (TaskRejectedException rejected) {
                    throw busy();
                }
            }
        };
    }

    private static MetricsRejectionException busy() {
        return new MetricsRejectionException(ErrorCode.RATE_LIMITED, MetricsRejectionException.LOG_EXPORT_CONCURRENCY, 5);
    }

    /** True when a download may start; the caller then owns one permit and must hand it back with {@link #release}. */
    boolean tryAcquire() {
        return permits.tryAcquire();
    }

    void release() {
        permits.release();
    }

    /** A permit that can be handed back from more than one path (completion, timeout, error) and counts once. */
    Runnable releaser() {
        AtomicBoolean done = new AtomicBoolean();
        return () -> {
            if (done.compareAndSet(false, true)) {
                permits.release();
            }
        };
    }

    /** The executor the download runs on; a refused submit is the log-specific {@code 429}, never the metrics advice's. */
    AsyncTaskExecutor executor() {
        return refusing;
    }

    long timeoutMillis() {
        return timeoutMillis;
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
