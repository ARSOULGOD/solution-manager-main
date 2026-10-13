package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.hierarchy.HierarchyAdministration;
import com.solace.sm.cp.api.session.Session;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The scope tree's writes (plan 013 T2.3; D-REG 5.3 H1 to H5): {@code createSubOrganization}, {@code createEnvironment},
 * {@code renameOrganization}, {@code renameSubOrganization}, {@code renameEnvironment}. A translator: the rules are
 * {@link HierarchyAdministration}'s. It sits beside {@link HierarchyController} (the three reads), not in it, so that class
 * and its constructor stay what its tests pin.
 *
 * <p>{@code hierarchy.edit} has been checked by {@link AuthenticationInterceptor} before any handler here runs, at the
 * PARENT of a create and at the NODE of a rename ({@link OrganizationNodeResolver}, {@link SubOrganizationNodeResolver},
 * {@link EnvironmentNodeResolver}), and each resolver has already refused a path whose organization is not the session's.
 *
 * <p><b>{@code renameOrganization} is the exception ({@code OR-125} (3)): install scope only.</b> Its resolver is
 * {@link InstallOrganizationResolver}: it checks only that the path id is a UUID (a malformed one is {@code 404} before the
 * authorizer, as in the other four writes) and otherwise reads nothing, so an organization-bound Administrator is
 * refused {@code 403} before the path id is compared with anything: the answer is the same for its own organization, another existing
 * one and a nonexistent one, and the install-wide organization-name {@code 409} is reachable only by an install-bound
 * principal. The refusal is {@code 403}, not {@code 404}, because it is a verdict on the caller's grant and carries no
 * information about the named organization. An install-bound caller naming an organization that is not its own tenant's
 * then meets {@link #requireOwn} ({@code 404}). A sub-organization or environment rename keeps its per-organization
 * scope; its sibling-name {@code 409} inside one organization is accepted.
 * Every handler passes the SESSION's principal and the path's (now session-confirmed) organization: no request field
 * names an organization ({@code I5}).
 *
 * <p>A create answers {@code 201} when this call wrote the node and {@code 200} for the identical replay (the exact name,
 * under the same parent). A name that differs only by case is {@code 409} ({@code OR-123}), rendered by
 * {@link HierarchyErrorAdvice}; no handler here compares two names.
 */
@RestController
@RequestMapping("/api/cp/hierarchy/organizations")
public class HierarchyAdministrationController {

    private final HierarchyAdministration hierarchy;
    private final RequestOriginFactory origins;

    public HierarchyAdministrationController(HierarchyAdministration hierarchy, RequestOriginFactory origins) {
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
        this.origins = Objects.requireNonNull(origins, "origins");
    }

    @PostMapping("/{organizationId}/sub-orgs")
    @ConsoleOperation(value = "createSubOrganization", resource = OrganizationNodeResolver.class)
    public ResponseEntity<ScopeNode> createSubOrganization(@PathVariable UUID organizationId,
            @RequestBody NodeNameRequest body, Session session) {
        requireOwn(organizationId, session);
        return created(hierarchy.createSubOrganization(session.principal(), origins.create(), organizationId,
                body.value()));
    }

    @PostMapping("/{organizationId}/sub-orgs/{subOrganizationId}/environments")
    @ConsoleOperation(value = "createEnvironment", resource = SubOrganizationNodeResolver.class)
    public ResponseEntity<ScopeNode> createEnvironment(@PathVariable UUID organizationId,
            @PathVariable UUID subOrganizationId, @RequestBody NodeNameRequest body, Session session) {
        requireOwn(organizationId, session);
        return created(hierarchy.createEnvironment(session.principal(), origins.create(), organizationId,
                subOrganizationId, body.value()));
    }

    @PatchMapping("/{organizationId}")
    @ConsoleOperation(value = "renameOrganization", resource = InstallOrganizationResolver.class)
    public ResponseEntity<ScopeNode> renameOrganization(@PathVariable UUID organizationId,
            @RequestBody NodeNameRequest body, Session session) {
        requireOwn(organizationId, session);
        return ResponseEntity.ok(toWire(hierarchy.renameOrganization(session.principal(), origins.create(),
                organizationId, body.value())));
    }

    @PatchMapping("/{organizationId}/sub-orgs/{subOrganizationId}")
    @ConsoleOperation(value = "renameSubOrganization", resource = SubOrganizationNodeResolver.class)
    public ResponseEntity<ScopeNode> renameSubOrganization(@PathVariable UUID organizationId,
            @PathVariable UUID subOrganizationId, @RequestBody NodeNameRequest body, Session session) {
        requireOwn(organizationId, session);
        return ResponseEntity.ok(toWire(hierarchy.renameSubOrganization(session.principal(), origins.create(),
                organizationId, subOrganizationId, body.value())));
    }

    @PatchMapping("/{organizationId}/sub-orgs/{subOrganizationId}/environments/{environmentId}")
    @ConsoleOperation(value = "renameEnvironment", resource = EnvironmentNodeResolver.class)
    public ResponseEntity<ScopeNode> renameEnvironment(@PathVariable UUID organizationId,
            @PathVariable UUID subOrganizationId, @PathVariable UUID environmentId, @RequestBody NodeNameRequest body,
            Session session) {
        requireOwn(organizationId, session);
        return ResponseEntity.ok(toWire(hierarchy.renameEnvironment(session.principal(), origins.create(),
                organizationId, subOrganizationId, environmentId, body.value())));
    }

    /**
     * The resolvers refused a foreign organization already; this is the handler's own check, because the service trusts
     * the id it is handed ({@code I5}) and a route wired without the resolver must not reach it.
     */
    private static void requireOwn(UUID organizationId, Session session) {
        if (!organizationId.equals(session.organizationId())) {
            throw new com.solace.sm.cp.api.authz.ResourceNotFoundException(
                    com.solace.sm.cp.api.authz.ResourceKind.ORGANIZATION);
        }
    }

    private static ResponseEntity<ScopeNode> created(HierarchyAdministration.Created created) {
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(toWire(created.node()));
    }

    private static ScopeNode toWire(com.solace.sm.cp.api.hierarchy.ScopeNode node) {
        return new ScopeNode(node.id(), node.name());
    }
}
