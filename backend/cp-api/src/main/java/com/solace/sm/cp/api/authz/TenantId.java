package com.solace.sm.cp.api.authz;

import java.util.Objects;
import java.util.UUID;

/** Internal isolation key. It is never accepted as proof of authority from an external client. */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "tenant id");
    }
}
