package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code createSubOrganization} ({@code x-sm-scope: organization}) to the ORGANIZATION the path names, which
 * must be the session's own (see {@link HierarchyPath}). {@code renameOrganization} is no longer resolved here: it is
 * install-scoped ({@code OR-125} (3), {@link InstallOrganizationResolver}). The path id is an address
 * checked against the session, never authority ({@code I5}); the body is not read.
 */
@Component
public final class OrganizationNodeResolver implements ResourceResolver {

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        return HierarchyPath.node(ResourceKind.ORGANIZATION, HierarchyPath.sessionOrganization(request, session),
                session);
    }
}
