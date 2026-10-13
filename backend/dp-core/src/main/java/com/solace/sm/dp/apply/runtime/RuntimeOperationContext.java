package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.Step;
import java.util.Objects;
import java.util.UUID;

/**
 * The apply journal's identity of one runtime action, handed to an adapter that has to correlate its own durable record with
 * the journal (plan 015 T3.1; the agent path's command identity, {@code BE-D66}).
 *
 * <p>{@code intentKey} is the intent's attempt-free {@code idempotency_key} ({@code applyId:step:replica}, {@code ApplyExecutor
 * #idempotencyKey}): it is the <b>identity</b> an adapter may key durable work on, because a resumed apply recomputes the same
 * one. {@code attemptId} rides as correlation only, never in a key (P15-3): a new attempt of the same intent is the same
 * command.
 *
 * <p>Additive: every {@link RuntimeAdapter} overload that takes this record defaults to the one that does not, so an adapter
 * with no durable record of its own (the SSH and Docker adapters, the in-memory fake) is untouched.
 */
public record RuntimeOperationContext(ApplyId applyId, UUID intentId, String intentKey, UUID attemptId, Step step) {

    public RuntimeOperationContext {
        Objects.requireNonNull(applyId, "apply id");
        Objects.requireNonNull(intentId, "intent id");
        Objects.requireNonNull(intentKey, "intent key");
        Objects.requireNonNull(attemptId, "attempt id");
        Objects.requireNonNull(step, "step");
        if (intentKey.isBlank()) {
            throw new IllegalArgumentException("an intent key is required");
        }
    }
}
