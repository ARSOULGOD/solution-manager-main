package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.solace.sm.cp.api.catalog.EntitlementAdministration;

/**
 * Request body of {@code setEntitlement} (console.yaml {@code SetEntitlementRequest}: {@code required: [maxInstances]},
 * {@code additionalProperties: false}). {@code maxInstances} is 1 to {@link EntitlementAdministration#MAX_INSTANCES_LIMIT}:
 * the table's {@code CHECK (max_instances > 0)} and the service's range. An entitlement is lowered, never removed, so zero
 * is not a value.
 *
 * <p>No organization and no solution in the body: the organization is the session's ({@code I5}) and the solution is the
 * path's.
 *
 * <p>The component is a {@link JsonNode} on purpose ({@code SubmitApplyRequest} is the precedent): an {@code Integer}
 * component would let Jackson coerce {@code 1.5} to {@code 1} and {@code "3"} to {@code 3}, silently setting a limit the
 * caller did not ask for. Only a JSON integer is a limit.
 */
public record SetEntitlementRequest(JsonNode maxInstances) {

    public SetEntitlementRequest {
        if (maxInstances == null || !maxInstances.isIntegralNumber() || !maxInstances.canConvertToInt()
                || maxInstances.intValue() < 1 || maxInstances.intValue() > EntitlementAdministration.MAX_INSTANCES_LIMIT) {
            throw new IllegalArgumentException("maxInstances is an integer, 1 to "
                    + EntitlementAdministration.MAX_INSTANCES_LIMIT);
        }
    }

    /** A request for {@code limit} (for a caller that is not a JSON body). */
    public static SetEntitlementRequest of(int limit) {
        return new SetEntitlementRequest(IntNode.valueOf(limit));
    }

    /** The validated limit. */
    public int limit() {
        return maxInstances.intValue();
    }

    @JsonAnySetter
    void refuseUnknown(String unknown, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
