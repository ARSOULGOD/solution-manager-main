package com.solace.sm.dp.apply.model;

import java.util.List;

/**
 * {@code apply_request.resolution_observed} for an {@code operation = 'restart'} resolution —
 * {@code BE-D17}'s {@code restart} shape (design §3.4).
 *
 * <p>Carries <b>no {@code stagedGeneration}</b> — a restart request has no {@code generation}
 * ({@code apply_has_generation}), so there is nothing for one to certify, and this record has no
 * component that could hold one. That is the type-level half of rejection class (iv) ("an invented
 * restart staged generation"): a caller cannot construct evidence with a {@code stagedGeneration} for a
 * restart resolution, because there is no way to spell it in Java. The database's own CHECK proves the
 * same thing independently, at the raw-SQL layer callers going around this type could still reach.
 *
 * @param restartsObserved at least one observation — an empty list is the "unsupported claim", exactly
 *                          as it is for an {@code apply} resolution's {@code replicas}
 */
public record RestartResolutionEvidence(List<RestartObservation> restartsObserved)
        implements ResolutionEvidence {

    public RestartResolutionEvidence {
        if (restartsObserved == null || restartsObserved.isEmpty()) {
            throw new IllegalArgumentException("resolution evidence names at least one restart "
                    + "observation — resolution_is_evidence refuses an empty array, and an operator "
                    + "confirming from nothing is the case the state exists to prevent");
        }
        restartsObserved = List.copyOf(restartsObserved);
    }
}
