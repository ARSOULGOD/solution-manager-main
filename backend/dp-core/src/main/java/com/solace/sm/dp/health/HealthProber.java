package com.solace.sm.dp.health;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.DirectHttpProbe;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacementCatalog;
import com.solace.sm.dp.inventory.InstanceRecord;
import com.solace.sm.dp.inventory.PlacementRecord;
import com.solace.sm.dp.inventory.TargetConnectionRecord;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Takes ONE observation of ONE placement, in the way its INSTANCE acquires health (owner rulings M-Q2 and M-Q2b,
 * {@code OR-71}): one setting for health and metrics, and no automatic fallback.
 *
 * <ul>
 *   <li>{@code via-ssh}: {@link HostProbe} on the host's loopback, over the pooled session (T7.1). The binding is
 *       looked up fresh, so a rotated credential or a corrected pin is used by the very next probe.</li>
 *   <li>{@code direct-http} ({@code OR-82}): an HTTP GET from the data plane to {@code target_connection.host} at the
 *       placement's health port and path. The host is read fresh with the placement's target on each round, so a
 *       re-target follows the new target's address with nothing carried forward; nothing about it comes from a browser
 *       (B2). A target with no live record has no usable address and reads unreachable.</li>
 * </ul>
 *
 * <p>A direct failure is never retried over SSH and an SSH failure is never retried direct. The apply engine's own
 * health gate is untouched (it stays on {@code docker inspect}, OR-39).
 */
public final class HealthProber {

    private final HostProbe ssh;
    private final SshDockerPlacementCatalog catalog;
    private final TargetConnectionRows targets;
    private final DirectHttpProbe direct;
    private final Duration timeout;

    public HealthProber(HostProbe ssh, SshDockerPlacementCatalog catalog, TargetConnectionRows targets,
            DirectHttpProbe direct, Duration timeout) {
        this.ssh = Objects.requireNonNull(ssh, "ssh");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.direct = Objects.requireNonNull(direct, "direct");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** @param placement a placement that declares a probe (the caller handles one that does not) */
    public Observation observe(InstanceRecord instance, PlacementRecord placement) {
        int port = placement.healthPort().orElseThrow();
        String path = placement.healthPath().orElseThrow();
        return switch (instance.acquisition()) {
            case VIA_SSH -> overSsh(placement, port, path);
            case DIRECT_HTTP -> overHttp(placement, port, path);
            case AGENT -> Observation.DEFERRED;           // the agent reports it (plan 015 T3.3); this poller says nothing
        };
    }

    private Observation overSsh(PlacementRecord placement, int port, String path) {
        try {
            SshDockerPlacement binding = catalog.lookup(new PlacementRef(new TenantId(placement.tenantId()),
                    new InstanceId(placement.instanceId()), new PlacementId(placement.placementId())));
            HostProbe.ProbeResult result = ssh.probe(binding, port, path, timeout);
            return switch (result.exitCode()) {
                case 0 -> new Observation.Answered(result.httpStatus(), result.body());
                case 7 -> Observation.REFUSED;                  // curl: connection refused on the host's loopback
                default -> Observation.SILENT;                  // 28 (timed out), 52, 56, ...: reached, no usable answer
            };
        } catch (RuntimeAdapterException e) {
            return switch (e.errorClass().token()) {
                // a load signal or a row that moved under us: no observation, so nothing is recorded this round
                case "ssh.pool.busy", "ssh.pool.closed", "placement.unresolvable" -> Observation.DEFERRED;
                // an agent placement is health-reported by its agent (plan 015 T3.3/T3.5): this poller observes nothing about it,
                // and must not write a verdict that races the agent's
                case "agent.operation.unsupported" -> Observation.DEFERRED;
                // the declared probe is outside its grammar: the host is fine, the declaration is the problem
                case "ssh.probe.refused" -> Observation.REFUSED;
                // every SSH-level failure: connect, host-key pin, credential, a connection lost twice, a dead exec
                default -> Observation.UNREACHABLE;
            };
        }
    }

    private Observation overHttp(PlacementRecord placement, int port, String path) {
        if (placement.connectionBacked()) {
            // Plan 014 step 3 (not built): the address will come from the connection's runtime projection. Until then a
            // connection-backed placement has no usable address here, which reads unreachable, as a target with none does.
            return Observation.UNREACHABLE;
        }
        Optional<TargetConnectionRecord> target = targets.find(placement.tenantId(), placement.targetId())
                .filter(t -> t.retiredAt() == null);
        if (target.isEmpty() || target.get().host() == null) {       // an agent target has no registered address (V028)
            return Observation.UNREACHABLE;
        }
        return DirectHttpObservations.observe(direct, target.get().host(), port, path, timeout);
    }
}
