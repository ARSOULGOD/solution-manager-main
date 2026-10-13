package com.solace.sm.cp.agent;

import com.solace.sm.cp.api.agent.AgentAdministration;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.authz.ResourceScopeDirectory;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * {@link AgentAdministration} over the registry (plan 015 T4.3). Reads are bound by the organization they are given (I5).
 *
 * <p><b>Revocation</b> requires {@code agent.revoke} at the agent's environment, then is one transaction: the audit context ({@code AGENT_REVOKED}, the principal as actor, OR-25), then the
 * registry's conditional update, which raises {@code agent_revocation_epoch} and sets {@code revoked_at} only on an ACTIVE row.
 * The data plane learns of it at its next reconciliation and closes the agent's session within that interval (AGW-D19); nothing
 * here reaches the data plane (I7). Revoking a revoked registration updates nothing, so it audits nothing.
 */
public final class AgentAdministrationService implements AgentAdministration {

    private final AgentRegistry registry;
    private final ResourceScopeDirectory scopeDirectory;
    private final AuthorizationService authorization;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Clock clock;

    public AgentAdministrationService(AgentRegistry registry, ResourceScopeDirectory scopeDirectory,
            AuthorizationService authorization, AuditContext auditContext, TransactionOperations transactions, Clock clock) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.scopeDirectory = Objects.requireNonNull(scopeDirectory, "scopeDirectory");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<ScopePath> environmentPath(UUID organizationId, UUID environmentId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(environmentId, "environmentId");
        return scopeDirectory.findScopePath(ResourceRef.of(ResourceKind.ENVIRONMENT, environmentId, new TenantId(organizationId)))
                .filter(path -> organizationId.equals(path.organization()) && path.depth() == 3);
    }

    @Override
    public List<AgentRecord> list(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Instant now = clock.instant();
        return registry.list(organizationId).stream().map(row -> record(row, now)).toList();
    }

    @Override
    public Optional<AgentRecord> find(UUID organizationId, UUID agentId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(agentId, "agentId");
        Instant now = clock.instant();
        return registry.detail(organizationId, agentId).map(row -> record(row, now));
    }

    @Override
    public Optional<AgentRecord> revoke(PrincipalRef actor, UUID organizationId, UUID agentId, RequestOrigin origin) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(origin, "origin");
        AgentRegistry.Detail detail = registry.detail(organizationId, agentId).orElse(null);
        if (detail == null) {
            return Optional.empty();
        }
        // HIGH: the HTTP interceptor has already required this, and this service requires it again so no other caller (a CLI, a bulk
        // revoke) can revoke without it, as AgentEnrolmentService re-requires its two verbs (review MINOR 5).
        authorization.require(actor, Permission.AGENT_REVOKE,
                ResourceRef.of(ResourceKind.ENVIRONMENT, detail.environmentId(), new TenantId(organizationId)));
        transactions.executeWithoutResult(status -> {
            auditContext.set(actor.id(), AuditOperations.AGENT_REVOKED, origin.correlationId(), origin.clientAddress());
            registry.revokeIfActive(organizationId, agentId, clock.instant());
        });
        return find(organizationId, agentId);
    }

    private static AgentRecord record(AgentRegistry.Detail row, Instant now) {
        Status status;
        if ("revoked".equals(row.status())) {
            status = Status.REVOKED;
        } else if (row.enrolledAt() != null) {
            status = Status.ACTIVE;
        } else {
            status = row.expiresAt().isAfter(now) ? Status.PENDING : Status.EXPIRED;
        }
        return new AgentRecord(row.agentId(), row.environmentId(), row.targetId(), row.targetName(), status,
                row.allowedConfigRoot(), row.allowedContainerPrefix(), row.catalogSolutionId(), row.packageVersion(),
                row.capabilities(), row.createdAt(), row.enrolledAt(), row.revokedAt());
    }
}
