package com.solace.sm.cp.authz;

import java.util.Objects;
import java.util.UUID;

/** Server-resolved authorization context required to canonicalize an organization-owned scope. */
public record SessionContext(UUID organizationId) {

    public SessionContext {
        Objects.requireNonNull(organizationId, "organization id");
    }
}
