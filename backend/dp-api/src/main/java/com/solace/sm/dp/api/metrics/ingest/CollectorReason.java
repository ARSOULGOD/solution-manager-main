package com.solace.sm.dp.api.metrics.ingest;

import java.util.Locale;

/**
 * Why a sample or a batch did not become a stored point, counted per placement and day (M-Q21). The list is CLOSED:
 * it mirrors {@code sm_metrics.ingest_counter}'s reason CHECK, and a new reason is a migration and a ruling.
 *
 * <p>{@link #OUT_OF_ORDER} (OQ-12 (a)) and {@link #AMBIGUOUS_PLACEMENT} (OQ-15 (a)) were ruled at the 2026-10-05 sitting
 * ({@code OR-92}) and are written here unconditionally, so the push task does not edit this enum.
 */
public enum CollectorReason {
    SCRAPE_FAILED, PARSE_FAILED, CAP_REJECTED, UNDECLARED_DROPPED, DECREASE_WITHOUT_RESTART, RUNWAY_DEGRADED,
    DEADLINE_MISSED, BUFFER_DROPPED, OUT_OF_ORDER, AMBIGUOUS_PLACEMENT,
    /** An agent's samples refused while its measured clock offset exceeds the window (OR-130 F-6, OR-134; {@code sm_metrics} V004). */
    CLOCK_SKEW;

    /** The stored and wire spelling, {@code snake_case}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
