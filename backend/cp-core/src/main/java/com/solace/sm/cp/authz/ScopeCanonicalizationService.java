package com.solace.sm.cp.authz;

import com.solace.sm.cp.api.authz.ScopePath;
import java.util.Objects;

/**
 * The runtime entry point for scope creation. The organization is derived server-side; callers do
 * not supply an authority claim that can select another organization.
 */
public final class ScopeCanonicalizationService {

    private final ScopeStore store;

    public ScopeCanonicalizationService(ScopeStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public ScopeId canonicalize(SessionContext session, ScopeRequest requested) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(requested, "requested");
        if (requested.isInstall()) {
            throw new ScopeNotCanonicalizableException(
                    "the install scope cannot be canonicalized from an organization session");
        }
        ScopePath path = new ScopePath(session.organizationId(), requested.organizationUnitId(),
                requested.environmentId(), requested.solutionId(), requested.instanceId());
        return store.canonicalize(path);
    }
}
