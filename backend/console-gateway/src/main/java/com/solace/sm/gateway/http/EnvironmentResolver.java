package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Resolves {@code declareInstance}'s resource: the ENVIRONMENT named by the path ({@code OR-52}, {@code OR-27}).
 *
 * <p>⛔ The environment comes from the path, never the body: this runs in {@code preHandle}, before the body is read
 * ({@link ResourceResolver}). The tenant on the reference is the session's own ({@code I5}); whether the environment
 * exists, and is the caller's organization's, is the authorization service's answer - another organization's
 * environment is {@code 404}, indistinguishable from an absent one.
 */
@Component
public final class EnvironmentResolver implements ResourceResolver {

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        return ResourceRef.of(ResourceKind.ENVIRONMENT, pathId(request, "environmentId", ResourceKind.ENVIRONMENT),
                new TenantId(session.organizationId()));
    }

    /** A path id that is not a UUID names nothing: {@code 404}. The exception message is fixed (it is logged). */
    static UUID pathId(HttpServletRequest request, String variable, ResourceKind kind) {
        return pathId(request, variable, () -> new ResourceNotFoundException(kind));
    }

    /** As above for a thing that is not a {@link ResourceKind} (an agent): the not-found names {@code subject}, a fixed noun. */
    static UUID pathId(HttpServletRequest request, String variable, String subject) {
        return pathId(request, variable, () -> new ResourceNotFoundException(subject));
    }

    private static UUID pathId(HttpServletRequest request, String variable,
            java.util.function.Supplier<ResourceNotFoundException> notFound) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Object raw = variables instanceof Map<?, ?> map ? map.get(variable) : null;
        if (!(raw instanceof String text)) {
            throw notFound.get();
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw notFound.get();
        }
    }
}
