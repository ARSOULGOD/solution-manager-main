package com.solace.sm.cp.api.authz;

import java.util.Objects;
import java.util.UUID;

/**
 * What is being acted on: its kind, identity, and internal tenant isolation key.
 *
 * <p>⛔ <b>The scope path is resolved server-side and never taken from the client</b>
 * ({@code I5}). A client-supplied path is input to resolution at most; it is never trusted
 * and never persisted.
 *
 * <p>⚠️ This type deliberately carries no {@link ScopePath}. The authorization implementation's
 * scope resolver loads that path from authoritative server-side state. In particular, a placement
 * resolves to its owning instance's path; a handler cannot supply a narrower or wider path.
 */
public record ResourceRef(ResourceKind kind, UUID id, TenantId tenant) {

    public ResourceRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "resource id");
        Objects.requireNonNull(tenant, "tenant");
    }

    public static ResourceRef instance(UUID instanceId, TenantId tenant) {
        return new ResourceRef(ResourceKind.INSTANCE, instanceId, tenant);
    }

    /**
     * A placement, authorized at its owning instance.
     *
     * The scope resolver follows this identity to its owning instance. Placement never becomes a
     * grant level merely because an operation targets one.
     */
    public static ResourceRef placement(UUID placementId, TenantId tenant) {
        return new ResourceRef(ResourceKind.PLACEMENT, placementId, tenant);
    }

    public static ResourceRef solution(UUID solutionId, TenantId tenant) {
        return new ResourceRef(ResourceKind.SOLUTION, solutionId, tenant);
    }

    public static ResourceRef of(ResourceKind kind, UUID id, TenantId tenant) {
        return new ResourceRef(kind, id, tenant);
    }
}
