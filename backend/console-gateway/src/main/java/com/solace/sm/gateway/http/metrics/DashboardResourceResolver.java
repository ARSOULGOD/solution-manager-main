package com.solace.sm.gateway.http.metrics;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.gateway.http.ResourceResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Resolves a dashboard route's resource: the INSTANCE the dashboard IS (plan 011 M-Q10, {@code OR-78}:
 * {@code dashboard_id = 'db_' || instance_id}, and the database CHECKs it).
 *
 * <p>The id is read from the PATH only ({@link ResourceResolver}: a resource must never come from the body, which is not
 * even readable here), matched against the contract's pattern, and the instance id is taken straight out of it. There is
 * <b>no database read</b>: the interceptor then {@code require}s {@code dashboard.view}, {@code .edit} or {@code .reset} at
 * that instance's scope, and the resource directory answers for existence ({@code 404} for an unknown or another
 * organization's instance, identical to out of scope).
 *
 * <p>A malformed id is {@code 404}, exactly like an unknown instance, and the exception's message is fixed: it is logged,
 * so it is never built from the path.
 */
@Component
public final class DashboardResourceResolver implements ResourceResolver {

    /** The contract's {@code DashboardId} pattern: lower-case hex only (a {@code UUID.fromString} would also take upper case). */
    private static final Pattern DASHBOARD_ID =
            Pattern.compile("db_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Object raw = variables instanceof Map<?, ?> map ? map.get("dashboardId") : null;
        return ResourceRef.instance(instanceOf(raw), new TenantId(session.organizationId()));
    }

    /** The instance a dashboard id names. @throws ResourceNotFoundException the id is not a dashboard id */
    static UUID instanceOf(Object dashboardId) {
        if (!(dashboardId instanceof String text) || !DASHBOARD_ID.matcher(text).matches()) {
            throw new ResourceNotFoundException(ResourceKind.INSTANCE);
        }
        return UUID.fromString(text.substring(3));
    }
}
