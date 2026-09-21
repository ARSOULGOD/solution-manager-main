package com.solace.sm.dp.apply.engine;

/**
 * What resume does with one journalled step — design §2.2 rule 3's decision table, as four answers.
 *
 * <p>The distinction that carries the weight is {@link #REISSUE} against {@link #INDETERMINATE}:
 * one says "this action is safe to perform again under the same key", the other says "we do not
 * know what happened and guessing would overwrite somebody". {@code B/D12} 🔒 — never silently
 * auto-revert a human — is the whole reason the second exists.
 */
public enum StepDecision {

    /** The step provably issued nothing, or cannot mutate anything. Run it from the top. */
    RERUN,

    /** The step's effect is observable on the runtime. Close it out and move on. */
    CONTINUE,

    /** The effect is absent and the action is idempotent under the same key. Do it again. */
    REISSUE,

    /** The observation is a value this apply did not write, or cannot be read. Report; never act. */
    INDETERMINATE
}
