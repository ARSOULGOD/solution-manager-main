package com.solace.sm.dp.api.metrics.query;

import com.solace.sm.dp.api.metrics.health.DiskAlarmView;
import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The collector block of {@code capabilities} (M-Q21, M-Q13, OQ-16 (a), OQ-10 (a)).
 *
 * <p><b>Scope of each field (I5).</b> {@code reasons} are the CALLER's organization only. {@code state},
 * {@code runwayEnd}, {@code unfrozenClosedPartitions} and {@code disk} are install-level health. No byte figure is a
 * component, and none may become one: the sizes are install-wide totals across tenants.
 *
 * @param runwayEnd  the first day with no partition, as of the last successful keep; null before one
 * @param disk       the disk alarm's state; null when a budget is set and no measurement has succeeded yet
 */
public record CollectorHealthView(State state, LocalDate runwayEnd, int unfrozenClosedPartitions, List<ReasonCount> reasons,
        DiskAlarmView disk) {

    public CollectorHealthView {
        Objects.requireNonNull(state, "state");
        reasons = List.copyOf(reasons);
    }

    /** {@code degraded} when less than a month of partition runway is left or the disk is critical. */
    public enum State { LIVE, DEGRADED }

    /** One reason counted over the last 24 hours (the UTC days that overlap them: the counter is per day). */
    public record ReasonCount(CollectorReason reason, long last24h) {
    }
}
