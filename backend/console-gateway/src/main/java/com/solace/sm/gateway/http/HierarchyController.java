package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.hierarchy.HierarchyDirectory;
import com.solace.sm.cp.api.session.Session;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * console-integration.md §5.2 rows 8-10: the three authentication-only hierarchy reads.
 *
 * <p>⛔ <b>Deliberately NOT a CATALOG operation.</b> None of these three carries a
 * {@code @ConsoleOperation(resource = ...)} — see {@link OperationClassification.AuthenticationOnly}
 * and {@link OperationPolicy}. {@link HierarchyDirectory} derives its own result from the caller's
 * bindings ({@code I5}: the organization is the session's own, never a path parameter taken as an
 * authority claim); there is nothing here for {@link AuthenticationInterceptor} to resolve or
 * authorize against, and {@code AuthorizationService} is never consulted for these three.
 */
@RestController
@RequestMapping("/api/cp/hierarchy")
public class HierarchyController {

    private final HierarchyDirectory hierarchyDirectory;

    public HierarchyController(HierarchyDirectory hierarchyDirectory) {
        this.hierarchyDirectory = Objects.requireNonNull(hierarchyDirectory, "hierarchyDirectory");
    }

    @GetMapping("/organizations")
    @ConsoleOperation("listOrganizations")
    public ResponseEntity<ScopeNodeList> listOrganizations(Session session) {
        return ResponseEntity.ok(toWire(hierarchyDirectory.listOrganizations(session)));
    }

    @GetMapping("/organizations/{organizationId}/sub-orgs")
    @ConsoleOperation("listSubOrganizations")
    public ResponseEntity<ScopeNodeList> listSubOrganizations(@PathVariable UUID organizationId, Session session) {
        return ResponseEntity.ok(toWire(hierarchyDirectory.listSubOrganizations(session, organizationId)));
    }

    @GetMapping("/organizations/{organizationId}/sub-orgs/{subOrganizationId}/environments")
    @ConsoleOperation("listEnvironments")
    public ResponseEntity<ScopeNodeList> listEnvironments(@PathVariable UUID organizationId,
            @PathVariable UUID subOrganizationId, Session session) {
        return ResponseEntity.ok(
                toWire(hierarchyDirectory.listEnvironments(session, organizationId, subOrganizationId)));
    }

    private static ScopeNodeList toWire(List<com.solace.sm.cp.api.hierarchy.ScopeNode> nodes) {
        List<ScopeNode> items = nodes.stream().map(node -> new ScopeNode(node.id(), node.name())).toList();
        return new ScopeNodeList(null, items);
    }
}
