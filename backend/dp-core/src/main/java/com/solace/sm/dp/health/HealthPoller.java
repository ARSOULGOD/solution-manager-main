package com.solace.sm.dp.health;

import com.solace.sm.dp.api.health.HealthState;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
import com.solace.sm.dp.inventory.InstanceRecord;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRecord;
import com.solace.sm.dp.inventory.PlacementRows;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The health poller (plan 010 T7.2, {@code OR-39}): the DATA PLANE polls every active instance's placements and
 * persists a verdict only when it changes. The honest detection bound is {@code interval + timeout}; the MI pushes
 * nothing ({@code I1}).
 *
 * <h2>Who is visited</h2>
 * Every tenant {@link TenantEnumerationRepository#tenantsWithActiveInstances()} yields - <b>the second cross-tenant
 * read, owner ruling Q-T / {@code OR-49}</b>, called here and never re-spelt. Owner ruling M-Q3 ({@code OR-72}, which
 * amends {@code OR-49}) names plan 011's {@code MetricsCollector} as the ruled FOURTH caller of that list; it is a
 * separate component built in plan 011, and this class builds and calls no collector. Every other read in a round is
 * scoped to one tenant at a time. The list is the tenants with an ACTIVE instance, not those with an in-flight apply
 * (OR-44's list would miss every idle tenant - B4).
 *
 * <h2>What is polled, and how</h2>
 * Each ACTIVE instance's live placements, in the way the instance acquires health ({@link HealthProber}: via-ssh over
 * T7.1's pool, or direct-http at the target's registered address; no fallback between them). A placement that declares
 * no probe is recorded {@code NOT_APPLICABLE} once and never probed (owner ruling {@code OR-101}).
 *
 * <h2>Backoff</h2>
 * Per (tenant, target): after the n-th consecutive {@code unreachable} the target is not probed again for
 * {@code min(maxBackoff, interval * 2^n)}, and the first probe that gets through resets it. While backed off a
 * placement's row is left alone (so its {@code checked_at} goes stale, which is the truth). A round counts at most one
 * failure per target, however many of its placements failed.
 *
 * <h2>Container starts (OR-112 (1))</h2>
 * With a {@link StartObserver}, a round also reads the container start of each placement whose newest generation is
 * persisted under an ungated strategy and has seen none since the write: the evidence the config ladder and the metrics
 * active list need for such an apply to reach {@code restart-observed}. It is independent of the placement's health probe
 * (a placement with no probe is still observed), reads only the placements that wait, and adds no tenant read.
 *
 * <h2>Agent placements are not polled (plan 015 T3.1, {@code BE-D66})</h2>
 * A placement whose target uses the {@code agent} transport is skipped entirely: no probe, no {@code placement_health} write, no
 * start observation. The agent reports its own health facts and the DATA PLANE maps them (T3.3); a second writer of the same
 * row here would race it, and a probe over SSH to an agent target has nothing to connect to.
 *
 * <p>One placement's failure never stops the round, and a round's failure never stops the schedule.
 */
public final class HealthPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HealthPoller.class);

    private record TargetKey(UUID tenant, UUID target) {
    }

    private static final class Backoff {
        int failures;
        long lastFailureRound = -1;
        Instant nextProbeAt = Instant.MIN;
    }

    private final TenantEnumerationRepository tenants;
    private final InstanceRows instances;
    private final PlacementRows placements;
    private final PlacementHealthRows health;
    private final HealthProber prober;
    private final HealthRecorder recorder;
    private final HealthPollerProperties properties;
    private final Clock clock;
    private final Executor probeExecutor;
    private final Runnable afterRound;
    private final StartObserver startObserver;
    private final PlacementTransports transports;
    private final ConcurrentMap<TargetKey, Backoff> backoff = new ConcurrentHashMap<>();
    private final AtomicLong rounds = new AtomicLong();

    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    /**
     * @param probeExecutor runs the round's probes: the bounded pool {@code HealthConfiguration} builds from
     *                      {@code sm.dp.health.concurrency} in production, the caller's thread in tests
     * @param afterRound    runs at the end of every round (production: the session pool's idle close)
     */
    public HealthPoller(TenantEnumerationRepository tenants, InstanceRows instances, PlacementRows placements,
            PlacementHealthRows health, HealthProber prober, HealthRecorder recorder, HealthPollerProperties properties,
            Clock clock, Executor probeExecutor, Runnable afterRound) {
        this(tenants, instances, placements, health, prober, recorder, properties, clock, probeExecutor, afterRound, null);
    }

    /**
     * @param startObserver reads the container start of the placements still waiting for one (OR-112 (1)), or
     *                      {@code null}: nothing then observes a start and an ungated apply stays at {@code persisted}
     */
    public HealthPoller(TenantEnumerationRepository tenants, InstanceRows instances, PlacementRows placements,
            PlacementHealthRows health, HealthProber prober, HealthRecorder recorder, HealthPollerProperties properties,
            Clock clock, Executor probeExecutor, Runnable afterRound, StartObserver startObserver) {
        this(tenants, instances, placements, health, prober, recorder, properties, clock, probeExecutor, afterRound,
                startObserver, PlacementTransports.NONE);
    }

    /**
     * @param transports says which targets are agent targets, whose placements are skipped (plan 015 T3.1); the pre-agent forms
     *                   pass {@link PlacementTransports#NONE}, for which nothing is
     */
    public HealthPoller(TenantEnumerationRepository tenants, InstanceRows instances, PlacementRows placements,
            PlacementHealthRows health, HealthProber prober, HealthRecorder recorder, HealthPollerProperties properties,
            Clock clock, Executor probeExecutor, Runnable afterRound, StartObserver startObserver,
            PlacementTransports transports) {
        this.transports = Objects.requireNonNull(transports, "transports");
        this.startObserver = startObserver;
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.instances = Objects.requireNonNull(instances, "instances");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.health = Objects.requireNonNull(health, "health");
        this.prober = Objects.requireNonNull(prober, "prober");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.probeExecutor = Objects.requireNonNull(probeExecutor, "probeExecutor");
        this.afterRound = Objects.requireNonNull(afterRound, "afterRound");
    }

    /** One full round over every tenant with an active instance. */
    public void pollOnce() {
        long round = rounds.incrementAndGet();
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (TenantId tenant : tenants.tenantsWithActiveInstances()) {
            try {
                for (InstanceRecord instance : instances.list(tenant.value(), InstanceLifecycle.ACTIVE,
                        Optional.empty())) {
                    Set<UUID> awaitingStart = awaitingStart(tenant, instance);
                    for (PlacementRecord placement : placements.live(tenant.value(), instance.instanceId())) {
                        if (transports.isAgentTarget(tenant.value(), placement.targetId())) {
                            continue;       // the agent reports its own health; nothing here probes or writes it (T3.1)
                        }
                        work.add(CompletableFuture.runAsync(() -> pollPlacement(round, instance, placement),
                                probeExecutor));
                        if (awaitingStart.contains(placement.placementId()) && !backedOff(tenant, placement)) {
                            work.add(CompletableFuture.runAsync(() -> observeStart(placement), probeExecutor));
                        }
                    }
                }
            } catch (RuntimeException e) {
                // one tenant's failure to enumerate is not every tenant's
                log.warn("health poll: a tenant could not be enumerated ({})", e.getClass().getSimpleName());
            }
        }
        work.forEach(CompletableFuture::join);
        try {
            afterRound.run();
        } catch (RuntimeException e) {
            log.warn("health poll: end-of-round housekeeping failed ({})", e.getClass().getSimpleName());
        }
    }

    /**
     * The instance's placements that still wait for a container start (OR-112 (1)). Asked per instance, once a round, from
     * the journal alone: a failure is "none this round", never a failed round.
     */
    private Set<UUID> awaitingStart(TenantId tenant, InstanceRecord instance) {
        if (startObserver == null) {
            return Set.of();
        }
        try {
            return startObserver.awaiting(tenant, new InstanceId(instance.instanceId()));
        } catch (RuntimeException e) {
            log.warn("health poll: an instance's start observation could not be planned ({})", e.getClass().getSimpleName());
            return Set.of();
        }
    }

    /**
     * Whether the placement's target is in the backoff its health probe earned (an unreachable host). The start observation
     * rides the same pooled SSH session as that probe, so it waits out the same backoff rather than trying a dead host every
     * round and holding a probe-executor slot for the connect timeout each time.
     */
    private boolean backedOff(TenantId tenant, PlacementRecord placement) {
        Backoff held = backoff.get(new TargetKey(tenant.value(), placement.targetId()));
        return held != null && clock.instant().isBefore(held.nextProbeAt);
    }

    private void observeStart(PlacementRecord placement) {
        try {
            startObserver.observe(placement);
        } catch (RuntimeException e) {
            // classifier only: a driver or transport message can carry a host, a path or a role
            log.warn("health poll: one placement's container start could not be observed ({})", e.getClass().getSimpleName());
        }
    }

    private void pollPlacement(long round, InstanceRecord instance, PlacementRecord placement) {
        try {
            UUID tenant = placement.tenantId();
            if (placement.healthKind().isEmpty()) {
                // No declared probe (OR-101): NOT_APPLICABLE is a property of the package, not a fault of the
                // placement. Recorded once, never probed, and nothing to check afterwards. A row an earlier build
                // wrote as not_exposed is corrected once; after that the state is unchanged and nothing moves.
                boolean recorded = health.find(tenant, placement.placementId())
                        .map(row -> row.state() == HealthState.NOT_APPLICABLE).orElse(false);
                if (!recorded) {
                    recorder.record(tenant, instance.instanceId(), placement.placementId(),
                            HealthState.NOT_APPLICABLE);
                }
                return;
            }
            TargetKey key = new TargetKey(tenant, placement.targetId());
            Backoff held = backoff.get(key);
            if (held != null && clock.instant().isBefore(held.nextProbeAt)) {
                return;
            }
            Observation observation = prober.observe(instance, placement);
            if (observation == Observation.DEFERRED) {
                return;
            }
            HealthState state = ProbeResultMapper.map(placement.healthKind().get(), observation);
            if (state == HealthState.UNREACHABLE) {
                recordFailure(key, round);
            } else {
                backoff.remove(key);
            }
            recorder.record(tenant, instance.instanceId(), placement.placementId(), state);
        } catch (RuntimeException e) {
            // classifier only: a driver or transport message can carry a host, a path or a role
            log.warn("health poll: one placement could not be polled ({})", e.getClass().getSimpleName());
        }
    }

    private void recordFailure(TargetKey key, long round) {
        backoff.compute(key, (k, b) -> {
            Backoff next = b == null ? new Backoff() : b;
            if (next.lastFailureRound != round) {
                next.lastFailureRound = round;
                next.failures++;
                Duration wait = properties.interval().multipliedBy(1L << Math.min(next.failures, 20));
                if (wait.compareTo(properties.maxBackoff()) > 0) {
                    wait = properties.maxBackoff();
                }
                next.nextProbeAt = clock.instant().plus(wait);
            }
            return next;
        });
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "sm-health-poller"));
        running = true;
        scheduleNext();
    }

    private void scheduleNext() {
        long millis = properties.interval().toMillis();
        long jittered = millis + (long) (millis * properties.jitter() * ThreadLocalRandom.current().nextDouble());
        scheduler.schedule(this::round, jittered, TimeUnit.MILLISECONDS);
    }

    private void round() {
        try {
            if (running) {
                pollOnce();
            }
        } catch (Throwable t) {
            log.warn("health poll: a round failed ({})", t.getClass().getSimpleName());
        } finally {
            synchronized (this) {
                if (running) {
                    scheduleNext();
                }
            }
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
