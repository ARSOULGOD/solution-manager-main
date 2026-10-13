package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.identity.AssignmentAdministration;
import com.solace.sm.cp.api.identity.AssignmentView;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Resolves {@code revokeAssignment} to the node the stored binding is held at, the scope {@code role.assign} is evaluated
 * at (plan 013 T3.2; D-REG 5.4 U7; {@code x-sm-scope: named} is "the stored resource's own scope, resolved server-side").
 * A binding at the install scope names no node, so it is authorized through its role definition, whose path is the
 * install scope's (the same resource {@code AssignmentAdministrationService.revoke} uses).
 *
 * <p>An assignment that is not in the session's organization, or does not exist, or whose id is not a UUID, is
 * {@code 404}, indistinguishable from one the caller may not touch. ⛔ The id comes from the path: this runs in
 * {@code preHandle}, before any body is read; the organization is the session's ({@code I5}).
 */
@Component
public final class AssignmentResolver implements ResourceResolver {

    /** The noun in the (fixed) not-found message, as the service names it (W3D-5). */
    private static final String ASSIGNMENT = "role assignment";

    private final AssignmentAdministration assignments;

    public AssignmentResolver(AssignmentAdministration assignments) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
    }

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        UUID id = assignmentId(request);
        AssignmentView binding = assignments.find(session.organizationId(), id)
                .orElseThrow(() -> new ResourceNotFoundException(ASSIGNMENT));
        TenantId tenant = new TenantId(session.organizationId());
        if (binding.scope().depth() == 0) {
            return ResourceRef.of(ResourceKind.ROLE_DEFINITION, binding.roleId(), tenant);
        }
        return GrantScopes.nodeAt(binding.scope(), session.organizationId());
    }

    private static UUID assignmentId(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Object raw = variables instanceof Map<?, ?> map ? map.get("assignmentId") : null;
        if (!(raw instanceof String text)) {
            throw new ResourceNotFoundException(ASSIGNMENT);
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException(ASSIGNMENT);
        }
    }
}
