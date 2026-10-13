package com.solace.sm.cp.governance;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.catalog.EntitlementRefusedException;
import com.solace.sm.cp.agent.AgentAuditContext;
import com.solace.sm.cp.api.governance.GovernanceRegistration;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.governance.QuotaExceededException;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Step 4 of the declare protocol ({@code OR-27}): the control plane's governance node for an instance the data
 * plane has reserved. One transaction, audited {@code INSTANCE_DECLARED}; nothing is written unless the whole
 * registration commits.
 *
 * <p>Order inside the transaction is the order that makes the quota binding: the entitlement row is locked
 * {@code FOR UPDATE} <em>first</em>, then instances are counted, then anything is written. Two racing declares of
 * one solution in one organization therefore serialize on that row; the second counts the first's committed
 * instance. The same lock also serializes two first declares of one catalog entry in one environment, so the
 * Solution node ({@code OR-41}) is created once; the partial unique index and {@code ON CONFLICT} remain the
 * backstop, not the mechanism.
 *
 * <p>{@code I5}: the organization is an argument of every method and is bound in every query; the gateway passes
 * the session's own. {@code I6}: no value from a request is placed in an exception message.
 */
public final class GovernanceService implements InstanceGovernance {

    private final GovernanceRepository repository;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Supplier<UUID> ids;
    private final AgentAuditContext agentAuditContext;

    public GovernanceService(GovernanceRepository repository, AuditContext auditContext,
            TransactionOperations transactions, Supplier<UUID> ids) {
        this(repository, auditContext, null, transactions, ids);
    }

    /**
     * @param agentAuditContext sets the agent-actor audit context for {@link #registerForAgent}; {@code null} in a context with no
     *                          agent gateway, whose {@code registerForAgent} then refuses (nothing is written)
     */
    public GovernanceService(GovernanceRepository repository, AuditContext auditContext, AgentAuditContext agentAuditContext,
            TransactionOperations transactions, Supplier<UUID> ids) {
        this.agentAuditContext = agentAuditContext;
        this.repository = Objects.requireNonNull(repository, "repository");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public GovernanceRegistration register(PrincipalRef actor, RequestOrigin origin, UUID organizationId,
            UUID environmentId, UUID catalogSolutionId, UUID instanceId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin");
        return registerNode(() -> auditContext.set(actor.id(), AuditOperations.INSTANCE_DECLARED, origin.correlationId(),
                origin.clientAddress()), organizationId, environmentId, catalogSolutionId, instanceId);
    }

    /**
     * Plan 015 T4.2: the same node, quota and scope rows, under {@code AGENT_INSTANCE_REGISTERED} with the agent as actor and no
     * principal ({@code BE-D70}). The correlation id and client address are the gateway-minted ones of the RPC that reported the
     * container.
     */
    public GovernanceRegistration registerForAgent(UUID agentId, RequestOrigin origin, UUID organizationId,
            UUID environmentId, UUID catalogSolutionId, UUID instanceId) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(origin, "origin");
        if (agentAuditContext == null) {
            throw new IllegalStateException("no agent audit context is wired: an agent cannot register an instance");
        }
        return registerNode(() -> agentAuditContext.setAgent(agentId, AuditOperations.AGENT_INSTANCE_REGISTERED,
                origin.correlationId(), origin.clientAddress()), organizationId, environmentId, catalogSolutionId, instanceId);
    }

    private GovernanceRegistration registerNode(Runnable setAudit, UUID organizationId, UUID environmentId,
            UUID catalogSolutionId, UUID instanceId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(catalogSolutionId, "catalogSolutionId");
        Objects.requireNonNull(instanceId, "instanceId");

        return transactions.execute(status -> {
            setAudit.run();
            // Both lookups bind the organization: another organization's id is "not found", never "forbidden".
            String solution = repository.solutionNameOf(organizationId, catalogSolutionId)
                    .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.SOLUTION_PACKAGE));
            UUID unitId = repository.organizationUnitOf(organizationId, environmentId)
                    .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.ENVIRONMENT));

            // OR-40: lock the entitlement row FIRST. It is the one row every declare of this solution in this
            // organization must pass through, so the count below is read after the previous declare committed.
            OptionalInt max = repository.lockEntitlement(organizationId, solution);
            if (max.isEmpty()) {
                throw new EntitlementRefusedException();
            }
            if (repository.countInstances(organizationId, solution) >= max.getAsInt()) {
                throw new QuotaExceededException();
            }

            // OR-41: the environment's Solution node for this catalog entry, created by the first declare.
            Optional<UUID> existing = repository.findSolutionNode(organizationId, unitId, environmentId,
                    catalogSolutionId);
            UUID solutionId;
            boolean created = false;
            if (existing.isPresent()) {
                solutionId = existing.get();
            } else {
                UUID minted = ids.get();
                if (repository.insertSolutionNode(organizationId, unitId, environmentId, minted, catalogSolutionId)) {
                    solutionId = minted;
                    created = true;
                } else {
                    // Lost an insert race the lock should have prevented; the unique index is the backstop.
                    solutionId = repository.findSolutionNode(organizationId, unitId, environmentId, catalogSolutionId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "solution node insert conflicted but no node exists"));
                }
            }
            repository.insertSolutionScope(ids.get(), organizationId, unitId, environmentId, solutionId);
            repository.insertInstance(organizationId, unitId, environmentId, solutionId, instanceId);
            repository.insertInstanceScope(ids.get(), organizationId, unitId, environmentId, solutionId, instanceId);
            return new GovernanceRegistration(solutionId, created, instanceId);
        });
    }

    @Override
    public Set<UUID> registered(UUID organizationId, Collection<UUID> instanceIds) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(instanceIds, "instanceIds");
        if (instanceIds.size() > MAX_BATCH) {
            throw new IllegalArgumentException("at most " + MAX_BATCH + " instance ids per call");
        }
        return Set.copyOf(repository.registered(organizationId, instanceIds));
    }

    @Override
    public boolean withinQuota(UUID organizationId, String solution) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(solution, "solution");
        OptionalInt max = repository.maxInstances(organizationId, solution);
        return max.isPresent() && repository.countInstances(organizationId, solution) < max.getAsInt();
    }
}
