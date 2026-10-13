package com.solace.sm.dp.fleet;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;
import com.solace.sm.dp.apply.persistence.WorkerSingletonGuard;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The fleet's ONE advancer (plan 010 T11.2; {@code fleet-apply.md} section 5.5): a data-plane background loop that settles
 * members, opens the next batch and lands finished fleets.
 *
 * <h2>Who is visited</h2>
 * Every tenant {@link TenantEnumerationRepository#tenantsWithActiveInstances()} yields - <b>the second cross-tenant read,
 * owner ruling Q-T / {@code OR-49}</b>, called here and never re-spelt. A fleet BETWEEN batches may have no request in
 * flight, so {@code tenantsWithNonTerminalWork()} ({@code OR-44}'s list) would never yield its tenant and the fleet would
 * stall (B4). For each tenant the scheduler then runs one tenant-scoped query for that tenant's {@code RUNNING} fleets; it
 * does NOT read {@code fleet_action} across tenants, which would be a THIRD cross-tenant read and needs an owner ruling.
 * This is one of the callers {@code OR-49} names (and {@code OR-72}, which amends it, adds plan 011's collector); nothing
 * here is a fifth.
 *
 * <h2>Why exactly one, and how that is enforced</h2>
 * Correctness depends on there being one advancer per fleet ({@link FleetService}'s class javadoc says why), and three
 * things make it so: (1) every pass runs on THIS object's single thread, fleet by fleet, so it never runs two advances at
 * once; (2) {@link #start()} takes {@link WorkerSingletonGuard} first - the PostgreSQL session-level advisory lock that already
 * confines the apply worker pool to one process system-wide ({@code OR-44}'s single replica) - and refuses to start without
 * it, so a second process cannot run a second scheduler. It inherits the guard's one stated limit: if the holding connection
 * silently drops, the guarantee is gone until the next acquisition attempt notices; (3) nothing else calls
 * {@code advance}: {@code FleetService#submitFleet} {@linkplain #nudge() wakes} this scheduler and returns.
 *
 * <h2>Inert, not absent, without an executor</h2>
 * With no {@code ApplyExecutor} (a context that withdrew the production one and supplied none) nothing could be admitted:
 * {@link #start()} then logs a WARN and does nothing else - no thread, no lock - exactly as the apply worker does.
 */
public final class FleetScheduler implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(FleetScheduler.class);

    /**
     * After {@code ApplyWorker} ({@code Integer.MIN_VALUE + 2000}) and the startup sweep, and still far below the web
     * server's, so a fleet is advanced only once the worker that executes its members is up. {@link SmartLifecycle}
     * stops in REVERSE phase order, so this stops BEFORE the worker - and before the guard it relies on is released.
     */
    private static final int PHASE = Integer.MIN_VALUE + 2100;

    private final BooleanSupplier executorAvailable;
    private final TenantEnumerationRepository tenants;
    private final FleetService fleets;
    private final WorkerSingletonGuard guard;
    private final FleetProperties properties;

    private volatile boolean running;
    private volatile ScheduledExecutorService thread;

    /**
     * @param executorAvailable whether an {@code ApplyExecutor} is configured; asked at {@link #start()}
     */
    public FleetScheduler(BooleanSupplier executorAvailable, TenantEnumerationRepository tenants, FleetService fleets,
            WorkerSingletonGuard guard, FleetProperties properties) {
        this.executorAvailable = Objects.requireNonNull(executorAvailable, "executorAvailable");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.fleets = Objects.requireNonNull(fleets, "fleets");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * One pass over every tenant with an active instance and every {@code RUNNING} fleet of each. One fleet's failure never
     * stops the pass, and a pass's failure never stops the schedule. Public so a test can drive a pass without a thread.
     */
    public void pollOnce() {
        List<TenantId> candidates;
        try {
            candidates = tenants.tenantsWithActiveInstances();
        } catch (RuntimeException e) {
            LOG.warn("fleet scheduler: tenants could not be enumerated; retrying next interval ({})",
                    e.getClass().getSimpleName());
            return;
        }
        for (TenantId tenant : candidates) {
            List<UUID> running;
            try {
                running = fleets.runningFleets(tenant.value());
            } catch (RuntimeException e) {
                LOG.warn("fleet scheduler: one tenant's fleets could not be read ({})", e.getClass().getSimpleName());
                continue;
            }
            for (UUID fleet : running) {
                try {
                    fleets.advance(tenant.value(), fleet);
                } catch (RuntimeException e) {
                    // classifier only: an engine or driver message can carry a value
                    LOG.warn("fleet scheduler: one fleet could not be advanced ({})", e.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * Brings the next pass forward: latency only, never correctness - the periodic pass advances every fleet regardless.
     * Runs on the scheduler's own thread, so it can never race the periodic pass.
     */
    public void nudge() {
        ScheduledExecutorService s = thread;
        if (running && s != null) {
            s.execute(this::guardedPass);
        }
    }

    private void guardedPass() {
        if (!running) {
            return;
        }
        try {
            pollOnce();
        } catch (RuntimeException e) {
            // pollOnce catches per fleet; this is a safety net, because scheduleWithFixedDelay suppresses every later run
            // once one throws, which would stop the fleet silently while isRunning() keeps answering true
            LOG.warn("fleet scheduler: a pass failed unexpectedly ({})", e.getClass().getSimpleName());
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!properties.enabled()) {
            LOG.warn("fleet scheduler: withdrawn (sm.dp.fleet.enabled=false); a submitted fleet will stay RUNNING");
            return;
        }
        if (!executorAvailable.getAsBoolean()) {
            LOG.warn("fleet scheduler: no ApplyExecutor is configured in this context, so there is nothing to admit "
                    + "and the scheduler is NOT running");
            return;
        }
        // Fails startup on purpose (IllegalStateException) if another process holds the singleton: the one-advancer rule
        // is only a rule while exactly one process runs this. Idempotent for the process that already holds it.
        guard.acquireOrRefuseToStart();
        thread = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "fleet-scheduler");
            t.setDaemon(true);
            return t;
        });
        running = true;
        thread.scheduleWithFixedDelay(this::guardedPass, 0, properties.advanceInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        running = false;
        ScheduledExecutorService s = thread;
        thread = null;
        if (s == null) {
            return;
        }
        s.shutdown();
        try {
            // let the pass in flight finish its admission and its record: an interrupt mid-admission strands nothing (the
            // next process admits again under the same id) but it is noise, and the wait is short
            if (!s.awaitTermination(10, TimeUnit.SECONDS)) {
                s.shutdownNow();
            }
        } catch (InterruptedException e) {
            s.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
