package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code createConnection}, {@code updateConnection}, {@code deactivateConnection} and {@code deleteConnection}
 * (plan 013 T4, {@code BE-D66}, {@code OR-130}). A translator; the protocol is {@link ConnectionOrchestrator}'s.
 * It sits beside {@link ConnectionController} (the two reads), not in it, mirroring the Target split
 * ({@link TargetController} / {@link TargetManagementController}).
 *
 * <p>Authorization has passed in {@link AuthenticationInterceptor} before any handler here runs:
 * {@code connection.create} at the body's {@code ownershipScope} for the create ({@link OwnershipScopeResolver}),
 * {@code connection.edit}/{@code connection.delete} at the connection's own scope for the other three
 * ({@link ConnectionResolver}).
 *
 * <p>{@code createConnection}/{@code updateConnection} are also {@link CredentialBearing} (fix round 1, item 1):
 * their {@code attributes} may carry a {@code password}/{@code privateKey}/{@code passphrase} value — the same
 * class of secret {@code registerTarget}/{@code rotateTargetCredential} carry — so a channel that is not secure
 * is refused {@code 403 INSECURE_TRANSPORT} before this handler's body is read, exactly as for a Target's key.
 * {@code CredentialBearingRecordsTest}'s structural check does not (yet) look inside a generic
 * {@code attributes: Map<String,Object>} body for a secret-typed value, so it cannot plant-and-detect a missing
 * mark here the way it does for a named {@code privateKey} field — the annotation below is what actually gives
 * the production protection; ⚠️ the detection gap for this shape of body is a known limitation of that test, not
 * of this annotation.
 */
@RestController
@RequestMapping("/api/cp")
public class ConnectionManagementController {

    private final ConnectionOrchestrator connections;

    public ConnectionManagementController(ConnectionOrchestrator connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @PostMapping("/connections")
    @CredentialBearing
    @ConsoleOperation(value = "createConnection", resource = OwnershipScopeResolver.class)
    public ResponseEntity<Connection> createConnection(@RequestBody CreateConnectionRequest request,
            Session session) {
        Connection created = connections.create(session, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/connections/{connectionId}")
    @CredentialBearing
    @ConsoleOperation(value = "updateConnection", resource = ConnectionResolver.class)
    public ResponseEntity<Connection> updateConnection(@PathVariable UUID connectionId,
            @RequestBody UpdateConnectionRequest request, Session session) {
        if (request.name() == null && request.attributes() == null && request.testBeforeSave() == null) {
            // contract: minProperties 1 — an empty body is as meaningless as one naming no change.
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        return ResponseEntity.ok(connections.update(session, connectionId, request));
    }

    @DeleteMapping("/connections/{connectionId}")
    @ConsoleOperation(value = "deleteConnection", resource = ConnectionResolver.class)
    public ResponseEntity<Void> deleteConnection(@PathVariable UUID connectionId, Session session) {
        connections.delete(session, connectionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/connections/{connectionId}/deactivate")
    @ConsoleOperation(value = "deactivateConnection", resource = ConnectionResolver.class)
    public ResponseEntity<Connection> deactivateConnection(@PathVariable UUID connectionId, Session session) {
        return ResponseEntity.ok(connections.deactivate(session, connectionId));
    }
}
