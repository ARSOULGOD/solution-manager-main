package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The registration API's operations on an existing target (plan 013 T1.5; D-REG section 5.1 T4 to T7):
 * {@code editTargetConnection}, {@code rotateTargetCredential}, {@code testTargetConnection} and {@code retireTarget}. A
 * translator; the protocol is {@link TargetManagementOrchestrator}'s. It sits beside {@link TargetRegistrationController}
 * (the registration's three), not in it, so that class and the test that pins its credential-bearing handlers stay what
 * they are.
 *
 * <p>Authorization has passed in {@link AuthenticationInterceptor} before any handler here runs, at the target named by
 * the path ({@link TargetResolver}; a target of another organization, or none, is {@code 404}). {@code rotateTargetCredential}
 * is also {@link CredentialBearing}: a channel that is not secure was refused {@code 403 INSECURE_TRANSPORT} before this
 * handler's body was read.
 *
 * <p>⛔ {@code I6}: the key reaches the orchestrator inside a request record whose {@code toString} is redacted, and is
 * turned into a {@code char[]} there and zeroised in a {@code finally}.
 */
@RestController
@RequestMapping("/api")
public class TargetManagementController {

    private final TargetManagementOrchestrator management;

    public TargetManagementController(TargetManagementOrchestrator management) {
        this.management = Objects.requireNonNull(management, "management");
    }

    @PatchMapping("/dp/targets/{targetId}/connection")
    @ConsoleOperation(value = "editTargetConnection", resource = TargetResolver.class)
    public ResponseEntity<TargetConnection> editTargetConnection(@PathVariable UUID targetId,
            @RequestBody EditTargetConnectionRequest request, Session session) {
        return ResponseEntity.ok(management.edit(session, targetId, request));
    }

    @PutMapping("/dp/targets/{targetId}/credential")
    @CredentialBearing
    @ConsoleOperation(value = "rotateTargetCredential", resource = TargetResolver.class)
    public ResponseEntity<RotatedCredential> rotateTargetCredential(@PathVariable UUID targetId,
            @RequestBody RotateTargetCredentialRequest request, Session session) {
        return ResponseEntity.ok(management.rotate(session, targetId, request));
    }

    @PostMapping("/dp/targets/{targetId}/connection/test")
    @ConsoleOperation(value = "testTargetConnection", resource = TargetResolver.class)
    public ResponseEntity<ConnectionTestResult> testTargetConnection(@PathVariable UUID targetId, Session session) {
        return ResponseEntity.ok(management.test(session, targetId));
    }

    @PostMapping("/cp/targets/{targetId}/retire")
    @ConsoleOperation(value = "retireTarget", resource = TargetResolver.class)
    public ResponseEntity<Target> retireTarget(@PathVariable UUID targetId, Session session) {
        return ResponseEntity.ok(management.retire(session, targetId));
    }
}
