package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
 * {@code declareInstance}, {@code listInstances} and {@code getInstance} (plan 010 T4.4). A translator: the protocol
 * is {@link DeclareOrchestrator}'s (BE-D32), the wire shapes are this module's.
 *
 * <ul>
 *   <li>{@code declareInstance} is an {@code environment}-scope CATALOG operation: {@code instance.create} at the
 *       path's environment has passed in {@link AuthenticationInterceptor} before the body is read
 *       ({@link EnvironmentResolver}).</li>
 *   <li>{@code getInstance} is an {@code instance}-scope CATALOG operation ({@link InstanceResolver}).</li>
 *   <li>{@code listInstances} is {@code x-sm-scope: reach}: the interceptor passed {@code instance.view} held at one
 *       or more grants, which authorizes NO item; the orchestrator filters through
 *       {@code ReachAuthorization#authorizedSubset} with the operation's own verb.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/dp")
public class InstanceController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeclareOrchestrator orchestrator;
    private final RequestOriginFactory requestOriginFactory;

    public InstanceController(DeclareOrchestrator orchestrator, RequestOriginFactory requestOriginFactory) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.requestOriginFactory = Objects.requireNonNull(requestOriginFactory, "requestOriginFactory");
    }

    @GetMapping("/solutions/{solutionId}/instances")
    @ConsoleOperation("listInstances")
    public ResponseEntity<InstanceList> listInstances(@PathVariable UUID solutionId, Session session) {
        List<Instance> items = orchestrator.list(session, solutionId).stream().map(InstanceController::toWire)
                .toList();
        return ResponseEntity.ok(new InstanceList(null, items));
    }

    @PostMapping("/environments/{environmentId}/instances")
    @ConsoleOperation(value = "declareInstance", resource = EnvironmentResolver.class)
    public ResponseEntity<Instance> declareInstance(@PathVariable UUID environmentId,
            @RequestBody DeclareInstanceRequest request, Session session) {
        InstanceView declared = orchestrator.declare(session, requestOriginFactory.create(), environmentId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toWire(declared));
    }

    @GetMapping("/instances/{instanceId}")
    @ConsoleOperation(value = "getInstance", resource = InstanceResolver.class)
    public ResponseEntity<Instance> getInstance(@PathVariable UUID instanceId, Session session) {
        // Authorized as a registered instance; only an ACTIVE one is readable, so a pending, abandoned or unknown row
        // is the same 404 as an out-of-scope one.
        InstanceView view = orchestrator.get(session, instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));
        return ResponseEntity.ok(toWire(view));
    }

    static Instance toWire(InstanceView view) {
        List<InstancePlacement> placements = view.placements().stream().map(InstanceController::toWire).toList();
        return new Instance(view.stateVersion(), null, view.instanceId(), view.name(), view.catalogSolutionId(),
                view.environmentId(), packageVersionOnTheWire(view.declaredPackageVersion()),
                new Acquisition(view.acquisition().wire()), placements, view.role());
    }

    private static InstancePlacement toWire(PlacementView placement) {
        return new InstancePlacement(placement.placementId(), placement.targetId(), placement.containerName(),
                placement.probeStrength().map(s -> s.wire()).orElse("none"), placement.connectionId());
    }

    /**
     * The data plane stores the declared version as the canonical JSON text the catalog matched ({@code 1},
     * {@code "v2"}); a {@code PackageVersionId} is an integer or a string on the wire. A value that parses as neither
     * (a row seeded by hand) is returned as its text rather than failing the read.
     */
    public static Object packageVersionOnTheWire(String stored) {
        try {
            JsonNode node = MAPPER.readTree(stored);
            if (node != null && node.isIntegralNumber() && node.canConvertToLong()) {
                return node.longValue();
            }
            if (node != null && node.isTextual()) {
                return node.textValue();
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // not JSON: fall through to the raw text
        }
        return stored;
    }
}
