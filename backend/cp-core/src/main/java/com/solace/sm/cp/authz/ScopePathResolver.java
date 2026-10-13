package com.solace.sm.cp.authz;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Resolves only resource kinds backed by an installed authoritative directory. */
public final class ScopePathResolver {

    private final ResourceScopeDirectory directory;

    public ScopePathResolver(ResourceScopeDirectory directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public ScopePath scopePathOf(ResourceRef resource) {
        Objects.requireNonNull(resource, "resource");
        return directory.findScopePath(resource)
                .orElseThrow(() -> new ResourceNotFoundException(resource.kind()));
    }

    /** One directory read for a whole list: the ids of {@code kind} that exist in {@code organization}. */
    public Map<UUID, ScopePath> scopePathsOf(ResourceKind kind, UUID organization, Collection<UUID> ids) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(ids, "ids");
        return directory.findScopePaths(kind, organization, ids);
    }
}
