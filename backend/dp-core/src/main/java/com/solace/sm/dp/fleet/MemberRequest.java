package com.solace.sm.dp.fleet;

import com.solace.sm.dp.apply.model.RequestState;
import java.time.Instant;

/**
 * What the fleet reads of a member's {@code apply_request} to decide whether the member is settled: the state, and the
 * two columns that decide the states which are not decided by the state alone ({@code fleet-apply.md} section 5.3).
 *
 * @param resolutionOutcome {@code applied | not_applied}, set exactly when the request is {@code INDETERMINATE_RESOLVED}
 * @param reconcileClass    the reconciler's give-up classifier, null until it gives up
 * @param stateVersion      the request's own stamp
 * @param terminalAt        {@code apply_request.terminal_at}: set exactly when the state is not {@code REQUESTED},
 *                          {@code RUNNING} or {@code APPLY_INDETERMINATE} ({@code terminal_has_timestamp}), so it is NULL
 *                          for an {@code APPLY_INDETERMINATE} request the reconciler has given up on
 * @param settleAnchor      the instant the settle delay ({@code OPS-Q8} (x)) measures from:
 *                          {@code COALESCE(apply_request.terminal_at, apply_reconciliation.gave_up_at)}. The second term
 *                          is what a given-up {@code APPLY_INDETERMINATE} request has instead of a {@code terminal_at}
 *                          (the reconciler writes it in the give-up transaction, V011); null when neither is set, and then
 *                          the member cannot have settled either
 */
public record MemberRequest(RequestState state, String resolutionOutcome, String reconcileClass, long stateVersion,
        Instant terminalAt, Instant settleAnchor) {

    /** A request with no reconciliation row: the anchor is the {@code terminal_at}, if any. */
    public MemberRequest(RequestState state, String resolutionOutcome, String reconcileClass, long stateVersion,
            Instant terminalAt) {
        this(state, resolutionOutcome, reconcileClass, stateVersion, terminalAt, terminalAt);
    }
}
