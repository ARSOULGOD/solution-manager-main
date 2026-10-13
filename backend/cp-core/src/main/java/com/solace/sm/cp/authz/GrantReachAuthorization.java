package com.solace.sm.cp.authz;

import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ScopePath;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ReachAuthorization} over the same grants, organization resolver and scope-path resolver
 * {@link ScopeEvaluatingAuthorizationService} uses — and the same {@link ScopePath#authorizes}
 * downward rule, deliberately NOT {@link ScopeVisibility}, whose either-direction test would make an
 * ancestor of the principal's grant look reachable.
 *
 * <p><b>Cost.</b> One grant read, one organization read and one directory read per call, whatever the
 * list's length — not one {@code require} (each of which re-reads all three) per item. The directory
 * read is tenant-bound in its own query ({@link ScopePathResolver#scopePathsOf}); the caller bounds
 * the list (a page, or one organization's fleet).
 */
public final class GrantReachAuthorization implements ReachAuthorization {

    private final PrincipalOrganizationResolver principalOrganizations;
    private final AuthorizationGrantRepository grants;
    private final ScopePathResolver paths;

    public GrantReachAuthorization(PrincipalOrganizationResolver principalOrganizations,
                                   AuthorizationGrantRepository grants,
                                   ScopePathResolver paths) {
        this.principalOrganizations = Objects.requireNonNull(principalOrganizations, "principal organizations");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    @Override
    public void requireHeldAnywhere(PrincipalRef principal, Permission permission) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(permission, "permission");
        UUID organization = principalOrganizations.organizationOf(principal)
                .orElseThrow(() -> new AccessDeniedException(permission));
        boolean held = grants.grantsFor(principal).stream()
                .filter(grant -> inOrganization(grant.scope(), organization))
                .anyMatch(grant -> grant.permissions().contains(permission));
        if (!held) {
            throw new AccessDeniedException(permission);
        }
    }

    @Override
    public Set<UUID> authorizedSubset(PrincipalRef principal, Permission permission, ResourceKind kind,
            Collection<UUID> ids) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return Set.of();
        }
        UUID organization = principalOrganizations.organizationOf(principal).orElse(null);
        if (organization == null) {
            return Set.of();
        }
        List<ScopePath> reached = grants.grantsFor(principal).stream()
                .filter(grant -> inOrganization(grant.scope(), organization))
                .filter(grant -> grant.permissions().contains(permission))
                .map(AuthorizationGrant::scope)
                .toList();
        if (reached.isEmpty()) {
            return Set.of();
        }
        Set<UUID> subset = new LinkedHashSet<>();
        paths.scopePathsOf(kind, organization, ids).forEach((id, path) -> {
            // The organization is already bound in the directory read; the comparison stays as the
            // second lock, so a directory that forgot the bind still cannot widen the subset.
            if (organization.equals(path.organization())
                    && reached.stream().anyMatch(scope -> scope.authorizes(path))) {
                subset.add(id);
            }
        });
        return Set.copyOf(subset);
    }

    /** An install-scoped grant (no organization) reaches this organization; any other grant must be IN it. */
    private static boolean inOrganization(ScopePath scope, UUID organization) {
        return scope.organization() == null || scope.organization().equals(organization);
    }
}
