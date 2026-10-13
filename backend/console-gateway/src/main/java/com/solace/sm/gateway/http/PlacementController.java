package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code addPlacement} and {@code retirePlacement} (plan 008 S3). A translator: the protocol is
 * {@link PlacementOrchestrator}'s. Both are {@code instance}-scope CATALOG operations: {@code instance.bindTarget} at the
 * instance named by the path has passed in {@link AuthenticationInterceptor} before the body is read
 * ({@link InstanceResolver}); {@code addPlacement}'s second verb ({@code target.use}, at a target only the BODY names) is the
 * orchestrator's.
 */
@RestController
@RequestMapping("/api/dp")
public class PlacementController {

    /** The one value {@code ?workload=} takes: the request must say what happens to the container (nothing). */
    static final String LEAVE_RUNNING = "leave-running";

    private final PlacementOrchestrator orchestrator;
    private final RequestOriginFactory requestOriginFactory;

    public PlacementController(PlacementOrchestrator orchestrator, RequestOriginFactory requestOriginFactory) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.requestOriginFactory = Objects.requireNonNull(requestOriginFactory, "requestOriginFactory");
    }

    @PostMapping("/instances/{instanceId}/placements")
    @ConsoleOperation(value = "addPlacement", resource = InstanceResolver.class)
    public ResponseEntity<Instance> addPlacement(@PathVariable UUID instanceId,
            @RequestBody AddPlacementRequest request, Session session) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(InstanceController.toWire(orchestrator.add(session, instanceId, request)));
    }

    @DeleteMapping("/instances/{instanceId}/placements/{placementId}")
    @ConsoleOperation(value = "retirePlacement", resource = InstanceResolver.class)
    public ResponseEntity<PlacementRetirement> retirePlacement(@PathVariable UUID instanceId,
            @PathVariable UUID placementId, @RequestParam(required = false) String workload, Session session) {
        // Retiring touches no host, and the request must say so: a caller who expected the container to be removed is told
        // 400 rather than left to find it still running. Absent, empty and any other word are the same refusal.
        if (!LEAVE_RUNNING.equals(workload)) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        return ResponseEntity.ok(orchestrator.retire(session, requestOriginFactory.create(), instanceId, placementId));
    }
}
