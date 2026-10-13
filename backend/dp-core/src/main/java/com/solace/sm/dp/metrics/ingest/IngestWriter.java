package com.solace.sm.dp.metrics.ingest;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The ONE thread that writes samples (plan 011 P11-3.1, D-16): a single {@code IngestWriter} drains the buffer, so
 * ingest holds one pool connection however many collectors call it, and the per-series state in memory needs no lock.
 *
 * <p>{@link #kick()} is coalescing: any number of callers asking for a drain while one is queued or running yield one
 * more run, never a queue of them. A periodic tick retries what a failure left behind. Both run the same
 * {@code drain} on the same thread.
 */
final class IngestWriter implements AutoCloseable {

    private final ScheduledExecutorService thread;
    private final AtomicBoolean queued = new AtomicBoolean();
    private final Runnable drain;

    IngestWriter(Runnable drain, Duration tick) {
        this.drain = drain;
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread t = new Thread(runnable, "sm-ingest-writer");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        this.thread = executor;
        thread.scheduleWithFixedDelay(this::runGuarded, tick.toMillis(), tick.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Asks for a drain soon. Returns immediately; false when the writer is shut down. */
    boolean kick() {
        if (queued.compareAndSet(false, true)) {
            try {
                thread.execute(() -> {
                    queued.set(false);
                    runGuarded();
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                queued.set(false);
                return false;
            }
        }
        return true;
    }

    /** A drain must never kill the thread: a scheduled task that throws is cancelled for good. */
    private void runGuarded() {
        try {
            drain.run();
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(IngestWriter.class)
                    .warn("the ingest drain failed ({}); it is retried on the next tick", e.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        thread.shutdownNow();
    }
}
