package com.solace.sm.dp.apply.persistence;

/**
 * The constraint and index NAMES the engine branches on — one place, so a migration rename breaks
 * compilation rather than behaviour.
 *
 * <p>Two of these are not in the design text at all: PostgreSQL derived them from table and column
 * names for the design's inline {@code UNIQUE (…)} clauses. {@code RefusalShapesIT} pins the full
 * generated set; these are the subset control flow depends on.
 *
 * <p>Public because {@code ..apply.engine} classifies refusals by name (§2.2 rule 2) and must not
 * hold string literals of its own. They are names, not JDBC types, so exporting them costs the
 * persistence seam nothing.
 */
public final class Constraints {

    /** Design-named partial unique index: at most one in-flight apply/restart per scope. */
    public static final String APPLY_INFLIGHT_FENCE = "apply_inflight_fence";
    /** Design-named partial unique index: at most one in-flight stop per scope. */
    public static final String STOP_INFLIGHT_FENCE = "stop_inflight_fence";
    /** Design-named partial unique index: at most one STAGED row per scope. */
    public static final String STAGED_FENCE = "staged_fence";

    /**
     * The composite intent UNIQUE — PostgreSQL-generated from {@code UNIQUE (tenant_id, apply_id,
     * step, replica_ref)}. A 23505 naming it or {@link #INTENT_IDEMPOTENCY_KEY_UNIQUE} sends the
     * engine into a fresh-transaction re-read (§2.2 rule 2 as amended); the re-read's outcome, not
     * the name, decides between "race lost" and "protocol error".
     */
    public static final String INTENT_STEP_REPLICA_UNIQUE =
            "apply_step_intent_tenant_id_apply_id_step_replica_ref_key";

    /**
     * The idempotency key's own UNIQUE — PostgreSQL-generated from the inline {@code NOT NULL UNIQUE}.
     * Measured: a legitimate concurrent same-step create violates BOTH intent UNIQUEs and PostgreSQL
     * names THIS one first, so it is not by itself the protocol error — see
     * {@code StepIntentService}.
     */
    public static final String INTENT_IDEMPOTENCY_KEY_UNIQUE = "apply_step_intent_idempotency_key_key";

    /**
     * {@code UNIQUE (intent_id, attempt_no)} — PostgreSQL-generated. Two concurrent {@code open()}s
     * for one intent both read the same count at READ COMMITTED; this index is what makes exactly one
     * of them the attempt it claims to be (measured), and the engine classifies the loser by this name.
     */
    public static final String ATTEMPT_NO_UNIQUE = "apply_attempt_intent_id_attempt_no_key";

    /**
     * {@code attempt_open_fence} — design-named partial unique index: at most one OPEN attempt
     * (({@code outcome IS NULL})) per intent, regardless of {@code attempt_no} ({@code BE-D20}).
     * Distinct from, and alongside, {@link #ATTEMPT_NO_UNIQUE}: that one fires only on a same-number
     * collision, this one fires whenever the intent already has an open row, however the new
     * insert's own number was computed.
     */
    public static final String ATTEMPT_OPEN_FENCE = "attempt_open_fence";

    private Constraints() {
    }
}
