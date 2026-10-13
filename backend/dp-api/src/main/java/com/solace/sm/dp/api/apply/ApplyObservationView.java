package com.solace.sm.dp.api.apply;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A request as the journal records it. {@code state} is the engine's {@code RequestState}, verbatim; {@code terminal}
 * is its complement of the three open states; {@code stateVersion} is the request's own stamp, the only ordering
 * signal.
 *
 * @param operation       {@code apply}, {@code restart} or {@code stop}
 * @param placementId     {@code null} for an instance-wide request
 * @param generation      {@code null} for a stop
 * @param reconcileReason the indeterminate reconciler's give-up classifier (plan 010 T6.8; {@code
 *                        apply_request.reconcile_class}), present only while the request stays {@code APPLY_INDETERMINATE}
 *                        after the reconciler gave up; a classifier token, never file content (I6). {@code null} otherwise
 */
public record ApplyObservationView(UUID applyId, String operation, UUID placementId, Long generation,
        Instant requestedAt, String state, boolean terminal, long stateVersion, List<ApplyStepView> steps,
        String reconcileReason) {

    public ApplyObservationView {
        steps = List.copyOf(steps);
    }

    /** A request the reconciler has not given up on. */
    public ApplyObservationView(UUID applyId, String operation, UUID placementId, Long generation, Instant requestedAt,
            String state, boolean terminal, long stateVersion, List<ApplyStepView> steps) {
        this(applyId, operation, placementId, generation, requestedAt, state, terminal, stateVersion, steps, null);
    }
}
