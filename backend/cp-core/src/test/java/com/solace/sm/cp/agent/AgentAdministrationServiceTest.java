package com.solace.sm.cp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.api.agent.AgentAdministration.AgentRecord;
import com.solace.sm.cp.api.agent.AgentAdministration.Status;
import com.solace.sm.cp.api.agent.AgentRegistration;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.authz.ResourceScopeDirectory;
import com.solace.sm.cp.identity.AuditOperations;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** Plan 015 T4.3: the operator reads (status against the clock, bound by organization) and the audited revoke. */
class AgentAdministrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private final UUID organization = UUID.randomUUID();
    private final UUID unit = UUID.randomUUID();
    private final UUID environment = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final List<String> events = new ArrayList<>();
    private final Map<UUID, AgentRegistry.Detail> rows = new LinkedHashMap<>();

    private final AgentRegistry registry = new AgentRegistry() {
        @Override public void insert(NewRegistration registration) { throw new AssertionError(); }
        @Override public Optional<Enrollment> lockByTokenDigest(byte[] tokenDigest) { throw new AssertionError(); }
        @Override public void consume(UUID agentId, byte[] csrDigest, UUID targetId,
                AgentCertificateAuthority.IssuedCertificate issued, Instant usedAt) { throw new AssertionError(); }
        @Override public Optional<Registered> find(UUID organizationId, UUID agentId) { throw new AssertionError(); }
        @Override public Map<UUID, AgentRegistration> findAll(Collection<UUID> agentIds) { throw new AssertionError(); }

        @Override public List<Detail> list(UUID organizationId) {
            events.add("registry.list:" + organizationId.equals(organization));
            return organizationId.equals(organization) ? List.copyOf(rows.values()) : List.of();
        }

        @Override public Optional<Detail> detail(UUID organizationId, UUID agentId) {
            return organizationId.equals(organization) ? Optional.ofNullable(rows.get(agentId)) : Optional.empty();
        }

        @Override public boolean revokeIfActive(UUID organizationId, UUID agentId, Instant at) {
            events.add("registry.revoke:" + organizationId.equals(organization));
            Detail old = rows.get(agentId);
            if (old == null || "revoked".equals(old.status())) return false;
            rows.put(agentId, new Detail(old.agentId(), old.organizationUnitId(), old.environmentId(), old.targetId(), old.targetName(),
                    "revoked", old.allowedConfigRoot(), old.allowedContainerPrefix(), old.catalogSolutionId(), old.packageVersion(),
                    old.capabilities(), old.createdAt(), old.expiresAt(), old.enrolledAt(), at));
            return true;
        }
    };

    private final ResourceScopeDirectory directory = new ResourceScopeDirectory() {
        @Override public Optional<ScopePath> findScopePath(ResourceRef resource) {
            return resource.id().equals(environment) && resource.tenant().value().equals(organization)
                    ? Optional.of(ScopePath.environment(organization, unit, environment)) : Optional.empty();
        }
    };

    private final List<String> required = new ArrayList<>();
    private boolean deny;
    private final com.solace.sm.cp.api.authz.AuthorizationService authorization = (principal, permission, resource) -> {
        required.add(permission.id() + "@" + resource.kind() + ":" + resource.id() + ":" + resource.tenant().value());
        if (deny) throw new com.solace.sm.cp.api.authz.AccessDeniedException(permission);
    };

    private final AgentAdministrationService service = new AgentAdministrationService(registry, directory, authorization,
            (a, operation, correlation, address) -> events.add("audit:" + operation + ":" + a + ":" + address),
            new TransactionOperations() {
                @Override public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                    events.add("tx.begin");
                    try { return action.doInTransaction(null); } finally { events.add("tx.end"); }
                }
            }, Clock.fixed(NOW, ZoneOffset.UTC));

    private AgentRegistry.Detail row(String status, Instant expires, Instant enrolled, Instant revoked) {
        AgentRegistry.Detail detail = new AgentRegistry.Detail(UUID.randomUUID(), unit, environment, enrolled == null ? null : UUID.randomUUID(),
                "edge", status, "/srv/sm", "sm-", UUID.randomUUID(), "2", List.of("apply-config", "lifecycle"),
                NOW.minusSeconds(7200), expires, enrolled, revoked);
        rows.put(detail.agentId(), detail);
        return detail;
    }

    @Test
    void theStatusIsTheLifecycleReadAgainstTheControlPlanesClock() {
        AgentRegistry.Detail pending = row("active", NOW.plusSeconds(60), null, null);
        AgentRegistry.Detail expired = row("active", NOW.minusSeconds(1), null, null);
        AgentRegistry.Detail enrolledPastExpiry = row("active", NOW.minusSeconds(3600), NOW.minusSeconds(5000), null);
        AgentRegistry.Detail revoked = row("revoked", NOW.plusSeconds(60), null, NOW.minusSeconds(5));
        assertEquals(Status.PENDING, service.find(organization, pending.agentId()).orElseThrow().status());
        assertEquals(Status.EXPIRED, service.find(organization, expired.agentId()).orElseThrow().status());
        assertEquals(Status.ACTIVE, service.find(organization, enrolledPastExpiry.agentId()).orElseThrow().status(),
                "the token's expiry means nothing once it is consumed");
        assertEquals(Status.REVOKED, service.find(organization, revoked.agentId()).orElseThrow().status());
        assertEquals(4, service.list(organization).size());
    }

    @Test
    void anotherOrganizationSeesNothingAndCannotRevoke() {
        AgentRegistry.Detail a = row("active", NOW.plusSeconds(60), NOW.minusSeconds(60), null);
        UUID foreign = UUID.randomUUID();
        assertTrue(service.find(foreign, a.agentId()).isEmpty());
        assertTrue(service.list(foreign).isEmpty());
        assertTrue(service.revoke(new PrincipalRef(actor), foreign, a.agentId(), new RequestOrigin("203.0.113.9", UUID.randomUUID())).isEmpty());
        assertFalse(events.stream().anyMatch(e -> e.startsWith("audit:") || e.startsWith("tx.")), events.toString());
        assertEquals("active", rows.get(a.agentId()).status());
    }

    @Test
    void revokeRunsInOneTransactionAuditedAsAgentRevokedWithThePrincipalAndIsIdempotent() {
        AgentRegistry.Detail a = row("active", NOW.plusSeconds(60), NOW.minusSeconds(60), null);
        RequestOrigin origin = new RequestOrigin("203.0.113.9", UUID.randomUUID());

        AgentRecord revoked = service.revoke(new PrincipalRef(actor), organization, a.agentId(), origin).orElseThrow();

        assertEquals(Status.REVOKED, revoked.status());
        assertEquals(NOW, revoked.revokedAt());
        assertEquals(List.of("tx.begin", "audit:" + AuditOperations.AGENT_REVOKED + ":" + actor + ":203.0.113.9",
                "registry.revoke:true", "tx.end"), events, "the audit context is set before the write, in the same transaction");

        events.clear();
        AgentRecord again = service.revoke(new PrincipalRef(actor), organization, a.agentId(), origin).orElseThrow();
        assertEquals(Status.REVOKED, again.status());
        assertEquals(NOW, again.revokedAt(), "revoked_at is set once and never moves");
    }

    @Test
    void revokeRequiresAgentRevokeAtTheAgentsEnvironmentItselfAndADeniedCallerChangesAndAuditsNothing() {
        AgentRegistry.Detail a = row("active", NOW.plusSeconds(60), NOW.minusSeconds(60), null);
        RequestOrigin origin = new RequestOrigin("203.0.113.9", UUID.randomUUID());
        service.revoke(new PrincipalRef(actor), organization, a.agentId(), origin);
        assertEquals(List.of("agent.revoke@ENVIRONMENT:" + environment + ":" + organization), required);

        AgentRegistry.Detail b = row("active", NOW.plusSeconds(60), NOW.minusSeconds(60), null);
        events.clear();
        deny = true;
        org.junit.jupiter.api.Assertions.assertThrows(com.solace.sm.cp.api.authz.AccessDeniedException.class,
                () -> service.revoke(new PrincipalRef(actor), organization, b.agentId(), origin));
        assertEquals("active", rows.get(b.agentId()).status(), "a denied revoke changed nothing");
        assertFalse(events.stream().anyMatch(e -> e.startsWith("audit:") || e.startsWith("tx.") || e.startsWith("registry.revoke")),
                events.toString());
    }

    @Test
    void anUnusedTokenCanBeRevokedWhichReleasesItsName() {
        AgentRegistry.Detail a = row("active", NOW.plusSeconds(60), null, null);
        AgentRecord revoked = service.revoke(new PrincipalRef(actor), organization, a.agentId(),
                new RequestOrigin("203.0.113.9", UUID.randomUUID())).orElseThrow();
        assertEquals(Status.REVOKED, revoked.status());
        assertEquals(null, revoked.enrolledAt());
    }

    @Test
    void theEnvironmentPathIsTheStoredOneAndBoundByOrganizationAndDepth() {
        assertEquals(Optional.of(ScopePath.environment(organization, unit, environment)), service.environmentPath(organization, environment));
        assertTrue(service.environmentPath(UUID.randomUUID(), environment).isEmpty(), "another organization's environment is absent");
        assertTrue(service.environmentPath(organization, UUID.randomUUID()).isEmpty());
    }
}
