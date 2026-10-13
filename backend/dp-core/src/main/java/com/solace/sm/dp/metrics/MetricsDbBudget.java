package com.solace.sm.dp.metrics;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The ONE bounded query/export connection budget (plan 011 M-Q22 (6), M-Q19), so a flood of chart reads or exports
 * cannot starve apply, health or ingest of the shared {@code sm-data} pool. A fair {@link Semaphore} with
 * {@code sm.dp.metrics.db-budget} permits (default 4). It is a count on the one pool, not a second pool: a second pool
 * would be a second credential ({@code PlaneBoundaryTest}'s reason). Ingest and health never take a permit.
 *
 * <p>Registered once, in {@link MetricsConfiguration}; the query task and the export task each INJECT it and neither
 * builds its own, which is what makes "4" mean four across both.
 */
public final class MetricsDbBudget {

    /** A held permit. Closing it twice releases once. */
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    private final Semaphore permits;
    private final int size;

    public MetricsDbBudget(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.db-budget must be at least 1, was " + size);
        }
        this.size = size;
        this.permits = new Semaphore(size, true);
    }

    /** Waits up to {@code wait} for a permit; a spent budget is {@link MetricsBusyException}. */
    public Permit acquire(Duration wait) {
        try {
            if (!permits.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new MetricsBusyException();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetricsBusyException();
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        };
    }

    public int size() {
        return size;
    }

    public int available() {
        return permits.availablePermits();
    }
}
