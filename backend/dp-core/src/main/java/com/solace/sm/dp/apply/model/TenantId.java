package com.solace.sm.dp.apply.model;

import java.util.Objects;
import java.util.UUID;

/**
 * The canonical tenant key (architecture §3.1 seam 1) — a distinct type because {@code I5} says every
 * query carries tenant scope, and three bare {@code UUID}s in one signature is how a caller passes
 * the instance id as the tenant id without the compiler noticing.
 *
 * @param value the tenant's UUID, never null
 */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "tenant id");
    }
}
