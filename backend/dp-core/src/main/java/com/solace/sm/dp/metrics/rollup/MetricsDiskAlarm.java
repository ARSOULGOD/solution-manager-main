package com.solace.sm.dp.metrics.rollup;

import com.solace.sm.dp.api.metrics.health.DiskAlarmView;
import com.solace.sm.dp.api.metrics.health.DiskAlarmView.State;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The metrics disk-usage alarm (plan 011 P11-3.2; M-Q19, shaped by OQ-16 (a), {@code OR-92}). Hourly on the shared
 * {@code metricsExecutor}, never boot-fatal, it reads the database's size and {@code sm_metrics}' total size and compares
 * the larger with {@code sm.dp.metrics.disk.budget-bytes}: {@code ok} below 80 %, {@code warning} from 80 %,
 * {@code critical} from 95 %, {@code unconfigured} when no budget is set.
 *
 * <p><b>It only reports.</b> It never stops ingest and adds no ingest reason (M-Q21's list is unchanged). Only the STATE
 * leaves this class ({@link #view()}); the byte figures are install-wide totals across tenants, and the partition command
 * prints them for the operator.
 */
public final class MetricsDiskAlarm {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsDiskAlarm.class);

    static final double WARNING = 0.80;
    static final double CRITICAL = 0.95;

    private final DiskRows rows;
    private final DiskProperties properties;
    private final ScheduledExecutorService executor;
    private volatile State state;
    private ScheduledFuture<?> hourly;

    public MetricsDiskAlarm(DiskRows rows, DiskProperties properties, ScheduledExecutorService executor) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.state = properties.configured() ? null : State.UNCONFIGURED;
    }

    public synchronized void start() {
        if (hourly == null) {
            hourly = executor.scheduleWithFixedDelay(this::refresh, 0, properties.every().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void stop() {
        if (hourly != null) {
            hourly.cancel(false);
            hourly = null;
        }
    }

    /** One measurement. A failure keeps the previous state and is logged by class only; it never throws. */
    public void refresh() {
        if (!properties.configured()) {
            state = State.UNCONFIGURED;
            return;
        }
        try {
            DiskRows.Sizes sizes = rows.read();
            state = classify(Math.max(sizes.databaseBytes(), sizes.metricsBytes()), properties.budgetBytes());
        } catch (RuntimeException e) {
            LOG.warn("the metrics disk size could not be read ({}); the previous state stands", e.getClass().getSimpleName());
        }
    }

    static State classify(long usedBytes, long budgetBytes) {
        double ratio = (double) usedBytes / (double) budgetBytes;
        if (ratio >= CRITICAL) {
            return State.CRITICAL;
        }
        if (ratio >= WARNING) {
            return State.WARNING;
        }
        return State.OK;
    }

    /** The state and nothing more; empty only when a budget is set and no measurement has succeeded yet. */
    public Optional<DiskAlarmView> view() {
        State current = state;
        return current == null ? Optional.empty() : Optional.of(new DiskAlarmView(current));
    }
}
