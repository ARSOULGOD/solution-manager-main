package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.identity.AssignmentAdministration;
import com.solace.sm.cp.api.identity.AssignmentView;
import com.solace.sm.cp.api.identity.RoleView;
import com.solace.sm.cp.api.session.Session;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roles and role assignments of the session's organization over the API (plan 013 T3.2; D-REG 5.4 U4 to U7):
 * {@code listRoles}, {@code listAssignments}, {@code grantAssignment} and {@code revokeAssignment}. A translator; the
 * rules are {@link AssignmentAdministration}'s.
 *
 * <p>The verb ({@code role.view}, {@code role.assign}) has been checked by {@link AuthenticationInterceptor} before any
 * handler here runs: at the session's organization for the two reads ({@link SessionOrganizationResourceResolver}), and
 * for the two writes at the SCOPE: the granted one the body names ({@link GrantedScopeResolver}) or the stored binding's
 * own ({@link AssignmentResolver}), so a principal bound at a sub-organization cannot grant at the organization. The
 * service evaluates the same verbs again at the same nodes and enforces what the interceptor cannot (the Administrator
 * rules, that the requested ancestors are the node's real ones, that the caller holds every verb it grants).
 *
 * <p>A grant answers {@code 201} when this call wrote the binding and {@code 200} for the identical replay. Every
 * handler passes the SESSION's principal and organization: nothing in the request names an organization ({@code I5}).
 */
@RestController
@RequestMapping("/api/cp/admin")
public class RoleAssignmentController {

    private final AssignmentAdministration assignments;
    private final RequestOriginFactory origins;

    public RoleAssignmentController(AssignmentAdministration assignments, RequestOriginFactory origins) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.origins = Objects.requireNonNull(origins, "origins");
    }

    @GetMapping("/roles")
    @ConsoleOperation(value = "listRoles", resource = SessionOrganizationResourceResolver.class)
    public ResponseEntity<RoleList> listRoles(Session session) {
        List<Role> items = assignments.listRoles(session.principal(), session.organizationId()).stream()
                .map(RoleAssignmentController::toWire).toList();
        return ResponseEntity.ok(new RoleList(items));
    }

    @GetMapping("/assignments")
    @ConsoleOperation(value = "listAssignments", resource = SessionOrganizationResourceResolver.class)
    public ResponseEntity<AssignmentList> listAssignments(@RequestParam(name = "principalId", required = false) UUID principalId,
            Session session) {
        List<Assignment> items = assignments.list(session.principal(), session.organizationId(),
                Optional.ofNullable(principalId)).stream().map(RoleAssignmentController::toWire).toList();
        return ResponseEntity.ok(new AssignmentList(items));
    }

    @PostMapping("/assignments")
    @ConsoleOperation(value = "grantAssignment", resource = GrantedScopeResolver.class)
    public ResponseEntity<Assignment> grantAssignment(@RequestBody GrantAssignmentRequest request, Session session) {
        ScopePath scope = GrantScopes.pathOf(request.scope(), session.organizationId());
        GrantedScopeResolver.requireAuthorized(scope, session.organizationId());
        AssignmentAdministration.Granted granted = assignments.grant(session.principal(), origins.create(),
                session.organizationId(), request.principalId(), request.roleId(), scope);
        return ResponseEntity.status(granted.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(toWire(granted.assignment()));
    }

    @DeleteMapping("/assignments/{assignmentId}")
    @ConsoleOperation(value = "revokeAssignment", resource = AssignmentResolver.class)
    public ResponseEntity<Void> revokeAssignment(@PathVariable UUID assignmentId, Session session) {
        assignments.revoke(session.principal(), origins.create(), session.organizationId(), assignmentId);
        return ResponseEntity.noContent().build();
    }

    private static Role toWire(RoleView view) {
        return new Role(view.id(), view.name(), view.permissions());
    }

    private static Assignment toWire(AssignmentView view) {
        ScopePath scope = view.scope();
        return new Assignment(view.assignmentId(), view.principalId(), view.roleId(),
                new com.solace.sm.gateway.http.ScopePath(scope.organization(), scope.subOrganization(),
                        scope.environment(), scope.solution(), scope.instance()));
    }
}
