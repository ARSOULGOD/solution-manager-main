package com.solace.sm.dp.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.RequestState;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code fleet-apply.md} section 5.3's settlement table, row by row, as the code that decides it. Pure: no database. A
 * state added to {@link RequestState} makes the switch in {@link FleetSettlement#of} refuse to compile, and the
 * exhaustive loop below names the new state the moment it is added.
 */
class FleetSettlementTest {

    private static MemberRequest request(RequestState state, String resolutionOutcome, String reconcileClass) {
        return new MemberRequest(state, resolutionOutcome, reconcileClass, 7L, null);
    }

    @Test
    void aRequestStillInFlightIsNotSettled() {
        assertEquals(Optional.empty(), FleetSettlement.of(request(RequestState.REQUESTED, null, null)));
        assertEquals(Optional.empty(), FleetSettlement.of(request(RequestState.RUNNING, null, null)));
    }

    @Test
    void appliedAndStagedAreSucceeded() {
        assertEquals(Optional.of("succeeded"), FleetSettlement.of(request(RequestState.APPLIED, null, null)));
        assertEquals(Optional.of("succeeded"), FleetSettlement.of(request(RequestState.STAGED, null, null)),
                "STAGED is the manual strategy's terminal success (BE-D16)");
    }

    @Test
    void aResolvedIndeterminateIsSettledByWhatTheOperatorFound() {
        assertEquals(Optional.of("succeeded"),
                FleetSettlement.of(request(RequestState.INDETERMINATE_RESOLVED, "applied", null)));
        assertEquals(Optional.of("failed"),
                FleetSettlement.of(request(RequestState.INDETERMINATE_RESOLVED, "not_applied", null)));
    }

    @Test
    void everyFailureStateIsFailed() {
        for (RequestState state : new RequestState[] {RequestState.FAILED_NO_EFFECT, RequestState.FAILED_ROLLED_BACK,
                RequestState.CONFIG_DIVERGED}) {
            assertEquals(Optional.of("failed"), FleetSettlement.of(request(state, null, null)), state.name());
        }
    }

    @Test
    void supersededAtFirstObservationIsFailedBecauseTheFleetCannotTellWhichWayItGotThere() {
        assertEquals(Optional.of("failed"), FleetSettlement.of(request(RequestState.SUPERSEDED, null, null)),
                "Q-F2 (a): conservative, so halt-remaining stops rather than proceeds on an unproven member");
    }

    @Test
    void anIndeterminateMemberWaitsUntilTheReconcilerGivesUpAndIsThenFailedButNotTerminal() {
        assertEquals(Optional.empty(), FleetSettlement.of(request(RequestState.APPLY_INDETERMINATE, null, null)),
                "waiting forever would stall the fleet; settling at once would advance past a host of unknown state");
        assertEquals(Optional.of("failed"),
                FleetSettlement.of(request(RequestState.APPLY_INDETERMINATE, null, "host.unreachable")));
        assertTrue(!RequestState.APPLY_INDETERMINATE.isTerminal(),
                "settled is not terminal: this is the one case where the two differ (Q-F2 (a))");
    }

    @Test
    void everyStateHasAnAnswerAndOnlyTheInFlightOnesHaveNone() {
        for (RequestState state : RequestState.values()) {
            Optional<String> settled = FleetSettlement.of(request(state, "applied", null));
            boolean inFlight = state == RequestState.REQUESTED || state == RequestState.RUNNING
                    || state == RequestState.APPLY_INDETERMINATE;
            assertEquals(inFlight, settled.isEmpty(), state + " -> " + settled);
        }
    }
}
