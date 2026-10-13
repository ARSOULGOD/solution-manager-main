package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves an operation on one connection to the CONNECTION named by the path's {@code {connectionId}} (plan 013
 * T4, {@code BE-D66}, {@code OR-130}): {@code updateConnection}, {@code deactivateConnection}, {@code
 * deleteConnection}. A connection carries its own {@code ownershipScope} ({@code SM-D23}'s pattern, extended by
 * {@code OR-130}) and the authorization service resolves the verb's scope from it through {@code
 * JdbcResourceScopeDirectory}'s {@code CONNECTION} branch, so the scope is never named by the caller: a connection
 * outside the caller's organization, or one that does not exist, is {@code 404}.
 *
 * <p>⛔ The id comes from the path: this runs in {@code preHandle}, before any body is read. Shaped exactly like
 * {@link TargetResolver}.
 */
@Component
public final class ConnectionResolver implements ResourceResolver {

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        return ResourceRef.of(ResourceKind.CONNECTION,
                EnvironmentResolver.pathId(request, "connectionId", ResourceKind.CONNECTION),
                new TenantId(session.organizationId()));
    }
}
