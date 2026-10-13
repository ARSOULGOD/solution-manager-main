package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code listConnections} (OR-130; plan 013 T4) and {@code getConnectionTypes} ({@code UI-D27}). Mirrors
 * {@link TargetController}'s split from the write operations ({@link ConnectionManagementController}).
 *
 * <p>{@code listConnections} is {@code x-sm-scope: reach}, shaped exactly like {@code listTargets}:
 * {@link AuthenticationInterceptor} has already checked {@code connection.view} held at one or more grants in the
 * caller's organization; this handler owns the per-item filter ({@link ConnectionOrchestrator#list}).
 * {@code getConnectionTypes} is authentication-only, install scope — identical for every caller, so there is
 * nothing here to authorize per item.
 */
@RestController
@RequestMapping("/api/cp")
public class ConnectionController {

    private final ConnectionOrchestrator connections;

    public ConnectionController(ConnectionOrchestrator connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @GetMapping("/connections")
    @ConsoleOperation("listConnections")
    public ResponseEntity<ConnectionList> listConnections(Session session) {
        return ResponseEntity.ok(connections.list(session));
    }

    @GetMapping("/connection-types")
    @ConsoleOperation("getConnectionTypes")
    public ResponseEntity<List<ConnectionType>> getConnectionTypes() {
        return ResponseEntity.ok(connections.connectionTypes());
    }
}
