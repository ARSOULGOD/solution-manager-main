package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The agent operations (plan 015 T4.3; D10 rows 44-47): {@code issueAgentEnrolment}, {@code listAgents}, {@code getAgent},
 * {@code revokeAgent}, {@code drainAgent}. A translator; the protocol is {@link AgentOrchestrator}'s.
 *
 * <p>Authorization has passed in {@link AuthenticationInterceptor} before any handler here runs: {@code agent.enrol} at the path's
 * environment ({@link EnvironmentResolver}) for the issue, a reach pre-check for the list (the orchestrator filters), and
 * the agent's own environment ({@link AgentResolver}; another organization's agent, or none, is {@code 404}) for the rest.
 * {@code issueAgentEnrolment} is {@link CredentialBearing} although its REQUEST holds no secret: its RESPONSE does, so a channel that is
 * not secure is refused {@code 403 INSECURE_TRANSPORT} before the body is read ({@code OQ-14 (b)}).
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private final AgentOrchestrator agents;

    public AgentController(AgentOrchestrator agents) {
        this.agents = Objects.requireNonNull(agents, "agents");
    }

    @PostMapping("/cp/environments/{environmentId}/agent-enrolments")
    @CredentialBearing
    @ConsoleOperation(value = "issueAgentEnrolment", resource = EnvironmentResolver.class)
    public ResponseEntity<AgentEnrolment> issueAgentEnrolment(@PathVariable UUID environmentId,
            @RequestBody IssueAgentEnrolmentRequest request, Session session) {
        return ResponseEntity.status(HttpStatus.CREATED).body(agents.issue(session, environmentId, request));
    }

    @GetMapping("/cp/agents")
    @ConsoleOperation("listAgents")
    public ResponseEntity<AgentList> listAgents(Session session) {
        return ResponseEntity.ok(agents.list(session));
    }

    @GetMapping("/cp/agents/{agentId}")
    @ConsoleOperation(value = "getAgent", resource = AgentResolver.class)
    public ResponseEntity<Agent> getAgent(@PathVariable UUID agentId, Session session) {
        return ResponseEntity.ok(agents.get(session, agentId));
    }

    @PostMapping("/cp/agents/{agentId}/revoke")
    @ConsoleOperation(value = "revokeAgent", resource = AgentResolver.class)
    public ResponseEntity<Agent> revokeAgent(@PathVariable UUID agentId, Session session) {
        return ResponseEntity.ok(agents.revoke(session, agentId));
    }

    @PostMapping("/dp/agents/{agentId}/drain")
    @ConsoleOperation(value = "drainAgent", resource = AgentResolver.class)
    public ResponseEntity<AgentDrainAccepted> drainAgent(@PathVariable UUID agentId, Session session) {
        return ResponseEntity.accepted().body(agents.drain(session, agentId));
    }
}
