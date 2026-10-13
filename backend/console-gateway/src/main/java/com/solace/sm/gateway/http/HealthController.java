package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.health.HealthService;
import com.solace.sm.dp.api.health.HealthView;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code getHealth} (plan 010 T7.2): an {@code instance}-scope CATALOG operation authorized by {@code health.view} at
 * the path's instance ({@link InstanceResolver}) before this handler runs.
 *
 * <p>It reads what the data plane's poller PERSISTED and touches no host, so a request never waits on SSH and a host
 * outage cannot slow the console. The instance is looked up through {@link DeclareOrchestrator#get}, so it is visible
 * exactly when {@code getInstance} shows it; anything else is the same {@code 404} as out of scope. The organization
 * is {@code Session.organizationId()} on the data-plane call, never a request field (I5).
 */
@RestController
@RequestMapping("/api/dp")
public class HealthController {

    private final DeclareOrchestrator instances;
    private final HealthService health;

    public HealthController(DeclareOrchestrator instances, HealthService health) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.health = Objects.requireNonNull(health, "health");
    }

    @GetMapping("/instances/{instanceId}/health")
    @ConsoleOperation(value = "getHealth", resource = InstanceResolver.class)
    public ResponseEntity<Health> getHealth(@PathVariable UUID instanceId, Session session) {
        instances.get(session, instanceId).orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));
        HealthView view = health.health(session.organizationId(), instanceId);
        Availability availability = view.unpolledPlacements() == 0 ? null
                : new Availability(AvailabilityState.DEGRADED, AvailabilityMode.POLLING,
                        view.unpolledPlacements() + " placement(s) have not been polled yet", null);
        return ResponseEntity.ok(new Health(availability, view.placements().stream()
                .map(p -> new Health.PlacementHealth(p.placementId(), p.state().wire(), p.checkedAt().toString(),
                        p.stateVersion()))
                .toList()));
    }
}
