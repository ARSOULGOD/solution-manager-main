package com.solace.sm.cp.authz;

import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Allow-only scope evaluation with downward authorization inheritance. */
public final class ScopeEvaluatingAuthorizationService implements AuthorizationService {

    private final PrincipalOrganizationResolver principalOrganizations;
    private final AuthorizationGrantRepository grants;
    private final ScopePathResolver paths;

    public ScopeEvaluatingAuthorizationService(PrincipalOrganizationResolver principalOrganizations,
                                               AuthorizationGrantRepository grants,
                                               ScopePathResolver paths) {
        this.principalOrganizations = Objects.requireNonNull(principalOrganizations, "principal organizations");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    @Override
    public void require(PrincipalRef principal, Permission permission, ResourceRef resource) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(permission, "permission");
        ScopePath resourcePath = paths.scopePathOf(Objects.requireNonNull(resource, "resource"));
        UUID organization = principalOrganizations.organizationOf(principal)
                .orElseThrow(() -> new AccessDeniedException(permission));
        if (resourcePath.organization() != null && !resourcePath.organization().equals(organization)) {
            throw new ResourceNotFoundException(resource.kind());
        }

        List<AuthorizationGrant> held = grants.grantsFor(principal);
        if (held.stream().noneMatch(grant -> ScopeVisibility.isVisible(grant.scope(), resourcePath))) {
            throw new ResourceNotFoundException(resource.kind());
        }
        boolean allowed = held.stream()
                .filter(grant -> grant.scope().authorizes(resourcePath))
                .anyMatch(grant -> grant.permissions().contains(permission));
        if (!allowed) {
            throw new AccessDeniedException(permission);
        }
    }
}
