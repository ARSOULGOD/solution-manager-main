package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.identity.AssignmentAdministration;
import com.solace.sm.cp.api.identity.AssignmentConflictException;
import com.solace.sm.cp.api.identity.AssignmentInvalidException;
import com.solace.sm.cp.api.identity.AssignmentView;
import com.solace.sm.cp.api.identity.RoleView;
import com.solace.sm.cp.api.identity.UserView;
import com.solace.sm.cp.authz.AuthorizationGrant;
import com.solace.sm.cp.authz.AuthorizationGrantRepository;
import com.solace.sm.cp.authz.ScopePathResolver;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Roles and role bindings (plan 013 T3.1). See {@link AssignmentAdministration} for the contract.
 *
 * <p><b>Order of a grant:</b> shape (the scope is inside the session's organization and below the install), then
 * {@code require(role.assign)} <i>at the granted scope's own node</i> (before any read of a role or a principal and
 * before any write), then the two refusals that no caller may override (the Administrator role; an unknown role), then
 * one transaction: audit context ({@code ROLE_BINDING_GRANTED}, {@code OR-25}), the principal's row lock (so a grant
 * cannot land on a principal being deactivated), its liveness, and the bind. The scope is canonicalized <b>inside that
 * transaction by the repository</b>, never before it: a {@code scope} insert by the runtime role is itself audited and
 * must carry the audit context.
 *
 * <p><b>A requested scope is only the node's address.</b> {@code require} authorizes the deepest node by its id, which
 * says nothing about the ancestors the request claims above it. The node's real path is therefore read back and the
 * request must equal it exactly, so an environment named under the wrong sub-organization is absent (a {@code 404}),
 * never a foreign-key violation and never a scope row the real tree does not have.
 *
 * <p><b>Order of a revoke:</b> read the binding in the session's organization (absent: {@code ResourceNotFoundException}),
 * {@code require(role.assign)} at its own scope, refuse an Administrator binding ({@code 409}; only after the caller is
 * known to be authorized, so an unauthorized caller learns nothing of the rule), then delete in one audited transaction.
 *
 * <p><b>A grantor holds what it grants</b> (W3D-4): once the role is known, the actor must hold every verb of that role
 * at the granted scope, or the grant is {@code 403}.
 *
 * <p>{@code I5}: the organization is an argument of every method and is bound in every statement. {@code I6}: fixed
 * exception messages, no chained driver cause.
 */
public final class AssignmentAdministrationService implements AssignmentAdministration {

    /** W3D-5: {@code ResourceKind} has no assignment kind (and adding one would break the totality of the scope table). */
    private static final String ASSIGNMENT = "role assignment";

    private final AssignmentRepository assignments;
    private final PrincipalAdministrationRepository principals;
    private final AuthorizationService authorization;
    private final ReachAuthorization reach;
    private final AuthorizationGrantRepository grants;
    private final ScopePathResolver paths;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Supplier<UUID> ids;

    public AssignmentAdministrationService(AssignmentRepository assignments, PrincipalAdministrationRepository principals,
            AuthorizationService authorization, ReachAuthorization reach, AuthorizationGrantRepository grants,
            ScopePathResolver paths, AuditContext auditContext, TransactionOperations transactions,
            Supplier<UUID> ids) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.reach = Objects.requireNonNull(reach, "reach");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public List<RoleView> listRoles(PrincipalRef actor, UUID organizationId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(organizationId, "organizationId");
        authorization.require(actor, Permission.ROLE_VIEW,
                ResourceRef.of(ResourceKind.ORGANIZATION, organizationId, new TenantId(organizationId)));
        return assignments.roles();
    }

    @Override
    public List<AssignmentView> list(PrincipalRef actor, UUID organizationId, Optional<UUID> principalId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(principalId, "principalId");
        reach.requireHeldAnywhere(actor, Permission.ROLE_VIEW);

        // Downward reach of the caller's own role.view grants, inside the session's organization (an install-scoped
        // grant reaches every organization): never navigation visibility, so an ancestor of a grant is not shown.
        List<ScopePath> reached = grants.grantsFor(actor).stream()
                .filter(grant -> grant.permissions().contains(Permission.ROLE_VIEW))
                .map(AuthorizationGrant::scope)
                .filter(scope -> scope.organization() == null || scope.organization().equals(organizationId))
                .toList();
        return assignments.list(organizationId, principalId).stream()
                .filter(assignment -> reached.stream().anyMatch(scope -> scope.authorizes(assignment.scope())))
                .toList();
    }

    @Override
    public Optional<AssignmentView> find(UUID organizationId, UUID assignmentId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(assignmentId, "assignmentId");
        return assignments.find(organizationId, assignmentId);
    }

    @Override
    public Granted grant(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID principalId, UUID roleId,
            ScopePath scope) {
        requireArguments(actor, origin, organizationId);
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(scope, "scope");
        if (scope.depth() < 1) {
            throw new IllegalArgumentException("a role is granted at an organization or below");
        }
        if (!organizationId.equals(scope.organization())) {
            // Another organization's node is exactly as absent as a nonexistent one (I5).
            throw new ResourceNotFoundException(ResourceKind.ORGANIZATION);
        }

        ResourceRef node = nodeAt(scope, organizationId);
        // role.assign AT the granted scope: a principal bound at a unit cannot grant at the organization (D-REG 4).
        authorization.require(actor, Permission.ROLE_ASSIGN, node);
        if (!paths.scopePathOf(node).equals(scope)) {
            throw new ResourceNotFoundException(node.kind());
        }
        if (BuiltInRoles.ADMINISTRATOR.equals(roleId)) {
            throw new AssignmentInvalidException(AssignmentInvalidException.Reason.ADMINISTRATOR_NOT_GRANTABLE);
        }
        RoleView role = assignments.findRole(roleId)
                .orElseThrow(() -> new AssignmentInvalidException(AssignmentInvalidException.Reason.UNKNOWN_ROLE));
        requireHoldsEveryVerbOf(actor, role, scope);

        return transactions.execute(status -> {
            audit(actor, origin, AuditOperations.ROLE_BINDING_GRANTED);
            UserView target = principals.lock(organizationId, principalId)
                    .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.PRINCIPAL));
            if (target.status() != UserView.Status.ACTIVE) {
                throw new AssignmentConflictException(AssignmentConflictException.Reason.PRINCIPAL_DEACTIVATED);
            }
            AssignmentRepository.Bound bound = assignments.bind(ids.get(), principalId, roleId, scope);
            return new Granted(bound.assignment(), bound.created());
        });
    }

    /**
     * W3D-4: a grantor may not hand out standing it does not hold. Every verb of the role must be held by the actor at
     * the granted scope (the same prefix rule {@code require} applies). The refusal is {@code 403} naming the first
     * verb the actor lacks; the actor chose the role, so naming one of its verbs reveals nothing it did not ask for.
     * In v1 only the Administrator holds {@code role.assign}, and it holds the whole catalog, so this never refuses
     * anyone today; it keeps a future intermediate role (D-REG {@code OQ-7 (c)}) from escalating by granting Operator.
     */
    private void requireHoldsEveryVerbOf(PrincipalRef actor, RoleView role, ScopePath scope) {
        List<Permission> verbs = role.permissions().stream().map(Permission::byId).toList();
        VerbCoverage.firstMissingAt(grants.grantsFor(actor), scope, verbs).ifPresent(missing -> {
            throw new AccessDeniedException(missing);
        });
    }

    @Override
    public void revoke(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID assignmentId) {
        requireArguments(actor, origin, organizationId);
        Objects.requireNonNull(assignmentId, "assignmentId");
        AssignmentView binding = assignments.find(organizationId, assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException(ASSIGNMENT));

        authorization.require(actor, Permission.ROLE_ASSIGN, bindingNode(binding, organizationId));
        if (BuiltInRoles.ADMINISTRATOR.equals(binding.roleId())) {
            throw new AssignmentConflictException(AssignmentConflictException.Reason.ADMINISTRATOR_BINDING);
        }

        transactions.executeWithoutResult(status -> {
            audit(actor, origin, AuditOperations.ROLE_BINDING_REVOKED);
            if (!assignments.unbind(organizationId, assignmentId)) {
                throw new ResourceNotFoundException(ASSIGNMENT);
            }
        });
    }

    /**
     * The resource {@code role.assign} is checked against for an existing binding: the node its scope names, or - for a
     * binding at the install scope, which no node names - a role definition, whose path is the install scope's.
     */
    private static ResourceRef bindingNode(AssignmentView binding, UUID organizationId) {
        if (binding.scope().depth() == 0) {
            return ResourceRef.of(ResourceKind.ROLE_DEFINITION, binding.roleId(), new TenantId(organizationId));
        }
        return nodeAt(binding.scope(), organizationId);
    }

    /** The deepest node of the scope, by kind and id: the one resource whose own path the authorization resolves. */
    private static ResourceRef nodeAt(ScopePath scope, UUID organizationId) {
        TenantId tenant = new TenantId(organizationId);
        return switch (scope.depth()) {
            case 1 -> ResourceRef.of(ResourceKind.ORGANIZATION, scope.organization(), tenant);
            case 2 -> ResourceRef.of(ResourceKind.SUB_ORGANIZATION, scope.subOrganization(), tenant);
            case 3 -> ResourceRef.of(ResourceKind.ENVIRONMENT, scope.environment(), tenant);
            case 4 -> ResourceRef.of(ResourceKind.SOLUTION, scope.solution(), tenant);
            default -> ResourceRef.of(ResourceKind.INSTANCE, scope.instance(), tenant);
        };
    }

    private void audit(PrincipalRef actor, RequestOrigin origin, String operation) {
        auditContext.set(actor.id(), operation, origin.correlationId(), origin.clientAddress());
    }

    private static void requireArguments(PrincipalRef actor, RequestOrigin origin, UUID organizationId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(organizationId, "organizationId");
    }
}
