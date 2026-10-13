package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.RegistrationOutcome;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The registration API's target operations (plan 013 T1.4; D-REG section 5.1 T1 to T3): {@code scanHostKeys},
 * {@code registerTarget} and {@code getTargetConnection}. A translator; the protocol is
 * {@link TargetRegistrationOrchestrator}'s. It sits beside {@link TargetController} (the {@code listTargets} read), not
 * in it, so that class and its constructor stay what its tests pin.
 *
 * <p>Authorization has passed in {@link AuthenticationInterceptor} before any handler here runs: {@code target.create} at
 * the body's {@code ownershipScope} for the first two ({@link OwnershipScopeResolver}), {@code target.edit} at the
 * target's own scope for the third ({@link TargetResolver}). {@code registerTarget} is also {@link CredentialBearing}: a
 * channel that is not secure was refused {@code 403 INSECURE_TRANSPORT} before this handler's body was read.
 *
 * <p>⛔ {@code I6}: the key reaches the orchestrator inside the request record, whose {@code toString} is redacted, and is
 * turned into a {@code char[]} there and zeroised in a {@code finally}. (The request's own {@code String} is the JDK's to
 * reclaim; no code here can scrub it.)
 */
@RestController
@RequestMapping("/api")
public class TargetRegistrationController {

    private final TargetRegistrationOrchestrator registrations;

    public TargetRegistrationController(TargetRegistrationOrchestrator registrations) {
        this.registrations = Objects.requireNonNull(registrations, "registrations");
    }

    @PostMapping("/dp/host-key-scans")
    @ConsoleOperation(value = "scanHostKeys", resource = OwnershipScopeResolver.class)
    public ResponseEntity<HostKeyScan> scanHostKeys(@RequestBody HostKeyScanRequest request, Session session) {
        return ResponseEntity.ok(registrations.scan(session, request));
    }

    @PostMapping("/cp/targets")
    @CredentialBearing
    @ConsoleOperation(value = "registerTarget", resource = OwnershipScopeResolver.class)
    public ResponseEntity<TargetDetail> registerTarget(@RequestBody RegisterTargetRequest request, Session session) {
        TargetRegistrationOrchestrator.Registered registered = registrations.register(session, request);
        HttpStatus status = registered.outcome() == RegistrationOutcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(registered.detail());
    }

    @PatchMapping("/cp/targets/{targetId}")
    @ConsoleOperation(value = "editTarget", resource = TargetResolver.class)
    public ResponseEntity<TargetDetail> editTarget(@PathVariable UUID targetId,
            @RequestBody UpdateDeploymentTargetRequest request, Session session) {
        return ResponseEntity.ok(registrations.updateLinked(session, targetId, request));
    }

    @GetMapping("/dp/targets/{targetId}/connection")
    @ConsoleOperation(value = "getTargetConnection", resource = TargetResolver.class)
    public ResponseEntity<TargetConnection> getTargetConnection(@PathVariable UUID targetId, Session session) {
        return ResponseEntity.ok(registrations.connectionOf(session, targetId));
    }
}
