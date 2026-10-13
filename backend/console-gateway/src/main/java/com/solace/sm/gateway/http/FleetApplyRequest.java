package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Request body of {@code submitFleetApply} (console.yaml's {@code FleetApplyRequest}, {@code additionalProperties:
 * false}; {@code fleet-apply.md} section 7).
 *
 * <p>⛔ <b>No {@code organizationId}, and no other key, is accepted at the top level</b> ({@code I5}): the contract
 * closes the object, and Spring Boot disables Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES} globally, so an any-setter
 * that refuses is what makes that real ({@link LoadPackageRequest} says why). A stray key is a {@code 400}, not a
 * silently ignored claim. A member item is not closed by the contract, so a stray key there is ignored - and none is ever
 * read: the member's authority is the {@code require} at its instance, never a field.
 *
 * <p>Structural faults - a missing field, a wrong type, an empty or oversized list, a generation below 1, a batch size
 * below 1, a strategy that is not one of the five engine tokens, {@code restart-all} without
 * {@code outageConfirmedBy} - are {@code 400}. Whether the strategy is SERVED
 * ({@code 501}), whether each member is authorized ({@code 403}) and whether it is valid ({@code 422}) are not decided
 * here, and happen in that order.
 *
 * @param batchSize           defaults to {@code 1}, the most conservative rollout ({@code Q-F4})
 * @param onFailure           defaults to {@code halt-remaining}: a failure stops the rollout visibly ({@code Q-F4})
 * @param proceedWithAuthorized defaults to {@code false}: by default a fleet with any refused member is refused whole
 *                            ({@code OR-57})
 */
public record FleetApplyRequest(UUID solutionId, List<Member> members, String strategy, String outageConfirmedBy,
        int batchSize, String onFailure, boolean proceedWithAuthorized) {

    /** The contract's {@code maxItems} on {@code members} ({@code Q-F4}). */
    static final int MAX_MEMBERS = 100;

    static final Set<String> ON_FAILURE = Set.of("continue", "halt-remaining");

    /** Jackson's entry point: the optional fields arrive as boxed nulls and are defaulted here, once. */
    @com.fasterxml.jackson.annotation.JsonCreator
    static FleetApplyRequest of(@com.fasterxml.jackson.annotation.JsonProperty("solutionId") UUID solutionId,
            @com.fasterxml.jackson.annotation.JsonProperty("members") List<Member> members,
            @com.fasterxml.jackson.annotation.JsonProperty("strategy") String strategy,
            @com.fasterxml.jackson.annotation.JsonProperty("outageConfirmedBy") String outageConfirmedBy,
            @com.fasterxml.jackson.annotation.JsonProperty("batchSize") JsonNode batchSize,
            @com.fasterxml.jackson.annotation.JsonProperty("onFailure") String onFailure,
            @com.fasterxml.jackson.annotation.JsonProperty("proceedWithAuthorized") JsonNode proceedWithAuthorized) {
        int size = 1;
        if (batchSize != null && !batchSize.isNull()) {
            // A JsonNode on purpose: an Integer component would let Jackson coerce 1.5 to 1 and "3" to 3.
            if (!batchSize.isIntegralNumber() || !batchSize.canConvertToInt()) {
                throw new IllegalArgumentException("batchSize must be an integer");
            }
            size = batchSize.intValue();
        }
        boolean proceed = false;
        if (proceedWithAuthorized != null && !proceedWithAuthorized.isNull()) {
            if (!proceedWithAuthorized.isBoolean()) {
                throw new IllegalArgumentException("proceedWithAuthorized must be a boolean");
            }
            proceed = proceedWithAuthorized.booleanValue();
        }
        return new FleetApplyRequest(solutionId, members, strategy, outageConfirmedBy, size,
                onFailure == null ? "halt-remaining" : onFailure, proceed);
    }

    public FleetApplyRequest {
        if (solutionId == null) {
            throw new IllegalArgumentException("solutionId is required");
        }
        if (members == null || members.isEmpty() || members.size() > MAX_MEMBERS
                || members.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("members is required: between 1 and " + MAX_MEMBERS + " items");
        }
        members = List.copyOf(members);
        if (strategy == null || !SubmitApplyRequest.STRATEGIES.contains(strategy)) {
            throw new IllegalArgumentException("strategy must be one of the engine's five tokens");
        }
        SubmitApplyRequest.requireConfirmationForRestartAll(strategy, outageConfirmedBy);
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        if (!ON_FAILURE.contains(onFailure)) {
            throw new IllegalArgumentException("onFailure must be continue or halt-remaining");
        }
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }

    /**
     * One member. {@code generation} is a {@link JsonNode} for the same reason as {@link SubmitApplyRequest}'s: a
     * {@code Long} would let Jackson coerce {@code 1.5} to {@code 1}. {@code placementId} is required: v1 refuses
     * instance-wide apply ({@code BE-D23}, {@code OR-53}).
     */
    public record Member(UUID instanceId, UUID placementId, JsonNode generation) {

        public Member {
            if (instanceId == null || placementId == null) {
                throw new IllegalArgumentException("instanceId and placementId are required");
            }
            if (generation == null || !generation.isIntegralNumber() || !generation.canConvertToLong()
                    || generation.longValue() < 1) {
                throw new IllegalArgumentException("generation is required and an integer of at least 1");
            }
        }

        long generationNumber() {
            return generation.longValue();
        }
    }

    /** ⛔ {@code outageConfirmedBy} names a person; it is not printed. */
    @Override
    public String toString() {
        return "FleetApplyRequest[" + members.size() + " members, strategy=" + strategy + ", batchSize=" + batchSize
                + ", onFailure=" + onFailure + ", proceedWithAuthorized=" + proceedWithAuthorized + "]";
    }
}
