package com.solace.sm.cp.authz;

import java.util.Objects;
import java.util.UUID;

/** Typed client input for a scope below the server-derived organization. */
public record ScopeRequest(ScopeKind kind, UUID organizationUnitId, UUID environmentId, UUID solutionId,
                           UUID instanceId) {

    public ScopeRequest {
        Objects.requireNonNull(kind, "kind");
        UUID[] levels = {organizationUnitId, environmentId, solutionId, instanceId};
        boolean ended = false;
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] == null) {
                ended = true;
            } else if (ended) {
                throw new IllegalArgumentException("a scope request must be contiguous from organization");
            }
        }
        int descendants = 0;
        for (UUID level : levels) {
            if (level != null) {
                descendants++;
            }
        }
        if (descendants != kind.depth() - (kind == ScopeKind.INSTALL ? 0 : 1)) {
            throw new IllegalArgumentException("scope request components do not match its kind");
        }
    }

    public static ScopeRequest install() {
        return new ScopeRequest(ScopeKind.INSTALL, null, null, null, null);
    }

    public static ScopeRequest organization() {
        return new ScopeRequest(ScopeKind.ORGANIZATION, null, null, null, null);
    }

    public static ScopeRequest environment(UUID organizationUnitId, UUID environmentId) {
        return new ScopeRequest(ScopeKind.ENVIRONMENT, Objects.requireNonNull(organizationUnitId),
                Objects.requireNonNull(environmentId), null, null);
    }

    boolean isInstall() {
        return kind == ScopeKind.INSTALL;
    }
}
