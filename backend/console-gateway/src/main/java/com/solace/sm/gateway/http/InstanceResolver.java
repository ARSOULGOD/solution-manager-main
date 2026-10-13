package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code getInstance}'s resource: the INSTANCE named by the path. The control plane's resource directory
 * answers for it, so an instance the control plane never registered (an abandoned declare, a pending one whose
 * registration never landed) is {@code 404} before the handler runs; a registered-but-still-{@code pending} one passes
 * and the handler's sweep makes it visible (BE-D32).
 */
@Component
public final class InstanceResolver implements ResourceResolver {

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        return ResourceRef.instance(EnvironmentResolver.pathId(request, "instanceId", ResourceKind.INSTANCE),
                new TenantId(session.organizationId()));
    }
}
