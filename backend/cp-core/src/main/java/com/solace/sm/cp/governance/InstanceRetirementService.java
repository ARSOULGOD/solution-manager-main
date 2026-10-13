package com.solace.sm.cp.governance;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.governance.InstanceRetirementGovernance;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import java.time.Clock;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Plan 012 T4: retires an instance's governance node. One transaction, audited {@code INSTANCE_UNDECLARED}: the
 * audit context is set first, so the {@code OR-25} trigger finds the actor, the operation, the correlation id and the
 * client address for the one {@code UPDATE}; nothing is written when the node is already retired (the repository
 * returns {@code false} and the row, and the audit trail, are left exactly as they were).
 *
 * <p>{@code I5}: the organization is an argument of every method and is bound in every query. {@code I6}: no value
 * from a request is placed in an exception message.
 */
public final class InstanceRetirementService implements InstanceRetirementGovernance {

    private final InstanceRetirementRepository repository;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Clock clock;

    public InstanceRetirementService(InstanceRetirementRepository repository, AuditContext auditContext,
            TransactionOperations transactions, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean deregister(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID instanceId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(instanceId, "instanceId");

        return transactions.execute(status -> {
            auditContext.set(actor.id(), AuditOperations.INSTANCE_UNDECLARED, origin.correlationId(),
                    origin.clientAddress());
            if (repository.retire(organizationId, instanceId, actor.id(), clock.instant())) {
                return true;
            }
            // Not written: either already retired (idempotent) or not this organization's. Another organization's
            // id is "not found", never "forbidden" - the lookup binds the organization.
            if (!repository.exists(organizationId, instanceId)) {
                throw new ResourceNotFoundException(ResourceKind.INSTANCE);
            }
            return false;
        });
    }

    @Override
    public Set<UUID> retired(UUID organizationId, Collection<UUID> instanceIds) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(instanceIds, "instanceIds");
        if (instanceIds.size() > MAX_BATCH) {
            throw new IllegalArgumentException("at most " + MAX_BATCH + " instance ids per call");
        }
        return Set.copyOf(repository.retired(organizationId, instanceIds));
    }
}
