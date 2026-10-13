package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.UUID;

/**
 * Request body of {@code grantAssignment} (console.yaml {@code GrantAssignmentRequest}; {@code additionalProperties:
 * false}). A structural fault (a missing field, a stray key, a value that is not a UUID) is {@code 400}.
 *
 * <p>{@code scope} is a {@link ScopeRef}: the DEEPEST id, never an organization ({@code I5}: the organization is the
 * session's). Whether the nodes exist, are in the caller's organization and are in that order is the service's to decide
 * ({@code 404}); this record only refuses a request that has no scope at all.
 */
public record GrantAssignmentRequest(UUID principalId, UUID roleId, ScopeRef scope) {

    public GrantAssignmentRequest {
        if (principalId == null || roleId == null || scope == null) {
            throw new IllegalArgumentException("principalId, roleId and scope are required");
        }
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
