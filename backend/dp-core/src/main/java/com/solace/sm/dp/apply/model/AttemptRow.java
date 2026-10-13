package com.solace.sm.dp.apply.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@code apply_attempt} row — one TRY of one intent. §2.2 rule 0's write-before-act artifact: the
 * row is committed before the adapter is invoked, which is what makes "an intent with no attempt row
 * provably issued nothing" a fact rather than a hope.
 *
 * <p>The three columns design §3.1's residual table names under {@code I6} —
 * {@code pre_baseline}, {@code error_class}, {@code runtime_ref} — are TYPED here rather than carried
 * as free-form text and jsonb: {@link PreBaseline}, {@link ErrorClass} and {@link RuntimeRef} each
 * refuse a secret in their constructor, before any SQL is built. {@code observedHash} stays a
 * {@link String} because it is one, but it is checked against the same
 * {@code <algorithm>:<hex>} grammar {@link ReplicaObservation} uses.
 *
 * @param attemptNo    1-based ({@code attempt_no_positive}); the configured cap is the engine's
 * @param preBaseline  process/container start baseline, LIFECYCLE only
 *                     ({@code baseline_only_for_lifecycle}); process metadata, never config
 * @param stepDeadline the bounded budget of §2.2 rule 4
 * @param outcome      null while the attempt is open
 * @param observedHash read-back EVIDENCE, never intent
 * @param errorClass   a CLASSIFIED code, never raw error text ({@code I6})
 * @param runtimeRef   adapter-scoped identifiers (design §2.3), never configuration
 */
public record AttemptRow(UUID attemptId, UUID intentId, Step step, short attemptNo,
        PreBaseline preBaseline, Instant startedAt, Instant endedAt, Instant stepDeadline,
        AttemptOutcome outcome, String observedHash, ErrorClass errorClass, RuntimeRef runtimeRef) {

    public AttemptRow {
        Objects.requireNonNull(attemptId, "attempt id");
        Objects.requireNonNull(intentId, "intent id");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(startedAt, "started at");
        Objects.requireNonNull(stepDeadline, "step deadline");
        if (observedHash != null) {
            ValueGrammar.require(ValueGrammar.HASH, observedHash, "an observed hash",
                    "<algorithm>:<hex>, read back from the replica — evidence, never intent");
        }
    }
}
