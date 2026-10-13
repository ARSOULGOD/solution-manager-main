package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.catalog.EntitlementAdministration;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code renameOrganization} ({@code x-sm-scope: install}, {@code OR-125} (3)): the same INSTALL-wide anchor as
 * {@link InstallResourceResolver}, but it first PARSES the path's {@code organizationId} (W5 review W5C-3), so a path id
 * that is not a UUID is {@code 404} before the authorizer, as in the other four hierarchy writes (W4 review W4D-4).
 *
 * <p>The parsed id is checked for SHAPE only. It is deliberately not compared with the session's organization or looked
 * up: an organization-bound caller must get the same {@code 403} for its own, another and a nonexistent organization
 * (the refusal is a verdict on its grant and tells it nothing about any organization), and a malformed id is malformed
 * for everyone, so the {@code 404} distinguishes nothing. The comparison with the session's organization stays in the
 * handler's {@code requireOwn}, after the authorization decision.
 *
 * <p>⛔ {@code I5}: the id is never an authority; the resource's tenant is the session's.
 */
@Component
public final class InstallOrganizationResolver implements ResourceResolver {

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        EnvironmentResolver.pathId(request, "organizationId", ResourceKind.ORGANIZATION);
        return ResourceRef.of(ResourceKind.ROLE_DEFINITION, EntitlementAdministration.INSTALL_ANCHOR_ROLE,
                new TenantId(session.organizationId()));
    }
}
