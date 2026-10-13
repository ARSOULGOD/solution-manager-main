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
 *
 * <p>{@code placement} is the placement the intent addresses ({@code BE-D44}, {@code V021}): for a placement-scoped
 * request the request's own, for an instance-wide stop the placement of the replica being stopped. It is NOT validated
 * here either: the nil sentinel and a placement of another instance are refused by {@code V021}'s trigger, and a test
 * must be able to hand the database exactly that to see it refuse. A terminal instance-wide stop that ran before V021
 * reads back with the nil value ({@link PlacementId#isInstanceWide()}): history, never an insert.
 */
public record StepIntentRow(UUID intentId, ApplyId applyId, PlacementId placement, Step step, ReplicaRef replica,
        String idempotencyKey, String configHash, Instant createdAt) {

    public StepIntentRow {
        Objects.requireNonNull(intentId, "intent id");
        Objects.requireNonNull(applyId, "apply id");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(replica, "replica");
        Objects.requireNonNull(idempotencyKey, "idempotency key");
        Objects.requireNonNull(createdAt, "created at");
    }
}
