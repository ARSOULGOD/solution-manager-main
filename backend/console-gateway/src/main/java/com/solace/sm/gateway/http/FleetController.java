package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code submitFleetApply} and {@code observeFleetApply} (plan 010 T11.2, {@code REQ-013}; {@code fleet-apply.md}
 * section 7). A translator: the protocol is {@link FleetOrchestrator}'s.
 *
 * <p>Both are {@code x-sm-scope: reach} operations: no path resource can authorize a fleet whose members span
 * environments, so the interceptor checks only that the verb is held at one or more grants in the caller's organization and
 * resolves NO resource - which is why neither declares a {@link ConsoleOperation#resource()}. That pre-check authorizes
 * NO member: the orchestrator {@code require}s each at its own instance (submit) or filters them (observe).
 *
 * <p>{@code 202} is returned when the fleet is recorded and durable, before any request exists: the scheduler admits batch
 * 0 on its next pass. A {@code Location} points at the observation to poll.
 *
 * <p>⛔ This controller never constructs or calls a runtime adapter, and imports none (plan 010 T6.7).
 */
@RestController
@RequestMapping("/api/dp")
public class FleetController {

    private final FleetOrchestrator orchestrator;

    public FleetController(FleetOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    @PostMapping("/fleet-applies")
    @ConsoleOperation("submitFleetApply")
    public ResponseEntity<FleetAccepted> submitFleetApply(@RequestBody FleetApplyRequest request, Session session,
            HttpServletRequest http) {
        Object correlation = http.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        FleetAccepted accepted = orchestrator.submit(session, correlation instanceof UUID id ? id : UUID.randomUUID(),
                request);
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, "/api/dp/fleet-applies/" + accepted.fleetId())
                .body(accepted);
    }

    @GetMapping("/fleet-applies/{fleetId}")
    @ConsoleOperation("observeFleetApply")
    public ResponseEntity<FleetObservation> observeFleetApply(@PathVariable UUID fleetId, Session session) {
        return ResponseEntity.ok(orchestrator.observe(session, fleetId));
    }
}
