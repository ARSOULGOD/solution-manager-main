package com.solace.sm.dp.apply.engine;

/**
 * Whether admission must prove that the generation an apply names is a generation of the config of record
 * (plan 010 T6.3, the T5.2 note).
 *
 * <p>{@link #MUST_BE_STAGED} is what every API submission passes: the check runs UNDER the {@code instance_state} row
 * lock, taken after the {@code apply_scope} lock (the plan's lock order), because that is the lock retention holds when
 * it deletes a generation. The {@code apply_request} insert that follows is what then protects the generation from
 * retention, so the check and the insert cannot straddle a delete.
 *
 * <p>{@link #UNCHECKED} is plan 005's engine behaviour, kept for the engine's own suites, which drive the journal with
 * generations no config of record ever staged. No production caller passes it.
 */
public enum GenerationBinding {

    MUST_BE_STAGED,
    UNCHECKED
}
