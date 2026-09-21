package com.solace.sm.dp.apply.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@code apply_step_intent} row — §2.2 rule 2's immutable identity substrate: exactly one per
 * logical {@code (apply, step, replica)}, holding the DP-assigned key and the INTENDED hash.
 *
 * <p>No validation of the two step/shape CHECKs happens here on purpose. {@code ApplyCommand} mirrors
 * its CHECKs because a malformed command is an operator-facing error; an intent is minted by the
 * engine from a {@link Step} and a {@link ReplicaRef} it already chose, so mirroring
 * {@code hash_on_config_steps} in Java would hide the schema half that {@code StepIntentIT} exists to
 * prove fires.
 */
public record StepIntentRow(UUID intentId, ApplyId applyId, Step step, ReplicaRef replica,
        String idempotencyKey, String configHash, Instant createdAt) {

    public StepIntentRow {
        Objects.requireNonNull(intentId, "intent id");
        Objects.requireNonNull(applyId, "apply id");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(replica, "replica");
        Objects.requireNonNull(idempotencyKey, "idempotency key");
        Objects.requireNonNull(createdAt, "created at");
    }
}
