package com.solace.sm.dp.apply.model;

import java.util.List;

/**
 * {@code apply_request.resolution_observed} for an {@code operation = 'apply'} resolution —
 * {@code BE-D17}'s {@code apply} shape (design §3.4).
 *
 * <p>The database's {@code resolution_is_evidence} CHECK proves, for this operation, that
 * {@code stagedGeneration} equals the request's own {@code generation} column (a same-row comparison)
 * and that {@code replicas} is a non-empty array. This record proves the same two things in Java,
 * first — except the generation *equality*, which needs the request row's own {@code generation} and so
 * can only be checked where that row is in hand ({@code OutcomeService.resolveIndeterminate}, before any
 * write) — plus the one thing the CHECK cannot: that the elements are replica observations and that the
 * JSON has no other keys at all.
 *
 * @param stagedGeneration the generation the operator is confirming; positive, like every generation.
 *                          Checked for equality against {@code apply_request.generation} by
 *                          {@code OutcomeService.resolveIndeterminate}, not by this constructor, which
 *                          has no request row to compare against
 * @param replicas          at least one observation — an empty list is the "unsupported claim"
 */
public record ApplyResolutionEvidence(long stagedGeneration, List<ReplicaObservation> replicas)
        implements ResolutionEvidence {

    public ApplyResolutionEvidence {
        if (stagedGeneration <= 0) {
            throw new IllegalArgumentException(
                    "resolution evidence names a positive stagedGeneration, was " + stagedGeneration);
        }
        if (replicas == null || replicas.isEmpty()) {
            throw new IllegalArgumentException("resolution evidence names at least one replica "
                    + "observation — resolution_is_evidence refuses an empty array, and an operator "
                    + "confirming from nothing is the case the state exists to prevent");
        }
        replicas = List.copyOf(replicas);
    }
}
