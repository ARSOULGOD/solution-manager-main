package com.solace.sm.dp.apply.persistence;

import java.time.LocalDate;
import java.util.Map;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The runtime side of the partition runway (plan 011 P11-2.1; M-Q13 c', {@code OR-77}): calls the owner-owned
 * {@code sm_metrics.ensure_metric_partitions()} and nothing else. It builds no DDL and passes the function no
 * argument, so there is no text of this class's to inject into ({@code I5}: the first sanctioned case lives inside
 * the function, behind a closed name grammar).
 *
 * <p>Destructive partition work (freeze, detach, archive, drop) is {@link MetricsPartitionMain}, an operator command
 * with the owner credential. The runtime role cannot do any of it.
 *
 * <p>Its public surface returns a date and nothing else, on purpose: byte figures and budgets are install-wide
 * totals across tenants and reach only the operator's console (OQ-16).
 */
public class JdbcPartitionRunwayRepository implements com.solace.sm.dp.metrics.rollup.PartitionRunway.Source {

    /** What {@link #classify} can tell about a refused insert into {@code metrics_raw}. */
    public enum Refusal {
        /** No partition accepts the row's timestamp: the runway has run out (counted {@code runway_degraded}). */
        NO_PARTITION,
        /** A table CHECK refused the row, for example {@code raw_value_finite}: a bad value, not a missing month. */
        CHECK,
        /** Anything else. */
        OTHER
    }

    private static final String CHECK_VIOLATION = "23514";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPartitionRunwayRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates any of this month and the three after it that is missing, and returns the first day of the first month
     * with no partition (the runway's end). A held lock is retried inside the function, then raised as
     * {@code lock_not_available} (55P03) for the caller to count; it never leaves a half-created partition.
     */
    @Override
    public LocalDate ensure() {
        return jdbc.queryForObject("SELECT sm_metrics.ensure_metric_partitions()", Map.of(), LocalDate.class);
    }

    /**
     * Tells a routing failure from a CHECK failure. Both are {@code 23514} (MF23); only a CHECK carries a constraint
     * name. Reads the name, never the message, which is localised and echoes the row.
     */
    static Refusal classify(PSQLException e) {
        if (!CHECK_VIOLATION.equals(e.getSQLState())) {
            return Refusal.OTHER;
        }
        boolean named = e.getServerErrorMessage() != null && e.getServerErrorMessage().getConstraint() != null;
        return named ? Refusal.CHECK : Refusal.NO_PARTITION;
    }
}
