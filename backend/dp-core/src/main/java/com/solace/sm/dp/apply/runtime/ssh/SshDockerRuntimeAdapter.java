package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.LifecycleResult;
import com.solace.sm.dp.apply.runtime.Liveness;
import com.solace.sm.dp.apply.runtime.MaterializeResult;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RestoreResult;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Docker runtime adapter over a pinned-host-key SSH/SFTP connection.
 *
 * <p>Only this package knows SSH. The engine sees ordinary {@link RuntimeAdapter} operations and
 * runtime references; browser and controller layers have neither SSH commands nor credentials.
 */
public final class SshDockerRuntimeAdapter implements RuntimeAdapter {

    private static final String PREIMAGE = ".sm-preimage-";
    private static final String ABSENT = ".absent";

    private final SshDockerPlacementCatalog catalog;
    private final SshDockerTransport transport;

    public SshDockerRuntimeAdapter(SshDockerPlacementCatalog catalog) {
        this(catalog, new SshjDockerTransport());
    }

    SshDockerRuntimeAdapter(SshDockerPlacementCatalog catalog, SshDockerTransport transport) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
    }

    /**
     * {@code BE-D25}. An SSH placement is pinned to exactly one container, so the replica set is
     * the placement's own replica and reading it needs no round trip — the identity comes from the
     * catalog, not from the remote host.
     */
    @Override
    public java.util.List<TargetReplica> replicas(PlacementRef placement) {
        return java.util.List.of(replica(placement, catalog.lookup(placement)));
    }

    @Override
    public PreflightResult prepare(PlacementRef placement, long generation) {
        SshDockerPlacement p = catalog.lookup(placement);
        try {
            SshDockerTransport.ContainerSnapshot snapshot = transport.inspect(p);
            transport.verifyConfigDirectoryWritable(p);
            TargetReplica replica = replica(placement, p);
            boolean healthDeclared = !"not_declared".equals(snapshot.health());
            return new PreflightResult.Ready(java.util.List.of(replica), p.location(), healthDeclared,
                    placementRef(p));
        } catch (RuntimeAdapterException e) {
            return new PreflightResult.Refused(e.errorClass(), placementRef(p));
        }
    }

    @Override
    public MaterializeResult materializeConfig(TargetReplica replica, long generation,
            RenderedConfig config) {
        SshDockerPlacement p = catalog.lookup(replica.placement());
        String target = p.configFile();
        capturePreImage(p, generation, target);
        transport.writeFileAtomically(p, target, config.bytes());
        String actual = hashOf(transport.readFile(p, target)
                .orElseThrow(() -> failure("ssh.config.readback.absent")));
        if (!config.hash().equals(actual)) {
            throw failure("ssh.materialize.hash.mismatch");
        }
        return new MaterializeResult(actual, replicaRef(p, transport.inspect(p)));
    }

    @Override
    public LifecycleResult startOrUpdate(TargetReplica replica, long generation) {
        SshDockerPlacement p = catalog.lookup(replica.placement());
        boolean issued = transport.restart(p);
        return new LifecycleResult(issued, replicaRef(p, transport.inspect(p)));
    }

    @Override
    public LifecycleResult stop(TargetReplica replica) {
        SshDockerPlacement p = catalog.lookup(replica.placement());
        boolean issued = transport.stop(p);
        return new LifecycleResult(issued, replicaRef(p, transport.inspect(p)));
    }

    @Override
    public RuntimeStatus status(TargetReplica replica) {
        SshDockerPlacement p = catalog.lookup(replica.placement());
        SshDockerTransport.StatusSnapshot statusSnapshot = transport.statusSnapshot(p);
        SshDockerTransport.ContainerSnapshot snapshot = statusSnapshot.container();
        Liveness liveness = snapshot.running() ? Liveness.RUNNING : Liveness.STOPPED;
        HealthSignal health = health(snapshot.health(), snapshot.running());
        String observedHash = statusSnapshot.configHash();
        PreBaseline baseline = snapshot.running() ? baseline(snapshot.startedAt(), replica) : null;
        return new RuntimeStatus(liveness, health, observedHash, baseline, replicaRef(p, snapshot));
    }

    @Override
    public RollbackAssessment assessRollback(PlacementRef placement, long generation,
            String intendedHash) {
        SshDockerPlacement p = catalog.lookup(placement);
        Optional<byte[]> prior = transport.readFile(p, preimagePath(p, generation));
        if (prior.isEmpty()) {
            if (transport.readFile(p, absencePath(p, generation)).isPresent()) {
                return new RollbackAssessment.Unknowable(new ErrorClass("rollback.target.absent"));
            }
            return new RollbackAssessment.NothingToRestore();
        }
        String targetHash = hashOf(prior.get());
        RuntimeStatus current;
        try {
            current = status(replica(placement, p));
        } catch (RuntimeAdapterException e) {
            return new RollbackAssessment.Unknowable(new ErrorClass("rollback.state.unreadable"));
        }
        if (current.observedHash() == null) {
            return new RollbackAssessment.Unknowable(new ErrorClass("rollback.state.unreadable"));
        }
        if (!targetHash.equals(current.observedHash()) && !intendedHash.equals(current.observedHash())) {
            return new RollbackAssessment.Unknowable(new ErrorClass("rollback.state.unknown"));
        }
        return new RollbackAssessment.Restorable(targetHash);
    }

    @Override
    public RestoreResult restore(PlacementRef placement, long generation, String targetHash) {
        SshDockerPlacement p = catalog.lookup(placement);
        byte[] prior = transport.readFile(p, preimagePath(p, generation))
                .orElseThrow(() -> failure("rollback.preimage.vanished"));
        if (!targetHash.equals(hashOf(prior))) {
            throw failure("rollback.preimage.altered");
        }
        transport.writeFileAtomically(p, p.configFile(), prior);
        return new RestoreResult(placementRef(p));
    }

    private void capturePreImage(SshDockerPlacement p, long generation, String target) {
        String preimage = preimagePath(p, generation);
        if (transport.readFile(p, preimage).isPresent()
                || transport.readFile(p, absencePath(p, generation)).isPresent()) {
            return;
        }
        Optional<byte[]> current = transport.readFile(p, target);
        if (current.isPresent()) {
            transport.writeFileAtomically(p, preimage, current.get());
        } else {
            transport.writeFileAtomically(p, absencePath(p, generation), new byte[0]);
        }
    }

    private static TargetReplica replica(PlacementRef placement, SshDockerPlacement p) {
        return new TargetReplica(placement, new ReplicaRef(p.containerName()));
    }

    private static String preimagePath(SshDockerPlacement p, long generation) {
        return p.remoteConfigDirectory() + "/." + p.fileName() + PREIMAGE + generation;
    }

    private static String absencePath(SshDockerPlacement p, long generation) {
        return preimagePath(p, generation) + ABSENT;
    }

    private static String hashOf(byte[] content) {
        return RenderedConfig.of(content).hash();
    }

    private static HealthSignal health(String raw, boolean running) {
        if ("not_declared".equals(raw)) {
            return HealthSignal.NOT_DECLARED;
        }
        if (!running) {
            return HealthSignal.UNHEALTHY;
        }
        return switch (raw) {
            case "healthy" -> HealthSignal.HEALTHY;
            case "starting" -> HealthSignal.STARTING;
            default -> HealthSignal.UNHEALTHY;
        };
    }

    private static PreBaseline baseline(String startedAt, TargetReplica replica) {
        if (startedAt == null || startedAt.isBlank() || startedAt.startsWith("0001-01-01")) {
            return null;
        }
        try {
            return new PreBaseline(Instant.parse(startedAt), replica.ref().value());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static RuntimeRef placementRef(SshDockerPlacement p) {
        return RuntimeRef.of(RuntimeRef.Key.HOST, p.host(), RuntimeRef.Key.MOUNT_PATH,
                p.containerConfigPath());
    }

    private static RuntimeRef replicaRef(SshDockerPlacement p, SshDockerTransport.ContainerSnapshot s) {
        return RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, s.containerId(), RuntimeRef.Key.HOST, p.host(),
                RuntimeRef.Key.MOUNT_PATH, p.containerConfigPath(), RuntimeRef.Key.IMAGE_DIGEST, s.imageId());
    }

    private static RuntimeAdapterException failure(String token) {
        return new RuntimeAdapterException(new ErrorClass(token));
    }
}
