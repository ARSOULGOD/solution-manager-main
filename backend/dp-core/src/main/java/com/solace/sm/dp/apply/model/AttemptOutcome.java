package com.solace.sm.dp.apply.model;

/**
 * {@code apply_attempt.outcome} — spelt identically in Java and SQL ({@code outcome_known}), so no
 * {@code sql()} mapping exists to drift.
 */
public enum AttemptOutcome {

    SUCCEEDED,
    FAILED,
    TIMED_OUT
}
