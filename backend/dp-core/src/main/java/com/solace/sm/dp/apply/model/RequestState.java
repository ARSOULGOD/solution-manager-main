package com.solace.sm.dp.apply.model;

/**
 * The twelve states of design §3.2. Spelt identically in Java and SQL, so there is no {@code sql()}
 * here — a second spelling would be a second place to get {@code APPLY_INDETERMINATE} wrong.
 *
 * <p>The three predicates below mirror DDL, and each is load-bearing rather than convenient:
 * {@link #isTerminal()} is the complement of {@code terminal_has_timestamp}'s NULL side,
 * {@link #blocksScope()} is the predicate of {@code apply_inflight_fence}, and
 * {@link #validFor(Operation)} is {@code state_fits_operation}. Keeping them here means the engine and
 * the database disagree in exactly one place, which a test can then watch.
 */
public enum RequestState {

    REQUESTED,
    RUNNING,
    STAGED,
    APPLIED,
    FAILED_NO_EFFECT,
    FAILED_ROLLED_BACK,
    CONFIG_DIVERGED,
    SUPERSEDED,
    APPLY_INDETERMINATE,
    INDETERMINATE_RESOLVED,
    STOPPED,
    STOP_FAILED;

    /** Mirrors {@code terminal_has_timestamp}: everything but the three open states. */
    public boolean isTerminal() {
        return !(this == REQUESTED || this == RUNNING || this == APPLY_INDETERMINATE);
    }

    /**
     * Mirrors {@code apply_inflight_fence}'s state predicate. {@code APPLY_INDETERMINATE} is in the
     * set on purpose — that is how architecture.md §16.1's "blocking further applies until an
     * operator confirms" is implemented.
     */
    public boolean blocksScope() {
        return this == REQUESTED || this == RUNNING || this == APPLY_INDETERMINATE;
    }

    /**
     * Mirrors {@code state_fits_operation}, clause for clause.
     *
     * <p>The CHECK reads:
     * <pre>{@code
     * CASE operation
     *   WHEN 'stop'    THEN state IN ('REQUESTED','RUNNING','STOPPED','STOP_FAILED')
     *   WHEN 'restart' THEN state NOT IN ('STAGED','STOPPED','STOP_FAILED')
     *   ELSE                state NOT IN ('STOPPED','STOP_FAILED') END
     * }</pre>
     *
     * <p>Two enforcers, the standing pattern of this design: the CHECK stays the last line and makes
     * the wrong pairing unrepresentable; this predicate lets {@code OutcomeService.land} refuse it
     * BEFORE any write, with a sentence naming the rule, so an engine bug is a readable error rather
     * than a 23514 out of a repository. A change here without a migration is a divergence
     * {@code StateFitsOperationMirrorTest} compares against the shipped {@code V002} bytes to catch —
     * {@code RequestStateTest} runs the same comparison but against a hand transcription of the CHECK,
     * so {@code StateFitsOperationMirrorTest} is the stronger lock.
     *
     * @param op the request's operation
     * @return whether this state is one that operation may hold
     */
    public boolean validFor(Operation op) {
        switch (op) {
            case STOP:
                return this == REQUESTED || this == RUNNING || this == STOPPED
                        || this == STOP_FAILED;
            case RESTART:
                return !(this == STAGED || this == STOPPED || this == STOP_FAILED);
            case APPLY:
                return !(this == STOPPED || this == STOP_FAILED);
            default:
                throw new IllegalArgumentException("not an operation this schema can hold: " + op);
        }
    }
}
