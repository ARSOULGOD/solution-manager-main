package com.solace.sm.dp.apply.model;

import java.util.Objects;

/**
 * One replica's line of the evidence an operator resolves an {@code APPLY_INDETERMINATE} with —
 * design §3.1's "replica-ELEMENT typing is the engine's typed resolver (own refusal test)".
 *
 * <p>The database's {@code resolution_is_evidence} CHECK proves the SHAPE around these elements, for an
 * {@code apply} resolution ({@code BE-D17}, §3.4): an object, {@code stagedGeneration} EQUAL to the
 * request's own {@code generation}, a NON-EMPTY ARRAY {@code replicas}. It deliberately does not type
 * the elements — at the PG 14 floor a per-element JSONB shape check is not expressible. This record is
 * the typed resolver the design promises in its place, so the CHECK is the last line rather than the
 * only one. (A {@code restart} resolution's elements are {@link RestartObservation}, a distinct,
 * smaller record — no generation is in play there, so there is no hash to read back either.)
 *
 * @param ref          which replica was observed
 * @param restarted    whether it was seen to have restarted onto the staged generation
 * @param observedHash the read-back hash, or null where the adapter could not read one
 */
public record ReplicaObservation(ReplicaRef ref, boolean restarted, String observedHash) {

    public ReplicaObservation {
        Objects.requireNonNull(ref, "replica ref");
        if (observedHash != null) {
            ValueGrammar.require(ValueGrammar.HASH, observedHash, "an observed hash",
                    "<algorithm>:<hex>, read back from the replica — evidence, never intent");
        }
    }
}
