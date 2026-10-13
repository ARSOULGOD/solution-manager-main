package com.solace.sm.cp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.cp.api.agent.AgentEnrolmentSweep;
import com.solace.sm.cp.identity.AuditOperations;
import com.solace.sm.cp.identity.SystemActors;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Plan 015 T3.5: the control plane's half of the enrolment sweep. The order is the point: the registration is revoked in a
 * transaction of its own FIRST, so a late replay cannot recreate the data plane half of a target about to be retired, and each
 * write is audited to the SYSTEM ACTOR (OR-140, plan 015 T4.6), never to the principal who issued the token or to anyone else.
 */
class AgentEnrolmentSweepServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private final UUID organization = UUID.randomUUID();
    private final List<String> events = new ArrayList<>();
    private final List<AgentRegistry.Abandonable> rows = new ArrayList<>();

    private final AgentRegistry registry = new AgentRegistry() {
        @Override public void insert(NewRegistration registration) { throw new AssertionError(); }
        @Override public java.util.Optional<Enrollment> lockByTokenDigest(byte[] tokenDigest) { throw new AssertionError(); }
        @Override public void consume(UUID agentId, byte[] csrDigest, UUID targetId,
                AgentCertificateAuthority.IssuedCertificate issued, Instant usedAt) { throw new AssertionError(); }
        @Override public java.util.Optional<Registered> find(UUID organizationId, UUID agentId) { throw new AssertionError(); }
        @Override public java.util.Map<UUID, com.solace.sm.cp.api.agent.AgentRegistration> findAll(
                java.util.Collection<UUID> agentIds) { throw new AssertionError(); }

        @Override
        public List<Abandonable> abandonable(UUID organizationId, Instant expiredBefore, Instant afterAt, UUID afterAgent, int limit) {
            events.add("registry.abandonable:" + organizationId.equals(organization) + ":" + limit + ":" + expiredBefore
                    + ":" + afterAt + ":" + afterAgent);
            return List.copyOf(rows);
        }

        @Override
        public List<UUID> organizationsWithAbandonable(Instant expiredBefore) {
            events.add("registry.organizations:" + expiredBefore);
            return List.of(organization);
        }

        @Override
        public boolean revokeIfActive(UUID organizationId, UUID agentId, Instant at) {
            events.add("registry.revoke:" + organizationId.equals(organization));
            return true;
        }
    };

    private final com.solace.sm.cp.target.TargetRepository targets = new com.solace.sm.cp.target.TargetRepository() {
        @Override public List<com.solace.sm.cp.api.target.TargetView> listLive(UUID o) { throw new AssertionError(); }
        @Override public java.util.Optional<Stored> findByName(UUID o, String n) { throw new AssertionError(); }
        @Override public java.util.Optional<Stored> findById(UUID o, UUID t) { throw new AssertionError(); }
        @Override public void insert(UUID o, UUID t, String n, String k, String tr, com.solace.sm.cp.api.authz.ScopePath s, UUID c,
                Instant a) { throw new AssertionError(); }
        @Override public int markRetired(UUID o, UUID t, Instant a) {
            events.add("target.retire:" + o.equals(organization));
            return 1;
        }
    };

    private final AgentEnrolmentSweepService service = new AgentEnrolmentSweepService(registry, targets,
            (actor, operation, correlation) -> events.add("audit:" + operation + ":" + actor + ":" + correlation),
            new TransactionOperations() {
                @Override
                public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                    events.add("tx.begin");
                    try {
                        return action.doInTransaction(null);
                    } finally {
                        events.add("tx.end");
                    }
                }
            }, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void candidatesAreReadForTheOrganizationAndMapped() {
        UUID agent = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Instant expires = NOW.minusSeconds(3600);
        rows.add(new AgentRegistry.Abandonable(agent, target, expires));

        List<AgentEnrolmentSweep.Candidate> found = service.candidates(organization, java.time.Duration.ofMinutes(10), null, 5);

        assertEquals(List.of(new AgentEnrolmentSweep.Candidate(target, agent, expires)), found);
        assertEquals(List.of("registry.abandonable:true:5:" + NOW.minusSeconds(600) + ":null:null"), events,
                "the cutoff is the CONTROL plane's clock minus the window");
        events.clear();
        service.candidates(organization, java.time.Duration.ofMinutes(10), found.get(0), 5);
        assertEquals(List.of("registry.abandonable:true:5:" + NOW.minusSeconds(600) + ":" + expires + ":" + agent), events,
                "the cursor is the previous page's last (token expiry, agent id)");
        assertThrows(IllegalArgumentException.class, () -> service.candidates(organization, java.time.Duration.ZERO, null, 0));
    }

    @Test
    void organizationsAreThoseWithACandidateOlderThanTheWindowOnTheControlPlanesClock() {
        assertEquals(List.of(organization), service.organizations(java.time.Duration.ofMinutes(10)));
        assertEquals(List.of("registry.organizations:" + NOW.minusSeconds(600)), events);
    }

    @Test
    void revokeAndRetireEachRunUnderTheirOwnOperationAttributedToTheSystemActorNeverToTheIssuer() {
        AgentEnrolmentSweep.Candidate candidate = new AgentEnrolmentSweep.Candidate(UUID.randomUUID(), UUID.randomUUID(), NOW);
        UUID correlation = UUID.randomUUID();

        service.revoke(organization, candidate, correlation);
        service.retire(organization, candidate, correlation);

        assertEquals(List.of(
                "tx.begin", "audit:" + AuditOperations.AGENT_REVOKED + ":" + SystemActors.AGENT_JOBS + ":" + correlation,
                "registry.revoke:true", "tx.end",
                "tx.begin", "audit:" + AuditOperations.TARGET_RETIRED + ":" + SystemActors.AGENT_JOBS + ":" + correlation,
                "target.retire:true", "tx.end"),
                events);
    }
}
