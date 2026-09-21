package com.solace.sm.cp.authz;

import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import java.util.Objects;

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
}
