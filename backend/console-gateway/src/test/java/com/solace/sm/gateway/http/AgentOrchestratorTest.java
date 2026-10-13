package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.agent.AgentAdministration;
import com.solace.sm.cp.api.agent.AgentAdministration.AgentRecord;
import com.solace.sm.cp.api.agent.AgentAdministration.Status;
import com.solace.sm.cp.api.agent.AgentEnrolment;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.agw.AgentDrainPort;
import com.solace.sm.dp.api.agent.AgentSessionFacts;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The composition the five agent operations perform, against fakes of the ports they sit on (plan 015 T4.3). */
class AgentOrchestratorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID organization = UUID.randomUUID();
    private final UUID envA = UUID.randomUUID();
    private final UUID envB = UUID.randomUUID();
    private final UUID principal = UUID.randomUUID();
    private final Session session = session(organization);

    private final Map<UUID, AgentRecord> store = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();
    private Set<UUID> reachable = Set.of();
    private Map<UUID, AgentSessionFacts.Session> facts = Map.of();
    private boolean drainAnswers;
    private Duration drainTold = Duration.ofSeconds(2);
    private IllegalRuntimeSwitch issueFailure;

    private enum IllegalRuntimeSwitch { STATE, ARGUMENT }

    private Session session(UUID org) {
        return GatewayTestSupport.newSession(principal, org, false);
    }

    @BeforeEach
    void requestScope() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.setAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE, UUID.randomUUID());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private AgentRecord record(UUID env, Status status, Instant enrolled, Instant revoked) {
        AgentRecord record = new AgentRecord(UUID.randomUUID(), env, enrolled == null ? null : UUID.randomUUID(), "edge-" + store.size(),
                status, "/srv/sm", "sm-", UUID.randomUUID(), "3", List.of("apply-config", "lifecycle"),
                Instant.parse("2026-10-11T10:00:00Z"), enrolled, revoked);
        store.put(record.agentId(), record);
        return record;
    }

    private final AgentAdministration administration = new AgentAdministration() {
        @Override public Optional<ScopePath> environmentPath(UUID org, UUID env) {
            calls.add("path:" + org);
            return org.equals(organization) && (env.equals(envA) || env.equals(envB))
                    ? Optional.of(ScopePath.environment(org, UUID.randomUUID(), env)) : Optional.empty();
        }
        @Override public List<AgentRecord> list(UUID org) {
            return org.equals(organization) ? List.copyOf(store.values()) : List.of();
        }
        @Override public Optional<AgentRecord> find(UUID org, UUID agentId) {
            return org.equals(organization) ? Optional.ofNullable(store.get(agentId)) : Optional.empty();
        }
        @Override public Optional<AgentRecord> revoke(PrincipalRef actor, UUID org, UUID agentId, RequestOrigin origin) {
            calls.add("revoke:" + actor.id() + ":" + org);
            return find(org, agentId).map(old -> {
                AgentRecord revoked = new AgentRecord(old.agentId(), old.environmentId(), old.targetId(), old.targetName(),
                        Status.REVOKED, old.allowedConfigRoot(), old.allowedContainerPrefix(), old.catalogSolutionId(),
                        old.packageVersion(), old.capabilities(), old.createdAt(), old.enrolledAt(), Instant.parse("2026-10-11T12:00:00Z"));
                store.put(agentId, revoked);
                return revoked;
            });
        }
    };

    private final AgentEnrolment enrolment = (actor, org, request, origin) -> {
        calls.add("issue:" + org + ":" + request.environment().environment() + ":" + request.packageVersion());
        if (issueFailure == IllegalRuntimeSwitch.STATE) throw new IllegalStateException("name");
        if (issueFailure == IllegalRuntimeSwitch.ARGUMENT) throw new IllegalArgumentException("not loaded");
        return new AgentEnrolment.IssuedToken(UUID.randomUUID(), "tok-" + UUID.randomUUID(), Instant.parse("2026-10-12T10:00:00Z"));
    };

    private final ReachAuthorization reach = new ReachAuthorization() {
        @Override public void requireHeldAnywhere(PrincipalRef p, Permission permission) { }
        @Override public Set<UUID> authorizedSubset(PrincipalRef p, Permission permission, ResourceKind kind, Collection<UUID> ids) {
            calls.add("reach:" + permission.id() + ":" + kind);
            return ids.stream().filter(reachable::contains).collect(Collectors.toSet());
        }
    };

    private final AgentSessionFacts sessionFacts = (tenant, ids) -> {
        calls.add("facts:" + tenant);
        return facts;
    };

    private final AgentDrainPort drainRequester = (id, reason) -> {
        calls.add("drain:" + id + ":" + reason);
        return drainAnswers ? Optional.of(drainTold) : Optional.empty();
    };

    private static <T> ObjectProvider<T> provided(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return value; }
            @Override public T getObject() { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    private AgentOrchestrator orchestrator(boolean withFacts, boolean withDrain) {
        return new AgentOrchestrator(administration, enrolment, reach, provided(withFacts ? sessionFacts : null),
                provided(withDrain ? drainRequester : null), new RequestOriginFactory(new ClientAddressResolver(new GatewayProperties())),
                java.time.Clock.fixed(Instant.parse("2026-10-11T12:30:00Z"), java.time.ZoneOffset.UTC));
    }

    private IssueAgentEnrolmentRequest request() {
        return new IssueAgentEnrolmentRequest("  edge-1  ", "/srv/sm", "sm-", UUID.randomUUID(), JSON.valueToTree(3));
    }

    // ---- issue ---------------------------------------------------------------------------------------------------------

    @Test
    void issueUsesTheSessionsOrganizationTheStoredEnvironmentPathAndEchoesTheEnvelopeNotTheCallersScope() {
        AgentEnrolment_ out = new AgentEnrolment_(orchestrator(true, true).issue(session, envA, request()));
        assertEquals(List.of("path:" + organization, "issue:" + organization + ":" + envA + ":3"), calls);
        assertEquals(envA, out.value.environmentId());
        assertEquals("edge-1", out.value.targetName(), "the name is stripped as the service strips it");
        assertEquals(3L, out.value.packageVersion());
        assertTrue(out.value.enrollmentToken().startsWith("tok-"));
        assertFalse(out.value.toString().contains(out.value.enrollmentToken()));
    }

    /** Only to keep the wire type and the cp-api type of the same simple name apart in this file. */
    private record AgentEnrolment_(com.solace.sm.gateway.http.AgentEnrolment value) { }

    @Test
    void anEnvironmentOfAnotherOrganizationIsAbsentAndNothingIsIssued() {
        Session foreign = session(UUID.randomUUID());
        assertThrows(ResourceNotFoundException.class, () -> orchestrator(true, true).issue(foreign, envA, request()));
        assertTrue(calls.stream().noneMatch(c -> c.startsWith("issue:")), calls.toString());
    }

    @Test
    void aTakenNameIsAFixedConflictAndAnUnloadedSolutionAFixedValidationFailure() {
        issueFailure = IllegalRuntimeSwitch.STATE;
        AgentRefusedException taken = assertThrows(AgentRefusedException.class, () -> orchestrator(true, true).issue(session, envA, request()));
        assertEquals(ErrorCode.CONFLICT, taken.reason().code());
        issueFailure = IllegalRuntimeSwitch.ARGUMENT;
        AgentRefusedException unloaded = assertThrows(AgentRefusedException.class, () -> orchestrator(true, true).issue(session, envA, request()));
        assertEquals(ErrorCode.VALIDATION_FAILED, unloaded.reason().code());
        assertFalse(unloaded.detail().contains("edge-1"), "nothing the request carried is echoed");
    }

    // ---- reads ---------------------------------------------------------------------------------------------------------

    @Test
    void theListIsFilteredByAgentViewAtEachEnvironmentAndCarriesNoCredentialField() throws Exception {
        AgentRecord a = record(envA, Status.ACTIVE, Instant.parse("2026-10-11T10:30:00Z"), null);
        record(envB, Status.PENDING, null, null);
        reachable = Set.of(envA);
        facts = Map.of(a.agentId(), new AgentSessionFacts.Session(true, Instant.parse("2026-10-11T12:29:00Z"), "0.1.0", Instant.parse("2026-10-11T12:34:00Z")));

        AgentList list = orchestrator(true, true).list(session);

        assertEquals(1, list.items().size());
        Agent agent = list.items().get(0);
        assertEquals(a.agentId(), agent.agentId());
        assertEquals(Agent.Status.ACTIVE, agent.status());
        assertEquals(AgentSession.State.CONNECTED, agent.session().state());
        assertEquals("0.1.0", agent.session().agentVersion());
        assertTrue(calls.contains("reach:agent.view:ENVIRONMENT"), calls.toString());
        String wire = JSON.writeValueAsString(list);
        for (String forbidden : new String[] {"token", "digest", "thumbprint", "certificate", "nonce"}) {
            assertFalse(wire.toLowerCase().contains(forbidden), forbidden + " in " + wire);
        }
        assertNull(list.availability());
    }

    @Test
    void anAgentThatNeverConnectedHasNoSession() {
        AgentRecord pending = record(envA, Status.PENDING, null, null);
        AgentRecord enrolled = record(envA, Status.ACTIVE, Instant.parse("2026-10-11T10:30:00Z"), null);
        AgentRecord revoked = record(envA, Status.REVOKED, Instant.parse("2026-10-11T10:30:00Z"), Instant.parse("2026-10-11T11:00:00Z"));
        reachable = Set.of(envA);
        AgentOrchestrator orchestrator = orchestrator(true, true);
        assertNull(orchestrator.get(session, pending.agentId()).session());
        assertEquals(Agent.Status.ACTIVE, orchestrator.get(session, enrolled.agentId()).status());
        assertEquals(Agent.Status.REVOKED, orchestrator.get(session, revoked.agentId()).status());
    }

    @Test
    void withoutTheDataPlanesFactsTheListIsDegradedNotHidden() {
        record(envA, Status.PENDING, null, null);
        reachable = Set.of(envA);
        AgentList list = orchestrator(false, true).list(session);
        assertEquals(1, list.items().size());
        assertEquals(AvailabilityState.DEGRADED, list.availability().state());
    }

    @Test
    void anotherOrganizationsAgentIsNotFoundOnEveryByIdOperation() {
        AgentRecord a = record(envA, Status.ACTIVE, Instant.parse("2026-10-11T10:30:00Z"), null);
        Session foreign = session(UUID.randomUUID());
        AgentOrchestrator orchestrator = orchestrator(true, true);
        assertThrows(ResourceNotFoundException.class, () -> orchestrator.get(foreign, a.agentId()));
        assertThrows(ResourceNotFoundException.class, () -> orchestrator.revoke(foreign, a.agentId()));
        assertThrows(ResourceNotFoundException.class, () -> orchestrator.drain(foreign, a.agentId()));
        assertTrue(calls.stream().noneMatch(c -> c.startsWith("drain:")), "a foreign agent is never drained");
        assertEquals(Status.ACTIVE, store.get(a.agentId()).status(), "nor revoked");
    }

    // ---- revoke and drain ----------------------------------------------------------------------------------------------

    @Test
    void revokeNamesThePrincipalAndTheSessionsOrganizationAndAnswersTheRevokedAgent() {
        AgentRecord a = record(envA, Status.ACTIVE, Instant.parse("2026-10-11T10:30:00Z"), null);
        Agent out = orchestrator(true, true).revoke(session, a.agentId());
        assertEquals(Agent.Status.REVOKED, out.status());
        assertTrue(calls.contains("revoke:" + principal + ":" + organization), calls.toString());
    }

    @Test
    void drainSendsToTheGatewayAndAnswers202ShapeOrRefusesWithAgentNotConnectedAndQueuesNothing() {
        AgentRecord a = record(envA, Status.ACTIVE, Instant.parse("2026-10-11T10:30:00Z"), null);
        drainAnswers = true;
        AgentDrainAccepted accepted = orchestrator(true, true).drain(session, a.agentId());
        assertEquals(a.agentId(), accepted.agentId());
        assertEquals("2026-10-11T12:30:00Z", accepted.requestedAt());

        drainAnswers = false;
        AgentRefusedException none = assertThrows(AgentRefusedException.class, () -> orchestrator(true, true).drain(session, a.agentId()));
        assertEquals(ErrorCode.AGENT_NOT_CONNECTED, none.reason().code());
        // no gateway composed at all (a dp-remote-only install): the same refusal, never a 500
        AgentRefusedException noGateway = assertThrows(AgentRefusedException.class, () -> orchestrator(true, false).drain(session, a.agentId()));
        assertEquals(ErrorCode.AGENT_NOT_CONNECTED, noGateway.reason().code());
        assertSame(none.reason(), noGateway.reason());
    }
}
