package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RuntimeRef;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link RuntimeAdapter} with no runtime behind it.
 *
 * <p>Two jobs. It proves the contract kit is not vacuous — an abstract test class with no subclass
 * runs zero tests. And it gives the engine's tests an adapter that can be driven into failure states
 * a real daemon will not reproduce on demand: an unreadable start baseline, a competing writer, a
 * pre-flight refusal, a pre-image that moves mid-flight.
 *
 * <p>Config state is <b>per replica</b>, which is the general case: two containers of one placement
 * can hold different bytes after a partial materialize, and that mixture is exactly what
 * {@code BE-D22} correction A is about.
 *
 * <p>Test scope only, and deliberately so: nothing in production may resolve a fake adapter.
 */
public final class InMemoryRuntimeAdapter implements RuntimeAdapter {

    /** What this fake should do instead of behaving. One switch per rule it helps exercise. */
    public enum Fault {
        NONE,
        PREFLIGHT_REFUSED,
        BASELINE_UNREADABLE,
        COMPETING_WRITER,
        NEVER_HEALTHY
    }

    private final PlacementRef placement;
    private final List<TargetReplica> replicas = new ArrayList<>();
    private final Map<ReplicaRef, String> hashes = new LinkedHashMap<>();
    /** What each replica's file CONTAINS, for {@link #readConfig}; the hash map above is all the rest of the seam needs. */
    private final Map<ReplicaRef, byte[]> contents = new LinkedHashMap<>();
    private ConfigHasher readHasher = TestHashers.SHA256;
    private final AtomicInteger readConfigCalls = new AtomicInteger();
    private final Map<ReplicaRef, Instant> startedAt = new LinkedHashMap<>();
    private final Map<ReplicaRef, Boolean> running = new LinkedHashMap<>();
    /** generation → replica → what that replica held BEFORE this generation landed (empty = nothing). */
    private final Map<Long, Map<ReplicaRef, Optional<String>>> preImages = new LinkedHashMap<>();
    private final Set<String> failMaterializeOn = new LinkedHashSet<>();
    private final Map<String, String> reportWrongHashOn = new LinkedHashMap<>();
    private final Map<String, String> writeWrongButReportExpectedOn = new LinkedHashMap<>();
    private final AtomicInteger restarts = new AtomicInteger();
    private final AtomicInteger prepareCalls = new AtomicInteger();
    private final AtomicInteger materializeCalls = new AtomicInteger();
    private final AtomicInteger assessCalls = new AtomicInteger();
    private final AtomicInteger rematerializeCalls = new AtomicInteger();
    private final AtomicInteger statusCalls = new AtomicInteger();
    private final Set<String> baselineUnreadableOn = new LinkedHashSet<>();
    private final boolean healthSignalDeclared;

    private Fault fault = Fault.NONE;
    private boolean failMaterializeAlways;
    private String leaveBehindOnFailure;
    private Long changedPreImageGeneration;
    private PreImageChange changedPreImage;
    private Long unrecordedPreImageGeneration;
    private Long changedAtRestoreGeneration;
    private PreImageChange changedAtRestore;
    private Long alterPreImageAfterAssessGeneration;
    private String alteredPreImageHash;
    private Instant clock = Instant.parse("2026-09-12T00:00:00Z");
    private String statusFailure;
    private HealthSignal healthOverride;
    private java.util.function.BiConsumer<InMemoryRuntimeAdapter, TargetReplica> beforeRematerialize;
    private String failRematerialize;

    public InMemoryRuntimeAdapter(PlacementRef placement, boolean healthSignalDeclared,
            String... replicaRefs) {
        this.placement = placement;
        this.healthSignalDeclared = healthSignalDeclared;
        for (String r : replicaRefs) {
            TargetReplica t = new TargetReplica(placement, new ReplicaRef(r));
            replicas.add(t);
            startedAt.put(t.ref(), clock);
            running.put(t.ref(), true);
        }
    }

    // ---- test controls -------------------------------------------------------------------------

    public void fault(Fault f) {
        this.fault = f;
    }

    /**
     * Sets the HOST's clock (OR-112 (1) skew tests): every replica is taken to have started at {@code now}, and a later
     * restart starts one second after it. The data plane's own clock is the real one, so this is how a skew is staged.
     */
    public void hostClock(Instant now) {
        clock = now;
        startedAt.replaceAll((ref, old) -> now);
    }

    /** The start the host reports for the replica now (what {@code docker inspect} would say), or null if it is not running. */
    public Instant startOf(String replicaRef) {
        return Boolean.TRUE.equals(running.get(new ReplicaRef(replicaRef))) ? startedAt.get(new ReplicaRef(replicaRef)) : null;
    }

    public void failMaterializeOn(String replicaRef) {
        failMaterializeOn.add(replicaRef);
    }

    public void failMaterializeAlways() {
        failMaterializeAlways = true;
    }

    /**
     * Makes {@code materializeConfig} write, and report, a hash that is NOT the one the intent
     * bound — the adapter equivalent of a corrupt or partial write that still returned normally.
     */
    public void reportWrongHashOn(String replicaRef, String wrongHash) {
        reportWrongHashOn.put(replicaRef, wrongHash);
    }

    /** Makes the runtime hold different bytes while the adapter falsely claims the requested hash. */
    public void writeWrongButReportExpectedOn(String replicaRef, String wrongHash) {
        writeWrongButReportExpectedOn.put(replicaRef, wrongHash);
    }

    /** What a failed materialize leaves on that replica's disk, if anything but the prior value. */
    public void leaveBehindOnFailure(String hash) {
        leaveBehindOnFailure = hash;
    }

    /** Simulates a competing writer landing bytes SM did not write (BE-D22 correction A). */
    public void competingWrite(TargetReplica r, String hash) {
        hashes.put(r.ref(), hash);
    }

    /** Simulates the retained pre-image moving between assess and restore (correction B). */
    public void alterPreImageAfterAssess(long generation, String alteredHash) {
        this.alterPreImageAfterAssessGeneration = generation;
        this.alteredPreImageHash = alteredHash;
    }

    /**
     * OR-102: the retained pre-image for {@code generation} no longer matches the fingerprint recorded when it was
     * retained. {@code assessRollback} answers {@code rollback.preimage.altered} carrying {@code change}, and a
     * {@code restore} refuses with the same detail.
     */
    public void preImageChangedSinceRetention(long generation, PreImageChange change) {
        this.changedPreImageGeneration = generation;
        this.changedPreImage = change;
    }

    /** OR-102: there is no trustworthy fingerprint for the generation's retained pre-image. */
    public void preImageUnrecorded(long generation) {
        this.unrecordedPreImageGeneration = generation;
    }

    /** OR-102: as above, but the change appears only AFTER a clean assessment, so only {@code restore} refuses. */
    public void preImageChangedAfterAssess(long generation, PreImageChange change) {
        this.changedAtRestoreGeneration = generation;
        this.changedAtRestore = change;
    }

    /** Every {@code status} (and so every {@code assessRollback}) throws this classifier until cleared with {@code null}. */
    public void failStatusWith(String classifier) {
        this.statusFailure = classifier;
    }

    /** That replica reports no start baseline while running - mid-restart, in effect (M18). */
    public void baselineUnreadableOn(String replicaRef) {
        baselineUnreadableOn.add(replicaRef);
    }

    public void clearBaselineUnreadable() {
        baselineUnreadableOn.clear();
    }

    /** Every running replica reports this health signal instead of its own. {@code null} restores the default. */
    public void healthOverride(HealthSignal signal) {
        this.healthOverride = signal;
    }

    /** Runs just before {@code rematerializeIfCurrent} re-reads the file: lets a test land a foreign write in that gap. */
    public void beforeRematerialize(java.util.function.BiConsumer<InMemoryRuntimeAdapter, TargetReplica> hook) {
        this.beforeRematerialize = hook;
    }

    /** A classified failure of {@code rematerializeIfCurrent} AFTER the re-check passed (the host went away mid-write). */
    public void failRematerializeWith(String classifier) {
        this.failRematerialize = classifier;
    }

    /**
     * Puts these exact bytes in the replica's file, as a human editing the host would (D-ACCEPT's scenarios), hashing them
     * with {@code hasher} so {@link #status} and {@link #readConfig} agree. The hasher is also what {@link #readConfig} uses.
     */
    public void writeFileAsHuman(TargetReplica r, byte[] bytes, ConfigHasher hasher) {
        this.readHasher = hasher;
        contents.put(r.ref(), bytes.clone());
        hashes.put(r.ref(), hasher.hash(placement.tenant(), bytes));
    }

    /** The hasher {@link #readConfig} (and a materialize through a kit fixture) reports with. */
    public void readHasher(ConfigHasher hasher) {
        this.readHasher = hasher;
    }

    public int readConfigCalls() {
        return readConfigCalls.get();
    }

    /** What the replica's file hashes to, or {@code null} for no file. */
    public String hashOf(TargetReplica r) {
        return hashes.get(r.ref());
    }

    /** Sets the replica's file to exactly this hash (or removes it with {@code null}) - the state a cut-off leaves. */
    public void setFile(TargetReplica r, String hash) {
        if (hash == null) {
            hashes.remove(r.ref());
        } else {
            hashes.put(r.ref(), hash);
        }
    }

    /** Retains a pre-image for the generation exactly as a first write would: a hash, or {@code null} for "absent". */
    public void retainPreImage(long generation, TargetReplica r, String priorHashOrNull) {
        preImages.computeIfAbsent(generation, g -> new LinkedHashMap<>())
                .put(r.ref(), Optional.ofNullable(priorHashOrNull));
    }

    /** Takes the replica down and leaves it down (an operator's stop). */
    public void setRunning(TargetReplica r, boolean up) {
        running.put(r.ref(), up);
    }

    public List<TargetReplica> replicas() {
        return List.copyOf(replicas);
    }

    public int rematerializeCalls() {
        return rematerializeCalls.get();
    }

    public int statusCalls() {
        return statusCalls.get();
    }

    public int restarts() {
        return restarts.get();
    }

    public int prepareCalls() {
        return prepareCalls.get();
    }

    public int materializeCalls() {
        return materializeCalls.get();
    }

    public int assessCalls() {
        return assessCalls.get();
    }

    // ---- the seam ------------------------------------------------------------------------------

    @Override
    public List<TargetReplica> replicas(PlacementRef p) {
        return boundTo(p);
    }

    /**
     * The fake's replicas, addressed under the placement the caller asked about. A real adapter answers for the placement
     * it is asked about; this fake holds one set of labels, and returning them under its CONSTRUCTOR's placement made a
     * second placement's apply journal its intents under the first's, which V021's trigger (an intent carries its
     * request's own placement) rightly refuses. For the constructor's own placement this is the identity.
     */
    private List<TargetReplica> boundTo(PlacementRef p) {
        if (p == null) {
            return List.copyOf(replicas); // a copy, never the live list; a caller that names no placement gets the constructor's, as before
        }
        return replicas.stream().map(r -> new TargetReplica(p, r.ref())).toList();
    }

    @Override
    public PreflightResult prepare(PlacementRef p, long generation) {
        prepareCalls.incrementAndGet();
        if (fault == Fault.PREFLIGHT_REFUSED) {
            return new PreflightResult.Refused(new ErrorClass("fake.preflight.refused"), ref());
        }
        if (fault == Fault.COMPETING_WRITER) {
            return new PreflightResult.Refused(new ErrorClass("fake.config.competing.source"), ref());
        }
        return new PreflightResult.Ready(boundTo(p),
                new ConfigLocation("/app/external/spring/config", new WriterAccess.LocalFilesystem(Path.of("/dev/null")),
                        "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION),
                healthSignalDeclared, ref());
    }

    @Override
    public MaterializeResult materializeConfig(TargetReplica r, long generation, RenderedConfig c) {
        materializeCalls.incrementAndGet();
        // Capture the pre-image BEFORE the write, once per (generation, replica) -- a re-materialize
        // of the same generation must not overwrite the rollback target with this generation's own
        // bytes, exactly as ConfigFileStore does it.
        preImages.computeIfAbsent(generation, g -> new LinkedHashMap<>())
                .computeIfAbsent(r.ref(), ref -> Optional.ofNullable(hashes.get(ref)));

        if (failMaterializeAlways || failMaterializeOn.contains(r.ref().value())) {
            if (leaveBehindOnFailure != null) {
                hashes.put(r.ref(), leaveBehindOnFailure);
            }
            throw new RuntimeAdapterException(new ErrorClass("fake.materialize.failed"));
        }
        String wrong = reportWrongHashOn.get(r.ref().value());
        if (wrong != null) {
            hashes.put(r.ref(), wrong);
            return new MaterializeResult(wrong, ref());
        }
        String silentlyWrong = writeWrongButReportExpectedOn.get(r.ref().value());
        if (silentlyWrong != null) {
            hashes.put(r.ref(), silentlyWrong);
            return new MaterializeResult(c.hash(), ref());
        }
        hashes.put(r.ref(), c.hash());
        contents.put(r.ref(), c.bytes());
        return new MaterializeResult(c.hash(), ref());
    }

    /**
     * The reconciler's conditional re-write: re-reads the replica's hash right before writing, writes nothing if it moved
     * ({@code reconcile.target.moved}), and captures NO pre-image (the original write retained it).
     */
    @Override
    public MaterializeResult rematerializeIfCurrent(TargetReplica r, long generation, RenderedConfig c,
            String expectedCurrentHash) {
        rematerializeCalls.incrementAndGet();
        if (beforeRematerialize != null) {
            beforeRematerialize.accept(this, r);   // a writer landing between the observation and the write
        }
        if (!java.util.Objects.equals(hashes.get(r.ref()), expectedCurrentHash)) {
            throw new RuntimeAdapterException(new ErrorClass("reconcile.target.moved"));
        }
        if (failRematerialize != null) {
            throw new RuntimeAdapterException(new ErrorClass(failRematerialize));
        }
        hashes.put(r.ref(), c.hash());
        contents.put(r.ref(), c.bytes());
        return new MaterializeResult(c.hash(), ref());
    }

    @Override
    public LifecycleResult startOrUpdate(TargetReplica r, long generation) {
        tick();
        restarts.incrementAndGet();
        startedAt.put(r.ref(), clock);
        running.put(r.ref(), true);
        return new LifecycleResult(true, ref());
    }

    @Override
    public LifecycleResult stop(TargetReplica r) {
        boolean wasRunning = Boolean.TRUE.equals(running.get(r.ref()));
        running.put(r.ref(), false);
        return new LifecycleResult(wasRunning, ref());
    }

    @Override
    public RuntimeStatus status(TargetReplica r) {
        statusCalls.incrementAndGet();
        if (statusFailure != null) {
            throw new RuntimeAdapterException(new ErrorClass(statusFailure));
        }
        boolean up = Boolean.TRUE.equals(running.get(r.ref()));
        HealthSignal health;
        if (up && healthOverride != null) {
            health = healthOverride;
        } else if (!healthSignalDeclared) {
            health = HealthSignal.NOT_DECLARED;
        } else if (!up) {
            health = HealthSignal.UNHEALTHY;
        } else if (fault == Fault.NEVER_HEALTHY) {
            health = HealthSignal.STARTING;
        } else {
            health = HealthSignal.HEALTHY;
        }
        PreBaseline baseline = fault == Fault.BASELINE_UNREADABLE || !up
                || baselineUnreadableOn.contains(r.ref().value())
                ? null
                : new PreBaseline(startedAt.get(r.ref()), r.ref().value());
        return new RuntimeStatus(up ? Liveness.RUNNING : Liveness.STOPPED, health,
                hashes.get(r.ref()), baseline, ref());
    }

    /** READ ONLY. Not one line of this method writes to hashes, startedAt or running. */
    @Override
    public RollbackAssessment assessRollback(PlacementRef p, long generation, String intendedHash) {
        assessCalls.incrementAndGet();
        if (statusFailure != null) {
            throw new RuntimeAdapterException(new ErrorClass(statusFailure));
        }
        Map<ReplicaRef, Optional<String>> prior = preImages.get(generation);
        if (prior == null || prior.isEmpty()) {
            return new RollbackAssessment.NothingToRestore();
        }
        if (unrecordedPreImageGeneration != null && unrecordedPreImageGeneration == generation) {
            return new RollbackAssessment.Unknowable(new ErrorClass("rollback.preimage.unrecorded"));
        }
        if (changedPreImage != null && changedPreImageGeneration == generation) {
            return new RollbackAssessment.Unknowable(new ErrorClass("rollback.preimage.altered"), changedPreImage);
        }
        if (prior.values().stream().anyMatch(Optional::isEmpty)) {
            // The prior state was ABSENCE: no hash exists for a ROLLBACK intent's config_hash, and
            // §2.2 rule 0 forbids acting without the journal entry (BE-D22).
            return new RollbackAssessment.Unknowable(new ErrorClass("rollback.target.absent"));
        }
        Set<String> targets = new LinkedHashSet<>();
        prior.values().forEach(v -> targets.add(v.orElseThrow()));
        if (targets.size() != 1) {
            // Two different prior values cannot both be the ROLLBACK intent's single config_hash.
            return new RollbackAssessment.Unknowable(new ErrorClass("rollback.state.unknown"));
        }
        String targetHash = targets.iterator().next();

        // BE-D22 correction A: exactly TWO values are journal-known -- the rollback target and the
        // MATERIALIZE intent's own hash. Their MIXTURE is the ordinary partial-write case a rollback
        // exists for, not divergence. A third value means somebody else wrote the file.
        for (TargetReplica r : replicas) {
            String seen = hashes.get(r.ref());
            if (seen == null) {
                return new RollbackAssessment.Unknowable(new ErrorClass("rollback.state.unreadable"));
            }
            if (!(seen.equals(targetHash) || seen.equals(intendedHash))) {
                return new RollbackAssessment.Unknowable(new ErrorClass("rollback.state.unknown"));
            }
        }
        if (alterPreImageAfterAssessGeneration != null
                && alterPreImageAfterAssessGeneration == generation) {
            // The world moves between assess and restore: the retained pre-image is no longer what
            // this assessment just reported. The restore below must refuse.
            Map<ReplicaRef, Optional<String>> moved = new LinkedHashMap<>();
            prior.keySet().forEach(k -> moved.put(k, Optional.of(alteredPreImageHash)));
            preImages.put(generation, moved);
        }
        return new RollbackAssessment.Restorable(targetHash);
    }

    @Override
    public RestoreResult restore(PlacementRef p, long generation, String targetHash) {
        Map<ReplicaRef, Optional<String>> prior = preImages.get(generation);
        if (prior == null || prior.isEmpty()) {
            throw new RuntimeAdapterException(new ErrorClass("rollback.preimage.vanished"));
        }
        if (changedPreImage != null && changedPreImageGeneration == generation) {
            throw new RuntimeAdapterException(new ErrorClass("rollback.preimage.altered"), changedPreImage);
        }
        if (changedAtRestore != null && changedAtRestoreGeneration == generation) {
            throw new RuntimeAdapterException(new ErrorClass("rollback.preimage.altered"), changedAtRestore);
        }
        // BE-D22 correction B: re-check the target BEFORE writing anything.
        for (Optional<String> value : prior.values()) {
            if (value.isEmpty() || !value.orElseThrow().equals(targetHash)) {
                throw new RuntimeAdapterException(new ErrorClass("rollback.preimage.altered"));
            }
        }
        for (TargetReplica r : replicas) {
            hashes.put(r.ref(), targetHash);
        }
        return new RestoreResult(ref());
    }

    /** READ ONLY. Reads {@code contents} and writes nothing; an unknown or absent file is {@code config.read.absent}. */
    @Override
    public ConfigReadBack readConfig(TargetReplica r) {
        readConfigCalls.incrementAndGet();
        if (statusFailure != null) {
            throw new RuntimeAdapterException(new ErrorClass(statusFailure));
        }
        byte[] bytes = contents.get(r.ref());
        if (bytes == null) {
            throw new RuntimeAdapterException(new ErrorClass("config.read.absent"));
        }
        return new ConfigReadBack(bytes.clone(), readHasher.hash(placement.tenant(), bytes));
    }

    private void tick() {
        clock = clock.plus(Duration.ofSeconds(1));
    }

    private RuntimeRef ref() {
        return RuntimeRef.of(RuntimeRef.Key.HOST, "in-memory");
    }
}
