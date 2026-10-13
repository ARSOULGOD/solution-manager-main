package com.solace.sm.dp.fleet;

import java.util.Optional;

/**
 * When a member is SETTLED, and as what ({@code fleet-apply.md} section 5.3, the table, as code).
 *
 * <p>A member is settled the first time the scheduler observes its request in a state that no longer needs waiting
 * for. {@link FleetService} writes the answer once and never revisits it, so a fleet's verdict does not flip when a
 * request moves on later (a {@code STAGED} request superseded by a later apply, for example). Two readings are the
 * owner's ({@code OR-65}, Q-F2 (a)): {@code SUPERSEDED} at first observation is {@code failed}, and
 * {@code APPLY_INDETERMINATE} waits until the reconciler gives up ({@code reconcile_class} set) and is then
 * {@code failed} - which is why "settled" is not "terminal".
 */
public final class FleetSettlement {

    private FleetSettlement() {
    }

    /** The settlement of a request in this state, or empty while the member must still be waited on. */
    public static Optional<String> of(MemberRequest request) {
        return switch (request.state()) {
            case REQUESTED, RUNNING -> Optional.empty();
            case APPLIED, STAGED -> Optional.of(MemberRecord.SUCCEEDED);
            case INDETERMINATE_RESOLVED -> Optional.of("applied".equals(request.resolutionOutcome())
                    ? MemberRecord.SUCCEEDED : MemberRecord.FAILED);
            case FAILED_NO_EFFECT, FAILED_ROLLED_BACK, CONFIG_DIVERGED, SUPERSEDED -> Optional.of(MemberRecord.FAILED);
            case APPLY_INDETERMINATE -> request.reconcileClass() == null ? Optional.empty()
                    : Optional.of(MemberRecord.FAILED);
            // A fleet member is always an apply, and state_fits_operation keeps STOPPED / STOP_FAILED off an apply, so
            // this arm is unreachable. Failed, not an exception: a fleet that cannot classify must not advance past it.
            case STOPPED, STOP_FAILED -> Optional.of(MemberRecord.FAILED);
        };
    }
}
