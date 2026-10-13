package com.solace.sm.gateway.http;

import java.util.UUID;

/** Optional coordinates of a grant scope, in hierarchy order. */
public record ScopePath(@WireOptional UUID organizationId, @WireOptional UUID subOrganizationId,
                        @WireOptional UUID environmentId, @WireOptional UUID solutionId,
                        @WireOptional UUID instanceId) {
}
