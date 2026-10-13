package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code acceptRuntimeConfig} (plan 010 T10.4). A translator: the protocol is {@link AcceptRuntimeOrchestrator}'s.
 * An {@code instance}-scope CATALOG operation: {@code config.edit} at the instance named by the path has passed in
 * {@link AuthenticationInterceptor} before the body is read ({@link InstanceResolver}); the second verb,
 * {@code config.overrideWarning}, is the orchestrator's first check.
 *
 * <p>It writes a generation to the config of record and NOTHING to a host, and this controller names no runtime adapter.
 */
@RestController
@RequestMapping("/api/dp")
public class AcceptRuntimeController {

    private final AcceptRuntimeOrchestrator accept;

    public AcceptRuntimeController(AcceptRuntimeOrchestrator accept) {
        this.accept = Objects.requireNonNull(accept, "accept");
    }

    @PostMapping("/instances/{instanceId}/config/accept-runtime")
    @ConsoleOperation(value = "acceptRuntimeConfig", resource = InstanceResolver.class)
    public ResponseEntity<AcceptedRuntimeConfig> acceptRuntimeConfig(@PathVariable UUID instanceId,
            @RequestBody AcceptRuntimeRequest request, Session session) {
        return ResponseEntity.ok(accept.accept(session, instanceId, request));
    }
}
