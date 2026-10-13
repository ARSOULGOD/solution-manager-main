package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves an {@code x-sm-scope: organization} operation that names no resource in its path to the caller's OWN
 * organization ({@code loadPackage}, {@code OR-14}).
 *
 * <p>⛔ {@code I5}: the organization comes from the session, never from the path, the query or the body (which is
 * not even readable here - {@link ResourceResolver}). There is therefore nothing for a client to name, and the
 * authorization the interceptor then runs is "does this principal hold the verb at its own organization".
 */
@Component
public final class SessionOrganizationResourceResolver implements ResourceResolver {

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        return ResourceRef.of(ResourceKind.ORGANIZATION, session.organizationId(),
                new TenantId(session.organizationId()));
    }
}
