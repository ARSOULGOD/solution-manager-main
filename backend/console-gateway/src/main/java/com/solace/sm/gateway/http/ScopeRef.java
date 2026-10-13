package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.UUID;

/**
 * A scope named in a REQUEST body (an {@code ownershipScope}; console.yaml's {@code ScopeRef}): {@code ScopePath}
 * without {@code organizationId}, because the organization is the session's and a request schema may not carry one
 * ({@code I5}, run-checks 14). Send the DEEPEST id; an empty object is the organization itself. Ids sent together must
 * agree, which the orchestrator checks against the tree ({@code 404} when they do not).
 *
 * <p>Spring Boot disables Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES} globally, so the any-setter that refuses is what
 * makes the contract's {@code additionalProperties: false} real: a stray key (an {@code organizationId} above all) is
 * {@code 400}, never a silently ignored claim.
 */
public record ScopeRef(UUID subOrganizationId, UUID environmentId, UUID solutionId, UUID instanceId) {

    /** The organization itself. */
    public static final ScopeRef ORGANIZATION = new ScopeRef(null, null, null, null);

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
