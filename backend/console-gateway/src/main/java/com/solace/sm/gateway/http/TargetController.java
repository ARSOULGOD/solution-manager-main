package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.target.TargetDirectory;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.dp.api.inventory.TargetConnectionService;
import com.solace.sm.dp.api.inventory.TargetEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code listTargets} (OR-28; plan 010 T2.7): the one target read, an {@code x-sm-scope: reach} operation.
 *
 * <p><b>Two checks, and the handler owns the second.</b> {@link AuthenticationInterceptor} has already passed
 * {@code target.view} held at one or more grants in the session's organization (a {@link OperationClassification.Reach}
 * operation resolves no resource). That is a pre-check, not an authorization of any item: this handler MUST filter the
 * organization's targets through {@link ReachAuthorization#authorizedSubset} with the operation's OWN verb, so a target
 * the caller does not reach at its {@code ownershipScope} is simply absent (downward reach, S0 point 22). The filter is
 * never {@code ScopeVisibility} (cp-core, either direction, and barred to the gateway).
 *
 * <p><b>Composition.</b> The governance rows come from the control plane ({@link TargetDirectory}); the immutable
 * envelope (B2) from the data plane ({@link TargetConnectionService#liveEnvelopes}). A target the control plane knows
 * and the data plane holds no live connection record for is listed, without its envelope, and the list's
 * {@code availability} says so: degraded is a statement about the data, never a reason to hide a row.
 */
@RestController
@RequestMapping("/api/cp")
public class TargetController {

    private final TargetDirectory targetDirectory;
    private final ReachAuthorization reachAuthorization;
    private final TargetConnectionService connections;

    public TargetController(TargetDirectory targetDirectory, ReachAuthorization reachAuthorization,
            TargetConnectionService connections) {
        this.targetDirectory = Objects.requireNonNull(targetDirectory, "targetDirectory");
        this.reachAuthorization = Objects.requireNonNull(reachAuthorization, "reachAuthorization");
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @GetMapping("/targets")
    @ConsoleOperation("listTargets")
    public ResponseEntity<TargetList> listTargets(Session session) {
        // OR-140 (plan 015 T4.6): a reach-level GET writes nothing to the control plane. The interrupted-enrolment sweep that
        // used to run here is the scheduled AgentEnrolmentSweepJob, audited to the system actor.
        // I5: the organization is the session's own, never a request field.
        List<TargetView> live = targetDirectory.listLive(session.organizationId());
        Set<UUID> reachable = reachAuthorization.authorizedSubset(session.principal(), Permission.TARGET_VIEW,
                ResourceKind.DEPLOYMENT_TARGET, live.stream().map(TargetView::targetId).toList());
        List<TargetView> visible = live.stream().filter(target -> reachable.contains(target.targetId())).toList();

        Map<UUID, TargetEnvelope> envelopes = connections.liveEnvelopes(session.organizationId(),
                visible.stream().map(TargetView::targetId).toList());
        List<Target> items = new ArrayList<>(visible.size());
        int withoutConnection = 0;
        for (TargetView view : visible) {
            TargetEnvelope envelope = envelopes.get(view.targetId());
            if (envelope == null) {
                withoutConnection++;
            }
            items.add(new Target(view.targetId(), view.name(), TargetKind.fromWire(view.kind()),
                    targetTransport(view.transport()), toWire(view.ownershipScope()),
                    envelope == null ? null : envelope.allowedConfigRoot(),
                    envelope == null ? null : envelope.allowedContainerPrefix(), null, view.connectionId(),
                    view.runtime(), view.runtimeSettingsJson()));
        }
        Availability availability = withoutConnection == 0 ? null
                : new Availability(AvailabilityState.DEGRADED, null,
                        "no connection record for " + withoutConnection + " of " + items.size() + " target"
                                + (items.size() == 1 ? "" : "s"), null);
        return ResponseEntity.ok(new TargetList(availability, items));
    }

    private static TargetTransport targetTransport(String wire) {
        return TargetTransport.fromWire(wire);
    }

    private static ScopePath toWire(com.solace.sm.cp.api.authz.ScopePath path) {
        return new ScopePath(path.organization(), path.subOrganization(), path.environment(), path.solution(),
                path.instance());
    }
}
