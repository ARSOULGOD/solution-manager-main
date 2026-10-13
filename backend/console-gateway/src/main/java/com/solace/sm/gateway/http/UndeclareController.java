package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code undeclareInstance} (plan 012 T5). A translator: the protocol is {@link UndeclareOrchestrator}'s. An
 * {@code instance}-scope CATALOG operation: {@code instance.undeclare} at the instance named by the path has passed in
 * {@link AuthenticationInterceptor} before this runs ({@link InstanceResolver}); a control-plane node already released is
 * "not found" to the authorizer, so a repeated {@code DELETE} is a {@code 404} here, not a second undeclare.
 */
@RestController
@RequestMapping("/api/dp")
public class UndeclareController {

    private final UndeclareOrchestrator orchestrator;
    private final RequestOriginFactory requestOriginFactory;

    public UndeclareController(UndeclareOrchestrator orchestrator, RequestOriginFactory requestOriginFactory) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.requestOriginFactory = Objects.requireNonNull(requestOriginFactory, "requestOriginFactory");
    }

    @DeleteMapping("/instances/{instanceId}")
    @ConsoleOperation(value = "undeclareInstance", resource = InstanceResolver.class)
    public ResponseEntity<InstanceRetirement> undeclareInstance(@PathVariable UUID instanceId,
            @RequestParam(required = false) String workload, Session session) {
        // Undeclare touches no host, and the request must say so: a caller who expected the container to be stopped or
        // removed is told 400 rather than left to find it still running. Absent, empty and any other word are the same refusal.
        if (!PlacementController.LEAVE_RUNNING.equals(workload)) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        return ResponseEntity.ok(orchestrator.undeclare(session, requestOriginFactory.create(), instanceId));
    }
}
