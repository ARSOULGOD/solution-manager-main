package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;

/**
 * What {@link ApplyExecutor#admit} decided — Task 4's split of plan 005's {@code execute()} into
 * admission and execution. A sum type, <b>not a bare {@link ApplyId}</b>: measured, plan 005's
 * {@code execute()} returns five non-admitted outcomes at or before admission —
 * {@link OperationUnsupported} (restart), {@link StrategyUnsupported}, {@link ScopeUnsupported}
 * (instance-wide apply) and {@link NotAdmitted} from {@link AdmissionService}'s own refusal. A method
 * returning {@code ApplyId} can express none of them.
 *
 * <p><b>{@code Admitted} means the {@code REQUESTED} row is durably committed — nothing more.</b>
 * {@code admit()} never calls the adapter and never transitions the row to {@code RUNNING}: that
 * transition is Task 4a's claim, the durable worker's job, not admission's.
 */
public sealed interface AdmissionResult {

    /** The request row is committed in {@code REQUESTED}; the durable worker may now claim it. */
    record Admitted(ApplyId id) implements AdmissionResult {
    }

    /**
     * Plan 010 T11.2 ({@code fleet-apply.md} section 4.2): a request ALREADY exists under the id the caller supplied, so
     * nothing was written. <b>Never folded into {@link Admitted}</b>: {@code execute()} runs whatever {@code Admitted}
     * names, so folding would let it start an existing request a second time.
     */
    record AlreadyAdmitted(ApplyId id) implements AdmissionResult {
    }

    /**
     * This build's executor drives {@code apply} and {@code stop}. A {@code restart} request is a
     * different slice — no generation to bind — and plan 005 does not open it. Refused BEFORE
     * admission, so nothing at all is persisted.
     */
    record OperationUnsupported(Operation op) implements AdmissionResult {
    }

    /**
     * This build cannot execute the strategy the operator chose ({@code SM-D15}). Refused BEFORE
     * admission, so nothing at all is persisted.
     */
    record StrategyUnsupported(Strategy s) implements AdmissionResult {
    }

    /**
     * The scope is instance-wide and the operation is an apply: an apply names one placement ({@code BE-D45}).
     * Refused BEFORE admission.
     */
    record ScopeUnsupported(ScopeKey scope) implements AdmissionResult {
    }

    /**
     * {@link AdmissionService#admit}'s own refusal — an operator-facing answer such as "something
     * else is running on this scope". {@code reason} stays the engine's own {@link RefusalReason}
     * rather than a bare {@code String}: this type is dp-core-internal (no dp-api/HTTP consumer
     * exists yet), so nothing outside the engine depends on its shape, and a typed reason is exactly
     * what a future controller needs to map onto an HTTP status without re-parsing text.
     */
    record NotAdmitted(RefusalReason reason, String detail) implements AdmissionResult {
    }
}
