package com.solace.sm.cp.hierarchy;

import com.solace.sm.cp.api.hierarchy.HierarchyNameConflictException;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The operator command's rename of ANY organization ({@code OR-131}, which keeps the HTTP rename install-scoped and
 * adds this). The authority is the runtime credential on the host, as {@code EntitlementMain}'s is; there is no
 * authorization call and no session. Name rules are the API's own ({@link HierarchyAdministrationService#validName}),
 * and uniqueness is the same {@code organization_name_ci_uq} index (ignoring case, {@code OR-123}): this class
 * pre-checks nothing, it reads the {@code 23505}.
 *
 * <p><b>Audited with no actor, in the {@code EntitlementMain} pattern</b> ({@code OR-131} Q1; supersedes the earlier named-actor
 * form). {@code sm_control} V020's trigger admits a {@link AuditOperations#HIERARCHY_NODE_RENAMED} write with no audit actor
 * on {@code organization} only, and only from the client address {@code operator-cli}; over HTTP the rename names its
 * authenticated principal and a no-actor write is refused by the database. The row records client address
 * {@code operator-cli} and no actor, which is what tells it from an HTTP rename.
 */
public final class OrganizationRenameService {

    private final HierarchyWriteRepository nodes;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;

    public OrganizationRenameService(HierarchyWriteRepository nodes, AuditContext auditContext, TransactionOperations transactions) {
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /**
     * @return the stored (stripped) name
     * @throws IllegalArgumentException                                  the name's shape is invalid
     * @throws com.solace.sm.cp.api.authz.ResourceNotFoundException      the organization does not exist
     * @throws HierarchyNameConflictException                            another organization has the name, ignoring case
     */
    public String rename(UUID organizationId, String name, UUID correlationId, String clientAddress) {
        Objects.requireNonNull(organizationId, "organizationId");
        String clean = HierarchyAdministrationService.validName(name);
        try {
            transactions.executeWithoutResult(status -> {
                auditContext.set(null, AuditOperations.HIERARCHY_NODE_RENAMED, correlationId, clientAddress);
                if (!nodes.renameOrganization(organizationId, clean)
                        && nodes.findOrganization(organizationId).isEmpty()) {
                    throw new com.solace.sm.cp.api.authz.ResourceNotFoundException(ResourceKind.ORGANIZATION);
                }
            });
        } catch (HierarchyWriteRepository.NameTakenException taken) {
            throw new HierarchyNameConflictException(ResourceKind.ORGANIZATION);
        }
        return clean;
    }
}
