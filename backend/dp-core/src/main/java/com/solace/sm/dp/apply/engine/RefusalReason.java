package com.solace.sm.dp.apply.engine;

/**
 * Why admission said no.
 *
 * <p>The first three, and {@link #INSTANCE_NOT_DECLARED}, are the BRACES — the checks §3.3's serialized admission transaction performs
 * while holding the scope row. {@link #FENCE_RACE_LOST} is the BELT: a partial unique index refused
 * the insert anyway. Under the row lock the belt should be unreachable through this service, and
 * keeping the two reasons distinct is what makes that assertable rather than assumed.
 */
public enum RefusalReason {

    /** An apply or restart is in flight (or unresolved) on an overlapping scope — design §3.3. */
    SCOPE_BUSY,
    /** A stop is already in flight on this exact scope. */
    STOP_IN_FLIGHT,
    /** §2.2 rule 1: the generation is at or below the fence for this scope shape. */
    GENERATION_NOT_ABOVE_WATERMARK,
    /** One of the three named fences refused the insert; {@code detail} carries the index name. */
    FENCE_RACE_LOST,
    /**
     * The instance is not one the data plane has declared and holds {@code active} (plan 010 T2.2): never
     * declared, still {@code pending}, or {@code abandoned}. Checked before any row is written, and again under
     * the scope lock.
     */
    INSTANCE_NOT_DECLARED,
    /**
     * The generation is not in the config of record for this instance (plan 010 T6.3): never staged, or retired.
     * Checked under the {@code instance_state} row lock, so a concurrent stage's retention cannot delete it between
     * the check and the insert that then protects it.
     */
    GENERATION_NOT_STAGED
}
