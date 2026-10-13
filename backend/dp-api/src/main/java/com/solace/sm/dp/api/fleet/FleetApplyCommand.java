package com.solace.sm.dp.api.fleet;

import com.solace.sm.dp.api.apply.OutageConfirmation;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A fleet apply, as the gateway hands it to the data plane once every member has been authorized (or refused).
 *
 * @param solutionId       the <b>catalog</b> id every authorized member's instance must belong to ({@code OR-41})
 * @param members          every member the operator named, in the order named - refused ones included, so each keeps
 *                         its ordinal; the list index IS the ordinal
 * @param strategy         one of the five engine tokens, verbatim; one strategy for the whole fleet
 * @param outageConfirmedBy REQUIRED for {@code restart-all}, null otherwise: the PRINCIPAL ID that confirmed the outage,
 *                         written by the gateway from the session (plan 012 T2, {@code OPS-Q7} C-2), never a browser string
 * @param batchSize        members per batch, at least 1; member {@code k} is in batch {@code k / batchSize}
 * @param onFailure        {@code continue} or {@code halt-remaining}
 * @param actor            the session's principal id - never a request field
 * @param correlationId    the submit's correlation id
 */
public record FleetApplyCommand(UUID solutionId, List<Member> members, String strategy, String outageConfirmedBy,
        int batchSize, String onFailure, UUID actor, UUID correlationId) {

    /** The most members one fleet carries; the contract's {@code maxItems} ({@code Q-F4}). */
    public static final int MAX_MEMBERS = 100;

    public FleetApplyCommand {
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(onFailure, "onFailure");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlationId, "correlationId");
        OutageConfirmation.require(strategy, outageConfirmedBy);
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        if (members.isEmpty() || members.size() > MAX_MEMBERS) {
            throw new IllegalArgumentException("a fleet has between 1 and " + MAX_MEMBERS + " members");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
    }

    /**
     * One member. {@code refusal} is the gateway's verdict: {@code null} when the member is authorized, else the code a
     * single call at that instance would have answered. A refused member's ids are the CALLER-NAMED, unvalidated ones.
     */
    public record Member(UUID instanceId, UUID placementId, long generation, RefusalCode refusal) {

        public Member {
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(placementId, "placementId");
            if (generation <= 0) {
                throw new IllegalArgumentException("generation must be positive");
            }
        }

        public boolean refused() {
            return refusal != null;
        }
    }

    /** Why a member was refused at authorization: the two answers {@code require} gives. */
    public enum RefusalCode {
        FORBIDDEN, NOT_FOUND
    }
}
