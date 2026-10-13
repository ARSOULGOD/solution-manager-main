package com.solace.sm.cp.hierarchy;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.catalog.EntitlementAdministration;
import com.solace.sm.cp.api.hierarchy.HierarchyAdministration;
import com.solace.sm.cp.api.hierarchy.HierarchyNameConflictException;
import com.solace.sm.cp.api.hierarchy.ScopeNode;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The scope tree's writes (plan 013 T2.2, {@code BE-D57}). See {@link HierarchyAdministration} for the contract.
 *
 * <p><b>Organization rename is install-scoped ({@code OR-125} (3)).</b> {@link #renameOrganization} requires
 * {@code hierarchy.edit} at the install anchor first, then at the named organization; every other method is authorized at
 * its parent or node as below.
 *
 * <p><b>Order, every method:</b> validate the name's shape (reveals nothing), {@code require(hierarchy.edit)} at the
 * parent or the node (before any read or write, so a caller who may not edit never learns whether a name is taken),
 * then one transaction: audit context ({@code HIERARCHY_NODE_CREATED} or {@code HIERARCHY_NODE_RENAMED}, {@code OR-25}),
 * the write.
 *
 * <p><b>No pre-check of a name ({@code OR-123}).</b> Two names are siblings of one another when PostgreSQL's
 * {@code lower()} under the database's locale says so; that rule lives in three unique indexes and nowhere else. This
 * class writes, and reads a {@code 23505} on those indexes (surfaced by the repository as
 * {@link HierarchyWriteRepository.NameTakenException}, after the transaction rolled back) as one of two answers: the
 * exact name already exists under the parent (an identical replay of a create: that node, {@code created = false}) or
 * it does not (a different spelling of a sibling's name, or a rename onto one: {@link HierarchyNameConflictException}).
 * The replay test is a byte-for-byte SQL equality on the name, never a Java case comparison.
 *
 * <p>{@code I5}: the organization is an argument of every method and is bound in every statement. {@code I6}: no value
 * from a request is placed in an exception message, and no driver exception is chained.
 */
public final class HierarchyAdministrationService implements HierarchyAdministration {

    static final int MAX_NAME_LENGTH = 100;

    /** V015's {@code organization_name_shape} control-character class: Unicode Cc other than the NUL PostgreSQL cannot store. */
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u001f\\u007f-\\u009f]");

    private final HierarchyWriteRepository nodes;
    private final AuthorizationService authorization;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Supplier<UUID> ids;

    public HierarchyAdministrationService(HierarchyWriteRepository nodes, AuthorizationService authorization,
            AuditContext auditContext, TransactionOperations transactions, Supplier<UUID> ids) {
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public Created createSubOrganization(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String name) {
        requireArguments(actor, origin);
        Objects.requireNonNull(organizationId, "organizationId");
        String clean = validName(name);
        authorization.require(actor, Permission.HIERARCHY_EDIT, resource(ResourceKind.ORGANIZATION, organizationId,
                organizationId));

        UUID subOrganizationId = ids.get();
        try {
            transactions.executeWithoutResult(status -> {
                audit(actor, origin, AuditOperations.HIERARCHY_NODE_CREATED);
                nodes.insertSubOrganization(organizationId, subOrganizationId, clean);
            });
            return new Created(new ScopeNode(subOrganizationId, clean), true);
        } catch (HierarchyWriteRepository.NameTakenException taken) {
            return nodes.findSubOrganizationByName(organizationId, clean).map(node -> new Created(node, false))
                    .orElseThrow(() -> new HierarchyNameConflictException(ResourceKind.SUB_ORGANIZATION));
        }
    }

    @Override
    public Created createEnvironment(PrincipalRef actor, RequestOrigin origin, UUID organizationId,
            UUID subOrganizationId, String name) {
        requireArguments(actor, origin);
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(subOrganizationId, "subOrganizationId");
        String clean = validName(name);
        authorization.require(actor, Permission.HIERARCHY_EDIT, resource(ResourceKind.SUB_ORGANIZATION,
                subOrganizationId, organizationId));
        // The authorization above resolved the id; this binds the pair, so a sub-organization of another organization
        // (or a nested one) is exactly as absent as a nonexistent one (I5).
        if (nodes.findSubOrganization(organizationId, subOrganizationId).isEmpty()) {
            throw new ResourceNotFoundException(ResourceKind.SUB_ORGANIZATION);
        }

        UUID environmentId = ids.get();
        try {
            transactions.executeWithoutResult(status -> {
                audit(actor, origin, AuditOperations.HIERARCHY_NODE_CREATED);
                nodes.insertEnvironment(organizationId, subOrganizationId, environmentId, clean);
            });
            return new Created(new ScopeNode(environmentId, clean), true);
        } catch (HierarchyWriteRepository.NameTakenException taken) {
            return nodes.findEnvironmentByName(organizationId, subOrganizationId, clean)
                    .map(node -> new Created(node, false))
                    .orElseThrow(() -> new HierarchyNameConflictException(ResourceKind.ENVIRONMENT));
        }
    }

    @Override
    public ScopeNode renameOrganization(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String name) {
        requireArguments(actor, origin);
        Objects.requireNonNull(organizationId, "organizationId");
        String clean = validName(name);
        // OR-125 (3): an organization is renamed at INSTALL scope only. Two requires, in this order, as entitlement.set
        // does: the install anchor decides who may rename an organization at all (an organization-bound grant is refused
        // here, whatever organization it names, so the install-wide 409 below reaches install principals only and is no
        // cross-tenant oracle); the organization-scoped require then compares the named organization with the caller's
        // own tenant and gives a foreign or nonexistent one the same 404 (I5), before any write.
        authorization.require(actor, Permission.HIERARCHY_EDIT, resource(ResourceKind.ROLE_DEFINITION,
                EntitlementAdministration.INSTALL_ANCHOR_ROLE, organizationId));
        authorization.require(actor, Permission.HIERARCHY_EDIT, resource(ResourceKind.ORGANIZATION, organizationId,
                organizationId));
        return rename(actor, origin, ResourceKind.ORGANIZATION, clean,
                () -> nodes.renameOrganization(organizationId, clean),
                () -> nodes.findOrganization(organizationId),
                organizationId);
    }

    @Override
    public ScopeNode renameSubOrganization(PrincipalRef actor, RequestOrigin origin, UUID organizationId,
            UUID subOrganizationId, String name) {
        requireArguments(actor, origin);
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(subOrganizationId, "subOrganizationId");
        String clean = validName(name);
        authorization.require(actor, Permission.HIERARCHY_EDIT, resource(ResourceKind.SUB_ORGANIZATION,
                subOrganizationId, organizationId));
        return rename(actor, origin, ResourceKind.SUB_ORGANIZATION, clean,
                () -> nodes.renameSubOrganization(organizationId, subOrganizationId, clean),
                () -> nodes.findSubOrganization(organizationId, subOrganizationId),
                subOrganizationId);
    }

    @Override
    public ScopeNode renameEnvironment(PrincipalRef actor, RequestOrigin origin, UUID organizationId,
            UUID subOrganizationId, UUID environmentId, String name) {
        requireArguments(actor, origin);
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(subOrganizationId, "subOrganizationId");
        Objects.requireNonNull(environmentId, "environmentId");
        String clean = validName(name);
        authorization.require(actor, Permission.HIERARCHY_EDIT, resource(ResourceKind.ENVIRONMENT, environmentId,
                organizationId));
        return rename(actor, origin, ResourceKind.ENVIRONMENT, clean,
                () -> nodes.renameEnvironment(organizationId, subOrganizationId, environmentId, clean),
                () -> nodes.findEnvironment(organizationId, subOrganizationId, environmentId),
                environmentId);
    }

    /**
     * One transaction: audit context, then the rename. A rename that changes nothing (the node already has exactly this
     * name) writes nothing and so leaves no audit row; a node that does not exist under the bound parents is absent.
     */
    private ScopeNode rename(PrincipalRef actor, RequestOrigin origin, ResourceKind kind, String name,
            java.util.function.BooleanSupplier write, Supplier<java.util.Optional<ScopeNode>> read, UUID id) {
        try {
            return transactions.execute(status -> {
                audit(actor, origin, AuditOperations.HIERARCHY_NODE_RENAMED);
                if (write.getAsBoolean()) {
                    return new ScopeNode(id, name);
                }
                return read.get().orElseThrow(() -> new ResourceNotFoundException(kind));
            });
        } catch (HierarchyWriteRepository.NameTakenException taken) {
            throw new HierarchyNameConflictException(kind);
        }
    }

    private void audit(PrincipalRef actor, RequestOrigin origin, String operation) {
        auditContext.set(actor.id(), operation, origin.correlationId(), origin.clientAddress());
    }

    private static void requireArguments(PrincipalRef actor, RequestOrigin origin) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin");
    }

    private static ResourceRef resource(ResourceKind kind, UUID id, UUID organizationId) {
        return ResourceRef.of(kind, id, new TenantId(organizationId));
    }

    /**
     * A name is 1 to 100 characters, no control character, not blank, after surrounding whitespace is stripped: the shape
     * V015's CHECK enforces, checked here too so a bad name is a clean {@code IllegalArgumentException} (a {@code 4xx})
     * and not a {@code 23514}. This is shape, never uniqueness.
     */
    static String validName(String name) {
        Objects.requireNonNull(name, "name");
        String stripped = name.strip();
        if (stripped.isEmpty() || stripped.codePointCount(0, stripped.length()) > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("a name is 1 to " + MAX_NAME_LENGTH + " characters");
        }
        if (CONTROL.matcher(stripped).find()) {
            throw new IllegalArgumentException("a name has no control characters");
        }
        return stripped;
    }
}
