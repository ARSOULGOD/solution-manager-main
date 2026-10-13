package com.solace.sm.dp.apply.runtime.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.runtime.ConfigFileStore;
import com.solace.sm.dp.apply.runtime.ConfigReadBack;
import com.solace.sm.dp.apply.runtime.LifecycleResult;
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
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.Liveness;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link RuntimeAdapter} over the Docker Engine API.
 *
 * <p>Runtime-specific identifiers live only in {@link RuntimeRef}; the core model never learns that
 * a container id exists ({@code BE-D12} §2.3).
 */
public final class DockerAdapter implements RuntimeAdapter {

    /** The read-back bound: the SSH adapter's {@code MAX_FILE_BYTES}, so both adapters refuse the same file. */
    private static final int MAX_READ_BYTES = 1024 * 1024;

    private static final byte[] PROBE_BYTES = "sm-preflight-probe\n".getBytes(StandardCharsets.UTF_8);

    private final DockerEngineClient engine;
    private final DockerPlacementCatalog catalog;
    private final ConfigFileStore store;
    private final DockerProperties props;

    public DockerAdapter(DockerEngineClient engine, DockerPlacementCatalog catalog,
            ConfigFileStore store, DockerProperties props) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.store = Objects.requireNonNull(store, "store");
        this.props = Objects.requireNonNull(props, "props");
    }

    /**
     * {@code BE-D25}: the same listing {@code prepare} does, without the per-apply validation and
     * without a generation. One {@code GET /containers/json?all=true} filtered by the placement's
     * name prefix; nothing is written.
     *
     * <p><b>A placement with no replicas is an empty list, never an exception.</b> {@code prepare}
     * refuses that case ({@code docker.replica.absent}) because an apply cannot write to nothing;
     * an observation has no such problem, and a stop over a placement whose containers are all
     * gone has simply nothing to do — which is the same answer {@code ResumePlanner} gives an
     * {@code ABSENT} replica. The two must not disagree about the same fact.
     */
    @Override
    public List<TargetReplica> replicas(PlacementRef placement) {
        DockerPlacement p = catalog.lookup(placement);
        List<TargetReplica> found = new ArrayList<>();
        engine.list(p.containerNamePrefix()).stream().map(c -> c.path("Id").asText()).sorted()
                .forEach(id -> found.add(new TargetReplica(placement, new ReplicaRef(id))));
        return found;
    }

    @Override
    public PreflightResult prepare(PlacementRef placement, long generation) {
        DockerPlacement p = catalog.lookup(placement);

        List<TargetReplica> replicas;
        try {
            replicas = enumerate(placement, p);
        } catch (RuntimeAdapterException e) {
            return new PreflightResult.Refused(e.errorClass(), placementRef(p));
        }

        // SM-D21's round-2 correction: the launch command, the mount and the competing-source set
        // are re-read on EVERY apply, for EVERY replica. A rolling deploy can leave two replicas
        // with different launch specs, and "the one I happened to inspect was fine" is exactly the
        // certificate that has since expired.
        boolean healthSignalDeclared = true;
        for (TargetReplica r : replicas) {
            JsonNode inspect;
            try {
                inspect = engine.inspect(r.ref().value());
            } catch (RuntimeAdapterException e) {
                return new PreflightResult.Refused(e.errorClass(), placementRef(p));
            }

            LaunchSource launch = LaunchSource.of(inspect);
            if (!launch.competingLocations().isEmpty()) {
                return refuse(p, "docker.config.competing.source");
            }
            if (p.mechanism() == com.solace.sm.dp.apply.runtime.ConfigLocation.Mechanism.ADDITIONAL_LOCATION
                    && !declares(launch, p.containerConfigPath())) {
                return refuse(p, "docker.config.path.mismatch");
            }
            if (!isPersistentlyMounted(inspect, p)) {
                return refuse(p, "docker.config.not.persistent");
            }
            healthSignalDeclared &= inspect.path("State").has("Health");
        }

        if (!probeRoundTrips(replicas, p)) {
            return refuse(p, "docker.config.mount.mismatch");
        }
        return new PreflightResult.Ready(replicas, p.location(), healthSignalDeclared,
                placementRef(p));
    }

    /**
     * The replica-listing half, extracted so {@code assessRollback} can reuse it WITHOUT
     * pre-flight's probe round-trip: the probe writes a file, and an assessment may not write
     * anything ({@code BE-D22}).
     */
    private List<TargetReplica> enumerate(PlacementRef placement, DockerPlacement p) {
        List<JsonNode> found = engine.list(p.containerNamePrefix());
        if (found.isEmpty()) {
            throw new RuntimeAdapterException(new ErrorClass("docker.replica.absent"));
        }
        List<TargetReplica> replicas = new ArrayList<>();
        found.stream().map(c -> c.path("Id").asText()).sorted()
                .forEach(id -> replicas.add(new TargetReplica(placement, new ReplicaRef(id))));
        return replicas;
    }

    private static boolean declares(LaunchSource launch, String containerConfigPath) {
        String want = trimTrailingSlash(containerConfigPath);
        return launch.declaredLocations().stream()
                .map(DockerAdapter::trimTrailingSlash)
                .anyMatch(want::equals);
    }

    /**
     * {@code SM-D21}'s round-2 correction, executable: Cache's image does {@code RUN mkdir -p
     * /app/external/spring/config} and declares <b>no {@code VOLUME}</b>, so without a bind or a
     * named volume the path lives in the container layer and SM's config is destroyed the moment the
     * container is replaced. Discovering the right path is necessary and not sufficient.
     */
    private boolean isPersistentlyMounted(JsonNode inspect, DockerPlacement p) {
        String want = trimTrailingSlash(p.containerConfigPath());
        for (JsonNode mount : inspect.path("Mounts")) {
            String destination = trimTrailingSlash(mount.path("Destination").asText());
            boolean covers = want.equals(destination) || want.startsWith(destination + "/");
            if (covers && mount.path("RW").asBoolean()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Proves the directory SM writes to and the directory every replica reads from are the same
     * directory — from the containers' own side, with no {@code exec} and no assumption that the
     * daemon's filesystem namespace is SM's.
     *
     * <p>Comparing {@code Mounts[].Source} to a configured host path would only compare two strings
     * SM was told; this writes a uniquely named probe through SM's own file handle and asks the
     * daemon what is at that path <b>inside</b> each container. Measured: {@code HEAD /archive}
     * answers {@code 200} with a base64 stat header and {@code 404} when it is not there.
     *
     * <p>The probe is deleted in a finally block, and is named so a leaked one is obvious and
     * harmless — it is a dotfile, and Spring ignores dotfiles in an additional location.
     */
    private boolean probeRoundTrips(List<TargetReplica> replicas, DockerPlacement p) {
        String name = ".sm-preflight-" + UUID.randomUUID();
        Path local = p.localConfigDir().resolve(name);
        try {
            Files.createDirectories(p.localConfigDir());
            Files.write(local, PROBE_BYTES);
            for (TargetReplica r : replicas) {
                boolean seen = engine.statInContainer(r.ref().value(), containerPathOf(p, name))
                        .filter(stat -> stat.size() == PROBE_BYTES.length)
                        .isPresent();
                if (!seen) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(local);
            } catch (IOException ignored) {
                // A leaked probe is a dotfile Spring does not read. Failing pre-flight over the
                // cleanup would refuse an apply for a reason that is not about the apply.
            }
        }
    }

    private static String containerPathOf(DockerPlacement p, String fileName) {
        String dir = trimTrailingSlash(p.containerConfigPath());
        return dir + "/" + fileName;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") && s.length() > 1 ? s.substring(0, s.length() - 1) : s;
    }

    private PreflightResult refuse(DockerPlacement p, String token) {
        return new PreflightResult.Refused(new ErrorClass(token), placementRef(p));
    }

    /** Placement-scoped evidence. The CONTAINER-side path, never the host one (I6, Task 7). */
    private RuntimeRef placementRef(DockerPlacement p) {
        return RuntimeRef.of(RuntimeRef.Key.MOUNT_PATH, p.containerConfigPath());
    }

    // ---- Task 5: materialize and observe --------------------------------------------------------

    @Override
    public MaterializeResult materializeConfig(TargetReplica replica, long generation,
            RenderedConfig config) {
        DockerPlacement p = catalog.lookup(replica.placement());
        // ZERO lifecycle effect, structurally: this method holds no reference to `engine` at all.
        // "Writing configuration never restarts anything" is then a property of the code's SHAPE
        // rather than of its current control flow (SM-D15, AG-D3).
        String written = store.write(replica.placement().tenant(), p.localConfigDir(), p.fileName(), config,
                generation);
        return new MaterializeResult(written, replicaRef(p, replica.ref().value(), null));
    }

    @Override
    public RuntimeStatus status(TargetReplica replica) {
        DockerPlacement p = catalog.lookup(replica.placement());
        JsonNode inspect;
        try {
            inspect = engine.inspect(replica.ref().value());
        } catch (RuntimeAdapterException e) {
            if ("docker.container.absent".equals(e.errorClass().token())) {
                return new RuntimeStatus(Liveness.ABSENT, HealthSignal.NOT_DECLARED, null, null,
                        replicaRef(p, replica.ref().value(), null));
            }
            throw e;
        }

        JsonNode state = inspect.path("State");
        boolean running = state.path("Running").asBoolean();
        Liveness liveness = running ? Liveness.RUNNING : Liveness.STOPPED;

        // Liveness is read FIRST and gates the health read. MEASURED: a stopped container's
        // State.Health.Status reads "unhealthy" -- STALE, not absent -- so a health read that
        // ignored liveness would report a dead replica's last opinion as current.
        HealthSignal health = health(state, running);

        String observedHash = store.readBackHash(replica.placement().tenant(), p.localConfigDir().resolve(p.fileName()))
                .orElse(null);

        PreBaseline baseline = running ? baselineOf(state, replica) : null;

        return new RuntimeStatus(liveness, health, observedHash, baseline,
                replicaRef(p, replica.ref().value(), inspect.path("Image").asText(null)));
    }

    private static HealthSignal health(JsonNode state, boolean running) {
        if (!state.has("Health")) {
            return HealthSignal.NOT_DECLARED;   // no healthcheck declared, MEASURED as an ABSENT key
        }
        if (!running) {
            return HealthSignal.UNHEALTHY;      // never HEALTHY while down, whatever the field says
        }
        return switch (state.path("Health").path("Status").asText("")) {
            case "healthy" -> HealthSignal.HEALTHY;
            case "starting" -> HealthSignal.STARTING;
            default -> HealthSignal.UNHEALTHY;
        };
    }

    private static PreBaseline baselineOf(JsonNode state, TargetReplica replica) {
        String startedAt = state.path("StartedAt").asText("");
        // MEASURED: a container that has never started carries "0001-01-01T00:00:00Z". Treating
        // that as a baseline would make a first-ever restart unobservable.
        if (startedAt.isBlank() || startedAt.startsWith("0001-01-01")) {
            return null;
        }
        try {
            return new PreBaseline(Instant.parse(startedAt), replica.ref().value());
        } catch (DateTimeParseException e) {
            // An unparseable baseline is "missing or unreadable" -- §2.2 rule 3's indeterminate
            // trigger -- reported as absence, not thrown, so the ENGINE decides what that means.
            return null;
        }
    }

    /** Replica-scoped evidence. The CONTAINER-side mount path, never the host one (I6). */
    private RuntimeRef replicaRef(DockerPlacement p, String containerId, String imageId) {
        if (imageId == null || imageId.isBlank()) {
            return RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, containerId,
                    RuntimeRef.Key.MOUNT_PATH, p.containerConfigPath());
        }
        return RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, containerId,
                RuntimeRef.Key.MOUNT_PATH, p.containerConfigPath(),
                RuntimeRef.Key.IMAGE_DIGEST, imageId);
    }

    // ---- Task 6: lifecycle, and BE-D22's exhaustion pair ---------------------------------------

    /**
     * The reconciler's conditional re-write (plan 010 T6.8): re-reads the file immediately before the write and refuses,
     * writing nothing, when it is not what the reconciler observed. Like {@link #materializeConfig} it holds no reference
     * to the engine client, so it has zero lifecycle effect by construction.
     */
    @Override
    public MaterializeResult rematerializeIfCurrent(TargetReplica replica, long generation, RenderedConfig config,
            String expectedCurrentHash) {
        DockerPlacement p = catalog.lookup(replica.placement());
        String now = store.readBackHash(replica.placement().tenant(), p.localConfigDir().resolve(p.fileName()))
                .orElse(null);
        if (!Objects.equals(now, expectedCurrentHash)) {
            throw new RuntimeAdapterException(new ErrorClass("reconcile.target.moved"));
        }
        // No pre-image: the generation's was retained by the original write (see ConfigFileStore.rewrite).
        String written = store.rewrite(replica.placement().tenant(), p.localConfigDir(), p.fileName(), config);
        return new MaterializeResult(written, replicaRef(p, replica.ref().value(), null));
    }

    /**
     * {@code BE-D39}: reads the placement's own file through the same bind mount {@link #materializeConfig} writes. Holds
     * no reference to the engine client and writes nothing, so it is read-only by shape. Bounded at 1 MiB, the same bound
     * the SSH adapter enforces.
     */
    @Override
    public ConfigReadBack readConfig(TargetReplica replica) {
        DockerPlacement p = catalog.lookup(replica.placement());
        byte[] bytes;
        try {
            bytes = store.readBounded(p.localConfigDir().resolve(p.fileName()), MAX_READ_BYTES)
                    .orElseThrow(() -> new RuntimeAdapterException(new ErrorClass("config.read.absent")));
        } catch (java.io.UncheckedIOException e) {
            throw new RuntimeAdapterException(new ErrorClass("docker.config.too.large"));
        }
        try {
            return new ConfigReadBack(bytes, store.hashOf(replica.placement().tenant(), bytes));
        } catch (RuntimeException e) {
            java.util.Arrays.fill(bytes, (byte) 0);
            throw e;
        }
    }

    @Override
    public LifecycleResult startOrUpdate(TargetReplica replica, long generation) {
        DockerPlacement p = catalog.lookup(replica.placement());
        // The DECLARED verb (SM-D19). On Docker that is a container restart of the SAME image: this
        // adapter has no code path that pulls, tags, creates or replaces a container, and
        // DockerEngineClient exposes none -- the absence is structural, not a promise (SM-D2, I1).
        boolean acted = engine.restart(replica.ref().value(), props.stopTimeoutSeconds());
        return new LifecycleResult(acted, replicaRef(p, replica.ref().value(), null));
    }

    @Override
    public LifecycleResult stop(TargetReplica replica) {
        DockerPlacement p = catalog.lookup(replica.placement());
        boolean acted = engine.stop(replica.ref().value(), props.stopTimeoutSeconds());
        return new LifecycleResult(acted, replicaRef(p, replica.ref().value(), null));
    }

    /**
     * {@code BE-D22}'s read half. <b>Writes nothing</b> — no {@code store.write}, no
     * {@code store.restore}, no {@code engine.restart}, no {@code engine.stop}, and deliberately not
     * {@link #prepare} either, because pre-flight's probe writes a file. That is the property the
     * contract kit asserts from the outside, and the reason the engine may journal this call as the
     * hashless {@code ROLLBACK_ASSESS} step.
     */
    @Override
    public RollbackAssessment assessRollback(PlacementRef placement, long generation,
            String intendedHash) {
        DockerPlacement p = catalog.lookup(placement);

        // The PRE-IMAGE question comes first, and the ordering is load-bearing. If this generation
        // never wrote anything, the target is untouched no matter what is on disk now -- so the
        // answer is NothingToRestore (-> FAILED_NO_EFFECT), not "I cannot read the replicas". An
        // earlier draft read the replicas first and reported a never-materialized generation as
        // APPLY_INDETERMINATE, which is the over-cautious direction but still a wrong answer.
        ConfigFileStore.PreImage kind =
                store.preImageKind(p.localConfigDir(), p.fileName(), generation);
        if (kind == ConfigFileStore.PreImage.MISSING) {
            return new RollbackAssessment.NothingToRestore();
        }
        if (kind == ConfigFileStore.PreImage.ABSENT) {
            // There WAS no file before this generation, so a restore has no target hash and
            // hash_on_config_steps gives a ROLLBACK intent nowhere to record one. §2.2 rule 0
            // forbids acting without the entry, so this stops and asks the operator (BE-D22).
            return new RollbackAssessment.Unknowable(new ErrorClass("rollback.target.absent"));
        }

        // OR-102: re-check the retained copy against the fingerprint recorded when it was retained BEFORE its hash is
        // taken as the target. A copy altered before this read would otherwise be hashed here and "match" itself.
        RollbackAssessment.Unknowable unusable = unusablePreImage(placement, p, generation);
        if (unusable != null) {
            return unusable;
        }

        String targetHash = store.preImageHash(placement.tenant(), p.localConfigDir(), p.fileName(), generation)
                .orElseThrow(() -> new RuntimeAdapterException(
                        new ErrorClass("rollback.state.unreadable")));

        List<TargetReplica> replicas;
        try {
            replicas = enumerate(placement, p);
        } catch (RuntimeAdapterException e) {
            return new RollbackAssessment.Unknowable(new ErrorClass("rollback.state.unreadable"));
        }

        // BE-D22 correction A: exactly TWO values are journal-known -- the rollback target and the
        // MATERIALIZE intent's own hash. Their mixture is the ordinary partial-write case, not
        // divergence. A third value means a human or a competing writer got there first, and
        // B/D12 forbids reverting them.
        for (TargetReplica r : replicas) {
            RuntimeStatus now;
            try {
                now = status(r);
            } catch (RuntimeAdapterException e) {
                return new RollbackAssessment.Unknowable(new ErrorClass("rollback.state.unreadable"));
            }
            String seen = now.observedHash();
            if (now.liveness() == Liveness.ABSENT || seen == null) {
                return new RollbackAssessment.Unknowable(new ErrorClass("rollback.state.unreadable"));
            }
            if (!(targetHash.equals(seen) || seen.equals(intendedHash))) {
                return new RollbackAssessment.Unknowable(new ErrorClass("rollback.state.unknown"));
            }
        }
        return new RollbackAssessment.Restorable(targetHash);
    }

    /** {@code BE-D22}'s destructive half, journalled as {@code ROLLBACK} with {@code targetHash}. */
    @Override
    public RestoreResult restore(PlacementRef placement, long generation, String targetHash) {
        DockerPlacement p = catalog.lookup(placement);
        // BE-D22 correction B: the pre-image is re-hashed against the target the assessment
        // recorded, BEFORE anything is written. If it moved, nothing is written at all.
        ConfigFileStore.Restoration restoration =
                store.restore(placement.tenant(), p.localConfigDir(), p.fileName(), generation, targetHash);
        if (restoration == ConfigFileStore.Restoration.NOTHING_TO_RESTORE) {
            throw new RuntimeAdapterException(new ErrorClass("rollback.preimage.vanished"));
        }
        if (restoration == ConfigFileStore.Restoration.TARGET_MOVED) {
            // OR-102: say WHY the copy was refused. An intact copy that merely differs from the recorded target is the
            // older "altered between assess and restore" case and carries no detail.
            RollbackAssessment.Unknowable why = unusablePreImage(placement, p, generation);
            throw why == null
                    ? new RuntimeAdapterException(new ErrorClass("rollback.preimage.altered"))
                    : new RuntimeAdapterException(why.why(), why.change());
        }
        return new RestoreResult(placementRef(p));
    }

    /**
     * OR-102: why the retained copy must not be used, or {@code null} when it matches what was recorded. A changed copy
     * carries the declared non-secret paths that differ; a copy with no trustworthy record carries nothing, and no
     * claim is made about its content.
     */
    private RollbackAssessment.Unknowable unusablePreImage(PlacementRef placement, DockerPlacement p, long generation) {
        PreImageFingerprint.Verdict verdict =
                store.checkPreImage(placement.tenant(), p.localConfigDir(), p.fileName(), generation);
        if (verdict instanceof PreImageFingerprint.Verdict.Changed changed) {
            return new RollbackAssessment.Unknowable(new ErrorClass("rollback.preimage.altered"), changed.change());
        }
        if (verdict instanceof PreImageFingerprint.Verdict.Unrecorded) {
            return new RollbackAssessment.Unknowable(new ErrorClass("rollback.preimage.unrecorded"));
        }
        return null;
    }
}
