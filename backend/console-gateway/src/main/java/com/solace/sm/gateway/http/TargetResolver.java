package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves an operation on one target to the DEPLOYMENT_TARGET named by the path's {@code {targetId}} (plan 013 T1.4:
 * {@code getTargetConnection}; the operations of T1.5 reuse it). A target carries its own {@code ownershipScope}
 * ({@code SM-D23}) and the authorization service resolves the verb's scope from it, so the scope is never named by the
 * caller: a target outside the caller's organization, or one that does not exist, is {@code 404}.
 *
 * <p>⛔ The id comes from the path: this runs in {@code preHandle}, before any body is read.
 */
@Component
public final class TargetResolver implements ResourceResolver {

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        return ResourceRef.of(ResourceKind.DEPLOYMENT_TARGET,
                EnvironmentResolver.pathId(request, "targetId", ResourceKind.DEPLOYMENT_TARGET),
                new TenantId(session.organizationId()));
    }
}
