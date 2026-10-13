package com.solace.sm.cp.authz;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Authoritative directory from resource identity to its authorization scope path. */
public interface ResourceScopeDirectory {

    Optional<ScopePath> findScopePath(ResourceRef resource);

    /**
     * The scope paths of those {@code ids} of one {@code kind} that exist AND sit in {@code
     * organization}; every other id (absent, or in another organization) is simply not a key. The
     * organization is part of the lookup, never a filter applied after it ({@code I5}).
     *
     * <p>The default asks {@link #findScopePath} once per id and filters in Java, so a directory
     * with no batched query still honors the contract; a database-backed one overrides it with a
     * single bind-parameter query.
     */
    default Map<UUID, ScopePath> findScopePaths(ResourceKind kind, UUID organization, Collection<UUID> ids) {
        TenantId tenant = new TenantId(organization);
        Map<UUID, ScopePath> found = new LinkedHashMap<>();
        for (UUID id : ids) {
            findScopePath(ResourceRef.of(kind, id, tenant))
                    .filter(path -> organization.equals(path.organization()))
                    .ifPresent(path -> found.put(id, path));
        }
        return found;
    }
}
