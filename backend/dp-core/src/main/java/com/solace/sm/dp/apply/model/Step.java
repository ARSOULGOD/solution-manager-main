package com.solace.sm.dp.apply.model;

/**
 * {@code apply_step_intent.step} — the EXECUTION vocabulary, internal to {@code sm_data}. Design §3.5
 * is explicit that this is not the wire vocabulary: {@code instance.rollout.payload.step} is the
 * contract's {@code [staged, restart-requested, restart-observed]} and the DP owns the mapping.
 *
 * <p>The two predicates mirror the two CHECKs that constrain an intent row, in both directions —
 * {@code hash_on_config_steps} and {@code placement_scoped_steps} are equalities, not implications,
 * so a hash on a hashless step is refused just as loudly as a missing one.
 */
public enum Step {

    PREFLIGHT,
    MATERIALIZE,
    LIFECYCLE,
    VERIFY,
    ROLLBACK,
    ROLLBACK_ASSESS,
    STOP;

    /** Mirrors {@code hash_on_config_steps}. */
    public boolean carriesHash() {
        return this == MATERIALIZE || this == VERIFY || this == ROLLBACK;
    }

    /** Mirrors {@code placement_scoped_steps}: these steps address {@code @placement}, not a replica. */
    public boolean placementScoped() {
        return this == PREFLIGHT || this == ROLLBACK || this == ROLLBACK_ASSESS;
    }
}
