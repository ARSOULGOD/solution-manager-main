package com.solace.sm.gateway.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.agent.AgentAdministration;
import com.solace.sm.cp.api.agent.AgentAdministration.AgentRecord;
import com.solace.sm.cp.api.agent.AgentEnrolment.IssuedToken;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.agw.AgentDrainPort;
import com.solace.sm.dp.api.agent.AgentSessionFacts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The five agent operations (plan 015 T4.3, D10 rows 44-47): {@code issueAgentEnrolment}, {@code listAgents}, {@code getAgent},
 * {@code revokeAgent}, {@code drainAgent}. A translator and a composer: the enrolment rules are the control plane's
 * ({@code AgentEnrolment}, {@code AgentAdministration}), the connection facts the data plane's ({@code AgentSessionFacts}), and the
 * drain is the gateway's, behind {@code AgentDrainPort}.
 *
 * <p><b>Authorization</b> has passed in {@link AuthenticationInterceptor} before any method here runs: {@code agent.enrol} at the
 * path's environment for the issue (its second verb, {@code target.create}, is the control plane's own check inside
 * {@code issue}), {@code agent.view} anywhere for the list (the handler filters, below), and the agent's own environment
 * ({@link AgentResolver}) for the other three.
 *
 * <p><b>{@code I5}.</b> The organization is {@code Session.organizationId()} on every call to either plane. <b>{@code I6}.</b> The
 * token is in {@link #issue}'s answer and nowhere else: no read here can return one because the records they read have no field for it.
 */
@Component
public class AgentOrchestrator {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentOrchestrator.class);
    /** The reason a console-requested drain carries (the gateway validates it as a token). */
    static final String OPERATOR_DRAIN = "operator-drain";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentAdministration agents;
    private final com.solace.sm.cp.api.agent.AgentEnrolment enrolment;
    private final ReachAuthorization reach;
    private final ObjectProvider<AgentSessionFacts> sessionFacts;
    private final ObjectProvider<AgentDrainPort> drainRequester;
    private final RequestOriginFactory origins;
    private final Clock clock;

    @Autowired
    public AgentOrchestrator(AgentAdministration agents, com.solace.sm.cp.api.agent.AgentEnrolment enrolment,
            ReachAuthorization reach, ObjectProvider<AgentSessionFacts> sessionFacts,
            ObjectProvider<AgentDrainPort> drainRequester, RequestOriginFactory origins) {
        this(agents, enrolment, reach, sessionFacts, drainRequester, origins, Clock.systemUTC());
    }

    AgentOrchestrator(AgentAdministration agents, com.solace.sm.cp.api.agent.AgentEnrolment enrolment,
            ReachAuthorization reach, ObjectProvider<AgentSessionFacts> sessionFacts,
            ObjectProvider<AgentDrainPort> drainRequester, RequestOriginFactory origins, Clock clock) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.enrolment = Objects.requireNonNull(enrolment, "enrolment");
        this.reach = Objects.requireNonNull(reach, "reach");
        this.sessionFacts = Objects.requireNonNull(sessionFacts, "sessionFacts");
        this.drainRequester = Objects.requireNonNull(drainRequester, "drainRequester");
        this.origins = Objects.requireNonNull(origins, "origins");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** {@code issueAgentEnrolment}: the token, ONCE. */
    public com.solace.sm.gateway.http.AgentEnrolment issue(Session session, UUID environmentId,
            IssueAgentEnrolmentRequest request) {
        UUID organization = session.organizationId();
        String fault = request.envelopeFault();
        if (fault != null) {
            throw new AgentRefusedException("allowedConfigRoot".equals(fault) ? AgentRefusedException.Reason.CONFIG_ROOT_INVALID
                    : AgentRefusedException.Reason.CONTAINER_PREFIX_INVALID);
        }
        ScopePath environment = agents.environmentPath(organization, environmentId)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.ENVIRONMENT));
        IssuedToken issued;
        try {
            issued = enrolment.issue(session.principal(), organization,
                    new com.solace.sm.cp.api.agent.AgentEnrolment.Request(environment, request.solutionId(),
                            request.packageVersion().toString(), request.targetName().strip(), request.allowedConfigRoot(),
                            request.allowedContainerPrefix(), null),
                    origins.create());
        } catch (IllegalStateException taken) {
            throw new AgentRefusedException(AgentRefusedException.Reason.NAME_TAKEN);
        } catch (IllegalArgumentException invalid) {
            // the request record already holds every grammar; what is left is a solution or version this organization has not loaded
            throw new AgentRefusedException(AgentRefusedException.Reason.SOLUTION_NOT_LOADED);
        }
        return new com.solace.sm.gateway.http.AgentEnrolment(issued.agentId(), issued.token(), issued.expiresAt().toString(),
                environmentId, request.targetName().strip(), request.allowedConfigRoot(), request.allowedContainerPrefix(),
                request.solutionId(), onTheWire(request.packageVersion()));
    }

    /** {@code listAgents}: the organization's agents, then only those whose environment the caller reaches with {@code agent.view}. */
    public AgentList list(Session session) {
        List<AgentRecord> all = agents.list(session.organizationId());
        Set<UUID> reachable = reach.authorizedSubset(session.principal(), Permission.AGENT_VIEW, ResourceKind.ENVIRONMENT,
                all.stream().map(AgentRecord::environmentId).distinct().toList());
        List<AgentRecord> visible = all.stream().filter(agent -> reachable.contains(agent.environmentId())).toList();
        return view(session.organizationId(), visible);
    }

    /** {@code getAgent}. */
    public Agent get(Session session, UUID agentId) {
        AgentRecord record = agents.find(session.organizationId(), agentId)
                .orElseThrow(() -> new ResourceNotFoundException("agent"));
        return view(session.organizationId(), List.of(record)).items().get(0);
    }

    /** {@code revokeAgent}: sticky and idempotent; the data plane closes the session within its reconciliation interval. */
    public Agent revoke(Session session, UUID agentId) {
        RequestOrigin origin = origins.create();
        AgentRecord record = agents.revoke(session.principal(), session.organizationId(), agentId, origin)
                .orElseThrow(() -> new ResourceNotFoundException("agent"));
        LOG.info("agent revoked: agent={} principal={} correlationId={}", agentId, session.principal().id(), origin.correlationId());
        return view(session.organizationId(), List.of(record)).items().get(0);
    }

    /** {@code drainAgent}: {@code AGENT_NOT_CONNECTED} when the gateway holds no live session for it; nothing is queued. */
    public AgentDrainAccepted drain(Session session, UUID agentId) {
        agents.find(session.organizationId(), agentId).orElseThrow(() -> new ResourceNotFoundException("agent"));
        AgentDrainPort requester = drainRequester.getIfAvailable();
        Instant requestedAt = clock.instant();
        // "operator-drain" is the reason carried in the Drain notice sent to the agent (review MINOR 6). It is NOT persisted: the agent_event row
        // is code gateway.drain / detail DRAINING, indistinguishable from a planned-restart drain (W5 review m2, final review NIT).
        if (requester == null || requester.drain(agentId, OPERATOR_DRAIN).isEmpty()) {
            throw new AgentRefusedException(AgentRefusedException.Reason.NOT_CONNECTED);
        }
        LOG.info("agent drain sent: agent={} principal={} correlationId={}", agentId, session.principal().id(), origins.create().correlationId());
        return new AgentDrainAccepted(agentId, requestedAt.toString());
    }

    private AgentList view(UUID organization, Collection<AgentRecord> records) {
        AgentSessionFacts facts = sessionFacts.getIfAvailable();
        Map<UUID, AgentSessionFacts.Session> sessions = facts == null || records.isEmpty() ? Map.of()
                : facts.sessionsOf(organization, records.stream().map(AgentRecord::agentId).collect(Collectors.toList()));
        Availability availability = facts == null
                ? new Availability(AvailabilityState.DEGRADED, null, "agent connection state is not available", null) : null;
        return new AgentList(availability, records.stream().map(record -> agent(record, sessions.get(record.agentId()))).toList());
    }

    private static Agent agent(AgentRecord record, AgentSessionFacts.Session session) {
        return new Agent(null, record.agentId(), record.environmentId(), record.targetId(), record.targetName(),
                Agent.Status.valueOf(record.status().name()), record.allowedConfigRoot(), record.allowedContainerPrefix(),
                record.catalogSolutionId(), onTheWire(parse(record.packageVersion())), record.capabilities(),
                session == null ? null : new AgentSession(
                        session.connected() ? AgentSession.State.CONNECTED : AgentSession.State.DISCONNECTED,
                        session.lastSeenAt().toString(), session.agentVersion(),
                        session.leaseExpiresAt() == null ? null : session.leaseExpiresAt().toString()),
                record.createdAt().toString(), record.enrolledAt() == null ? null : record.enrolledAt().toString(),
                record.revokedAt() == null ? null : record.revokedAt().toString());
    }

    private static JsonNode parse(String canonicalJson) {
        try {
            return JSON.readTree(canonicalJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("a stored package version is not JSON");
        }
    }

    /** A {@code PackageVersionId} on the wire: the integer or the string, as stored. */
    private static Object onTheWire(JsonNode version) {
        return version.isIntegralNumber() ? (Object) version.longValue() : version.textValue();
    }
}
