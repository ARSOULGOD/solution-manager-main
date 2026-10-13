package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;

/** What {@link ApplyExecutor#execute} did. */
public sealed interface ApplyOutcome {

    /**
     * The request reached a state the engine will not move it out of.
     *
     * <p>{@code state} is terminal in every case but one: {@code OutcomeService.land} accepts
     * {@code APPLY_INDETERMINATE}, which is <b>not</b> terminal — resolution is an explicit operator
     * confirmation (§3.4). The record is named for the common case; this sentence is the exception
     * rather than a second method hiding it.
     *
     * @param errorClass the classifier that ended the apply, or {@code null} on success
     */
    record Landed(ApplyId applyId, RequestState state, ErrorClass errorClass)
            implements ApplyOutcome {
    }

    /** Admission refused it; nothing was persisted beyond admission's own refusal. */
    record NotAdmitted(RefusalReason reason, String detail) implements ApplyOutcome {
    }

    /**
     * This build cannot execute the strategy the operator chose. Refused BEFORE admission, so
     * nothing at all is persisted ({@code SM-D15}: "SM offers only strategies the solution's
     * declared capabilities support").
     */
    record StrategyUnsupported(Strategy strategy, String detail) implements ApplyOutcome {
    }

    /**
     * This build's executor drives {@code apply} and {@code stop}. A {@code restart}
     * <b>request</b> is a different slice — no generation to bind, so no {@code MATERIALIZE} step
     * and no hash for its lifecycle journal to compare against — and plan 005 does not open it.
     * Refused BEFORE admission, so nothing at all is persisted.
     *
     * <p>Not folded into {@link StrategyUnsupported}: the strategy may be perfectly supported and
     * the operation still out of scope, and a refusal that named the wrong reason would send an
     * operator to change the wrong thing.
     */
    record OperationUnsupported(Operation operation, String detail) implements ApplyOutcome {
    }

    /**
     * The scope is instance-wide and the operation is an apply: an apply names one placement
     * ({@code BE-D45}; before {@code V021} the journal could not hold per-placement steps either, {@code BE-D23}).
     * Refused BEFORE admission, so nothing at all is persisted: a request the engine does not run must not occupy
     * the scope, and a {@code FAILED_*} row would imply an attempt was made.
     */
    record ScopeUnsupported(ScopeKey scope, String detail) implements ApplyOutcome {
    }
}
