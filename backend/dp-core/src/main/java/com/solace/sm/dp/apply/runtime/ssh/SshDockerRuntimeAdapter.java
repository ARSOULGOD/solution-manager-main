package com.solace.sm.dp.apply.runtime.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.inventory.StrictTargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetAddressProperties;
import com.solace.sm.dp.apply.runtime.ConfigReadBack;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.LifecycleResult;
import com.solace.sm.dp.apply.runtime.Liveness;
import com.solace.sm.dp.apply.runtime.MaterializeResult;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreImageFingerprint;
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
    private static final String FINGERPRINT = ".sm-fp-";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One health read's bound: well inside the SSH operation timeout (15 s) and {@link HostProbe#MAX_TIMEOUT_SECONDS}. */
    static final java.time.Duration PROBE_TIMEOUT = java.time.Duration.ofSeconds(5);

    private final SshDockerPlacementCatalog catalog;
    private final SshDockerTransport transport;
    private final DirectHttpProbe direct;
    private final ConfigHasher hasher;

    /** Production: the transport and the direct probe both check {@code addressPolicy} on the stored host, per use. */
    public SshDockerRuntimeAdapter(SshDockerPlacementCatalog catalog, SshCredentialSource credentials, ConfigHasher hasher,
            TargetAddressPolicy addressPolicy) {
        this(catalog, credentials, hasher, addressPolicy, HostKeyTypeRecorder.NONE);
    }

    /** As above, and a pinned key's type discovered on a connection is reported to {@code recorder} (OR-125 (2)). */
    public SshDockerRuntimeAdapter(SshDockerPlacementCatalog catalog, SshCredentialSource credentials, ConfigHasher hasher,
            TargetAddressPolicy addressPolicy, HostKeyTypeRecorder recorder) {
        this(catalog, new SshjDockerTransport(credentials, addressPolicy, recorder), DirectHttpProbe.jdk(addressPolicy),
                hasher);
    }

    /** For a test with its own transport: the direct probe, if ever reached, is the strict one. */
    SshDockerRuntimeAdapter(SshDockerPlacementCatalog catalog, SshDockerTransport transport,
            ConfigHasher hasher) {
        this(catalog, transport, DirectHttpProbe.jdk(new StrictTargetAddressPolicy(TargetAddressProperties.unrestricted())),
                hasher);
    }

    SshDockerRuntimeAdapter(SshDockerPlacementCatalog catalog, SshDockerTransport transport,
            DirectHttpProbe direct, ConfigHasher hasher) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.direct = java.util.Objects.requireNonNull(direct, "direct http probe");
        this.hasher = java.util.Objects.requireNonNull(hasher, "hasher");
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
            transport.ensureDockerAvailable(p);   // distinguishes a dead/unreachable daemon from an absent container
            transport.inspect(p);   // the container must exist and be inspectable; its HEALTHCHECK is not read
            transport.verifyConfigDirectoryWritable(p);
            TargetReplica replica = replica(placement, p);
            // T6.9 (Q-G1 (a)): a health signal exists exactly when the package declared a probe SM executes. The
            // image's own HEALTHCHECK is never a declared strength, so it is not consulted here.
            boolean healthDeclared = p.probe() != null;
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
        capturePreImage(p, replica.placement().tenant(), generation, target, config.nonSecretPaths());
        transport.writeFileAtomically(p, target, config.bytes());
        String actual = hashOf(replica.placement().tenant(), transport.readFile(p, target)
                .orElseThrow(() -> failure("ssh.config.readback.absent")));
        if (!config.hash().equals(actual)) {
            throw failure("ssh.materialize.hash.mismatch");
        }
        pruneRetained(p, generation);
        return new MaterializeResult(actual, replicaRef(p, transport.inspect(p)));
    }

    /**
     * Fable APPLY-02: {@code placement.retained_generations} (V007, {@code CHECK >= 1}) is honoured on the customer's
     * host as it is by the local {@code ConfigFileStore}. After a write that has been read back and verified, the
     * newest {@code retainedGenerations} DISTINCT generations of SM's own pre-image, fingerprint and absence files are
     * kept (always including {@code current}, which the exhaustion tail and the reconciler read) and the rest removed.
     *
     * <p>Only names matching SM's fixed grammar for THIS config file are candidates: the leading dot, the declared file
     * name, {@code sm-preimage-}/{@code sm-fp-}, a bounded decimal generation and (pre-image only) {@code .absent}.
     * Anything else in the customer's directory is never touched. Housekeeping, not part of the apply: a listing or
     * delete that fails leaves the files for the next apply to remove and never fails an apply whose file is already
     * written and verified.
     */
    private void pruneRetained(SshDockerPlacement p, long current) {
        try {
            java.util.regex.Pattern ours = java.util.regex.Pattern.compile("^\\." + java.util.regex.Pattern.quote(p.fileName())
                    + "\\.sm-(?:preimage-(\\d{1,18})(?:\\.absent)?|fp-(\\d{1,18}))$");
            java.util.Map<String, Long> candidates = new java.util.LinkedHashMap<>();
            for (String name : transport.listHiddenFileNames(p)) {
                java.util.regex.Matcher m = ours.matcher(name);
                if (m.matches()) {
                    candidates.put(name, Long.parseLong(m.group(1) != null ? m.group(1) : m.group(2)));
                }
            }
            java.util.TreeSet<Long> generations = new java.util.TreeSet<>(candidates.values());
            java.util.Set<Long> keep = new java.util.HashSet<>(generations.descendingSet().stream()
                    .limit(p.retainedGenerations()).toList());
            keep.add(current);
            for (java.util.Map.Entry<String, Long> candidate : candidates.entrySet()) {
                if (!keep.contains(candidate.getValue())) {
                    transport.deleteFile(p, p.remoteConfigDirectory() + "/" + candidate.getKey());
                }
            }
        } catch (RuntimeAdapterException housekeepingFailed) {
            // not an apply failure: the next apply removes what this one could not
        }
    }

    /**
     * The reconciler's conditional re-write (plan 010 T6.8). Re-reads the file right now and refuses, writing nothing,
     * if it is not what the reconciler observed. No pre-image is captured: this generation's was retained by the original
     * write, and capturing again here could retain content SM did not write.
     *
     * <p>Narrows the window; does not close it. SFTP has no compare-and-swap, so a writer landing between the re-read and
     * the atomic replace is not detected (the same window {@link #restore} has).
     */
    @Override
    public MaterializeResult rematerializeIfCurrent(TargetReplica replica, long generation, RenderedConfig config,
            String expectedCurrentHash) {
        SshDockerPlacement p = catalog.lookup(replica.placement());
        String target = p.configFile();
        String now = transport.readFile(p, target).map(bytes -> hashOf(replica.placement().tenant(), bytes)).orElse(null);
        if (!java.util.Objects.equals(now, expectedCurrentHash)) {
            throw failure("reconcile.target.moved");
        }
        transport.writeFileAtomically(p, target, config.bytes());
        String actual = hashOf(replica.placement().tenant(), transport.readFile(p, target)
                .orElseThrow(() -> failure("ssh.config.readback.absent")));
        if (!config.hash().equals(actual)) {
            throw failure("ssh.materialize.hash.mismatch");
        }
        return new MaterializeResult(actual, replicaRef(p, transport.inspect(p)));
    }

    @Override
    public LifecycleResult startOrUpdate(TargetReplica replica, long generation) {
        SshDockerPlacement p = catalog.lookup(replica.placement());
        // A restart strategy also has to work when the pre-existing container is stopped. In that case Docker's
        // explicit start verb is the correct lifecycle action; a running container receives a restart.
        boolean issued = transport.inspect(p).running() ? transport.restart(p) : transport.start(p);
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
        HealthSignal health = health(p, snapshot.running());
        // OR-32: the transport returns bytes; the keyed hash is computed here, in the DP.
        byte[] configBytes = statusSnapshot.configBytes();
        String observedHash = configBytes == null ? null : hashOf(replica.placement().tenant(), configBytes);
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
        // OR-102: the copy is re-checked against what was recorded when it was retained BEFORE its hash becomes a
        // target. A copy altered before this read would otherwise be hashed here and "match" itself.
        Optional<RollbackAssessment> unusable = unusable(p, placement.tenant(), generation, prior.get());
        if (unusable.isPresent()) {
            return unusable.get();
        }
        String targetHash = hashOf(placement.tenant(), prior.get());
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
        // OR-102: the recorded fingerprint first; the intent's hash alone cannot see a copy altered before it was read.
        Optional<RollbackAssessment> unusable = unusable(p, placement.tenant(), generation, prior);
        if (unusable.isPresent()) {
            RollbackAssessment.Unknowable refused = (RollbackAssessment.Unknowable) unusable.get();
            throw new RuntimeAdapterException(refused.why(), refused.change());
        }
        if (!targetHash.equals(hashOf(placement.tenant(), prior))) {
            throw failure("rollback.preimage.altered");
        }
        transport.writeFileAtomically(p, p.configFile(), prior);
        return new RestoreResult(placementRef(p));
    }

    /**
     * OR-102: why the retained copy must not be used, or empty when it is intact. A changed copy carries the declared
     * non-secret paths that differ (paths only); a copy with no trustworthy record is refused without a claim about it.
     */
    private Optional<RollbackAssessment> unusable(SshDockerPlacement p, TenantId tenant, long generation, byte[] prior) {
        byte[] recorded = transport.readFile(p, fingerprintPath(p, generation)).orElse(null);
        PreImageFingerprint.Verdict verdict =
                PreImageFingerprint.check(hasher, tenant, p.fileName(), generation, recorded, prior);
        if (verdict instanceof PreImageFingerprint.Verdict.Changed changed) {
            return Optional.of(new RollbackAssessment.Unknowable(new ErrorClass("rollback.preimage.altered"),
                    changed.change()));
        }
        if (verdict instanceof PreImageFingerprint.Verdict.Unrecorded) {
            return Optional.of(new RollbackAssessment.Unknowable(new ErrorClass("rollback.preimage.unrecorded")));
        }
        return Optional.empty();
    }

    /**
     * {@code BE-D39}: one bounded SFTP read of the placement's OWN declared file ({@code requireDeclaredPath} is the
     * transport's, and the request names no path), hashed here with the keyed hasher for the placement's tenant. Nothing is
     * written and no pre-image is captured. An absent file is a classified refusal, never an empty read.
     */
    @Override
    public ConfigReadBack readConfig(TargetReplica replica) {
        SshDockerPlacement p = catalog.lookup(replica.placement());
        byte[] bytes = transport.readFile(p, p.configFile()).orElseThrow(() -> failure("config.read.absent"));
        try {
            return new ConfigReadBack(bytes, hashOf(replica.placement().tenant(), bytes));
        } catch (RuntimeException e) {
            java.util.Arrays.fill(bytes, (byte) 0);
            throw e;
        }
    }

    private void capturePreImage(SshDockerPlacement p, TenantId tenant, long generation, String target,
            java.util.Set<String> nonSecretPaths) {
        String preimage = preimagePath(p, generation);
        if (transport.readFile(p, preimage).isPresent()
                || transport.readFile(p, absencePath(p, generation)).isPresent()) {
            return;
        }
        Optional<byte[]> current = transport.readFile(p, target);
        if (current.isPresent()) {
            // The record first: a crash between the two redoes both from the still-unreplaced file.
            transport.writeFileAtomically(p, fingerprintPath(p, generation), PreImageFingerprint.record(hasher, tenant,
                    p.fileName(), generation, current.get(), nonSecretPaths));
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

    private static String fingerprintPath(SshDockerPlacement p, long generation) {
        return p.remoteConfigDirectory() + "/." + p.fileName() + FINGERPRINT + generation;
    }

    private static String absencePath(SshDockerPlacement p, long generation) {
        return preimagePath(p, generation) + ABSENT;
    }

    private String hashOf(TenantId tenant, byte[] content) {
        return hasher.hash(tenant, content);
    }

    /**
     * The replica's health, from the placement's package-declared probe and from nothing else (T6.9, Q-G1 (a)).
     *
     * <p>{@code docker inspect} still decides liveness and the start time, but its {@code State.Health} is never read
     * here: with an executable probe declared it would be a second, undeclared signal, and with none declared the
     * honest answer is {@code NOT_DECLARED} whatever the image says. A stopped container is {@code UNHEALTHY} without
     * spending a session on a probe. A probe that cannot be read is {@code UNREADABLE}, never a fallback.
     */
    private HealthSignal health(SshDockerPlacement p, boolean running) {
        HealthProbeSpec probe = p.probe();
        if (probe == null) {
            return HealthSignal.NOT_DECLARED;
        }
        if (!running) {
            return HealthSignal.UNHEALTHY;
        }
        HostProbe.ProbeResult answer;
        try {
            answer = p.acquisition() == AcquisitionMode.DIRECT_HTTP
                    ? direct.get(p.host(), probe.hostPort(), probe.path(), PROBE_TIMEOUT)
                    : transport.probe(p, probe.hostPort(), probe.path(), PROBE_TIMEOUT);
        } catch (RuntimeAdapterException e) {
            return HealthSignal.UNREADABLE;
        }
        if (answer.exitCode() != 0 || answer.httpStatus() == 0) {
            return HealthSignal.UNREADABLE;
        }
        boolean healthy = switch (probe.kind()) {
            case "actuator-health" -> answer.httpStatus() == 200 && actuatorSaysUp(answer.body());
            case "http-status" -> answer.httpStatus() >= 200 && answer.httpStatus() < 300;
            default -> false;
        };
        return healthy ? HealthSignal.HEALTHY : HealthSignal.UNHEALTHY;
    }

    /** M3: a Boot actuator body is {@code {"status": "UP"}}; anything else, including a non-JSON body, is not UP. */
    private static boolean actuatorSaysUp(byte[] body) {
        try {
            JsonNode status = JSON.readTree(body).get("status");
            return status != null && status.isTextual() && "UP".equals(status.asText());
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
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
