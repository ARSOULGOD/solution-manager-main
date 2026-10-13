package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code deactivateUser} and {@code resetUserCredential} to the PRINCIPAL the path's {@code {userId}} names
 * (plan 013 T3.2; D-REG section 4: administering a principal is an organization act, {@code ResourceKind.PRINCIPAL}).
 * The authorization service resolves the principal's own path, so a user of another organization, or none, is {@code 404},
 * indistinguishable from one the caller may not touch; the id is never authority ({@code I5}).
 *
 * <p>⛔ The id comes from the path: this runs in {@code preHandle}, before any body is read.
 */
@Component
public final class UserResolver implements ResourceResolver {

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        return ResourceRef.of(ResourceKind.PRINCIPAL,
                EnvironmentResolver.pathId(request, "userId", ResourceKind.PRINCIPAL),
                new TenantId(session.organizationId()));
    }
}
