package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code renameEnvironment} ({@code x-sm-scope: environment}) to the ENVIRONMENT the path names. Unlike
 * {@link EnvironmentResolver} (whose route has no organization in its path) this checks the path's organization against
 * the session first ({@link HierarchyPath}); the service binds the sub-organization and environment to it again.
 */
@Component
public final class EnvironmentNodeResolver implements ResourceResolver {

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        HierarchyPath.sessionOrganization(request, session);
        // W4 review W4D-4: every id in the path is read here, so a malformed one is 404 before the authorizer, as in
        // every other position (it used to reach @PathVariable UUID conversion and answer 400 after authorization)
        EnvironmentResolver.pathId(request, "subOrganizationId", ResourceKind.SUB_ORGANIZATION);
        return HierarchyPath.node(ResourceKind.ENVIRONMENT,
                EnvironmentResolver.pathId(request, "environmentId", ResourceKind.ENVIRONMENT), session);
    }
}
