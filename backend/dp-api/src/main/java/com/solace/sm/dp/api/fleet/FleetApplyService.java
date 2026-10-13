package com.solace.sm.dp.api.fleet;

import com.solace.sm.dp.api.apply.NotImplementedStrategyException;
import java.util.Optional;
import java.util.UUID;

/**
 * The data plane's fleet-apply surface (plan 010 T11.2, {@code REQ-013}, {@code OR-47}; {@code fleet-apply.md}). Every
 * call is scoped to {@code organizationId} ({@code I5}): the caller passes the session's, never a request field.
 *
 * <p><b>Authorization is the gateway's, and it is finished before this is called.</b> The data plane may not call the
 * control plane's {@code AuthorizationService} ({@code I7}), so the gateway {@code require}s each member at its own
 * instance and hands over only the verdicts ({@link FleetApplyCommand.Member#refusal()}). A member refused there is
 * stored and reported; it is never read, validated or applied here. Authorization is decided at submit and is not
 * repeated for a later batch ({@code fleet-apply.md} Q-F1).
 *
 * <p><b>Submission is durable and admits nothing.</b> {@link #submitFleet} commits the fleet and its members and
 * returns; the one {@code FleetScheduler} admits batch 0 on its next pass. No adapter is called on the request thread.
 */
public interface FleetApplyService {

    /**
     * Records a fleet of ordinary apply requests, one per authorized member, in batches.
     *
     * @throws FleetValidationException         an authorized member fails a semantic check (never a refused member)
     * @throws FleetConflictException           an authorized member's placement is reserved by another running fleet
     * @throws NotImplementedStrategyException  {@code restart-all}, {@code rolling-no-validation} or {@code scheduled}
     * @throws IllegalArgumentException         the command is malformed: no authorized member, an unknown strategy or
     *                                          {@code onFailure}, a bound out of range
     */
    FleetAcceptedView submitFleet(UUID organizationId, FleetApplyCommand command);

    /**
     * The fleet as the journal holds it, every member included: the gateway filters the members the caller may see
     * ({@code Q-F6}, {@code Q-F8}). Empty when the fleet is absent or another organization's - indistinguishable.
     */
    Optional<FleetObservationView> observeFleet(UUID organizationId, UUID fleetId);
}
