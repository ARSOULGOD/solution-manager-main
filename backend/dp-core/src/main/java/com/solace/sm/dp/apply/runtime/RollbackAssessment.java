package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.ErrorClass;
import java.util.Objects;

/**
 * What a <b>read-only</b> rollback assessment concluded ({@code BE-D22}).
 *
 * <p><b>No observation list, deliberately.</b> An earlier draft returned per-replica observations
 * here and described them as the evidence for {@code CONFIG_DIVERGED}; they were discarded the moment
 * the engine branched on them. Per-replica evidence is <b>rows</b> — each replica's
 * {@code MATERIALIZE} attempt, and the {@code VERIFY} attempts the engine issues after a restore —
 * exactly as §3.2's {@code CONFIG_DIVERGED} row says. A value that cannot be read back after the
 * process exits is not evidence, and giving it a field invited the claim that it was.
 *
 * <p><b>No {@code Diverged} variant, also deliberately</b> (correction A). An assessment classifies
 * each replica against exactly two <b>journal-known</b> values: the rollback target read back from
 * the retained pre-image, and that replica's own {@code MATERIALIZE} {@code config_hash}, handed in
 * by the engine. Their MIXTURE — replica A on the new generation, replica B still on the old — is the
 * ordinary partial-write case a rollback exists for, and is {@link Restorable}. Divergence is a
 * <i>lifecycle</i> verdict ({@code SM-D15}: "some restarted onto G, some still run the prior
 * generation") that the engine takes from its own journal, never from a config-hash comparison.
 */
public sealed interface RollbackAssessment {

    /**
     * Every replica holds one of the two journal-known values, and a prior config exists that hashes
     * to {@code targetHash} → the engine may open a {@code ROLLBACK} intent carrying it and call
     * {@link RuntimeAdapter#restore}.
     */
    record Restorable(String targetHash) implements RollbackAssessment {

        public Restorable {
            Objects.requireNonNull(targetHash, "target hash");
        }
    }

    /** Nothing was ever written for this generation → {@code FAILED_NO_EFFECT}. */
    record NothingToRestore() implements RollbackAssessment {
    }

    /**
     * The assessment cannot decide, or can decide but cannot be journalled →
     * {@code APPLY_INDETERMINATE}, with nothing overwritten.
     *
     * <p>Classifiers this build produces:
     * <ul>
     *   <li>{@code rollback.state.unreadable} — a replica's config could not be read.
     *   <li>{@code rollback.state.unknown} — a replica holds a value this apply never wrote, neither
     *       the rollback target nor its own {@code MATERIALIZE} hash. Somebody else did, and
     *       {@code B/D12} forbids reverting them.
     *   <li>{@code rollback.target.absent} — the prior state was the ABSENCE of a file, so there is
     *       no hash for a {@code ROLLBACK} intent's {@code config_hash} and
     *       {@code hash_on_config_steps} makes the restore unjournallable. §2.2 rule 0 forbids acting
     *       without the journal entry, so the engine stops and asks the operator.
     *   <li>{@code rollback.preimage.altered} (OR-102) — the retained pre-image no longer matches the keyed
     *       fingerprint recorded when it was retained; {@code change} says which declared non-secret paths differ.
     *   <li>{@code rollback.preimage.unrecorded} (OR-102) — there is no trustworthy fingerprint to check it against,
     *       so the copy is not used.
     * </ul>
     *
     * @param change what differs, when {@code why} is {@code rollback.preimage.altered}; otherwise {@code null}.
     *        Paths only (see {@link PreImageChange}).
     */
    record Unknowable(ErrorClass why, PreImageChange change) implements RollbackAssessment {

        public Unknowable {
            Objects.requireNonNull(why, "why");
        }

        public Unknowable(ErrorClass why) {
            this(why, null);
        }
    }
}
