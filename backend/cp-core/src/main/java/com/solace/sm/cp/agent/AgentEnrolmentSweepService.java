package com.solace.sm.cp.agent;

import com.solace.sm.cp.api.agent.AgentEnrolmentSweep;
import com.solace.sm.cp.identity.AuditOperations;
import com.solace.sm.cp.identity.SystemActors;
import com.solace.sm.cp.identity.SystemAuditContext;
import com.solace.sm.cp.target.TargetRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * {@link AgentEnrolmentSweep} over the registry and the target repository (plan 015 T3.5, T4.6). Both control-plane writes are
 * audited to the SYSTEM ACTOR ({@code OR-140}, {@code V022}); the attribution rule is on the port.
 *
 * <p>Order (the caller's): the registration is revoked FIRST, in a transaction of its own, so a later replay of the token, which the
 * registry refuses for a revoked registration ({@code lockByTokenDigest} reads only ACTIVE rows), cannot recreate the data plane
 * half; the caller then looks at the data plane again and retires the target only if it is still absent. A crash between them
 * leaves a revoked registration and a live target, which is still a candidate and is finished by the next call.
 */
public final class AgentEnrolmentSweepService implements AgentEnrolmentSweep {

    private final AgentRegistry registry;
    private final TargetRepository targets;
    private final SystemAuditContext auditContext;
    private final TransactionOperations transactions;
    private final Clock clock;

    public AgentEnrolmentSweepService(AgentRegistry registry, TargetRepository targets, SystemAuditContext auditContext,
            TransactionOperations transactions, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<UUID> organizations(Duration olderThan) {
        Objects.requireNonNull(olderThan, "olderThan");
        return registry.organizationsWithAbandonable(clock.instant().minus(olderThan));
    }

    @Override
    public List<Candidate> candidates(UUID organizationId, Duration olderThan, Candidate after, int limit) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(olderThan, "olderThan");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return registry.abandonable(organizationId, clock.instant().minus(olderThan),
                after == null ? null : after.tokenExpiresAt(), after == null ? null : after.agentId(), limit).stream()
                .map(a -> new Candidate(a.targetId(), a.agentId(), a.tokenExpiresAt())).toList();
    }

    @Override
    public void revoke(UUID organizationId, Candidate candidate, UUID correlationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(correlationId, "correlationId");
        transactions.executeWithoutResult(status -> {
            auditContext.setSystem(SystemActors.AGENT_JOBS, AuditOperations.AGENT_REVOKED, correlationId);
            registry.revokeIfActive(organizationId, candidate.agentId(), clock.instant());
        });
    }

    @Override
    public void retire(UUID organizationId, Candidate candidate, UUID correlationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(correlationId, "correlationId");
        transactions.executeWithoutResult(status -> {
            auditContext.setSystem(SystemActors.AGENT_JOBS, AuditOperations.TARGET_RETIRED, correlationId);
            targets.markRetired(organizationId, candidate.targetId(), clock.instant());
        });
    }
}
