package com.solace.sm.dp.apply.worker;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.ApplyClaimRepository;
import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;
import com.solace.sm.dp.apply.persistence.WorkerSingletonGuard;
import com.solace.sm.dp.apply.reconcile.IndeterminateReconciler;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

/**
 * Task 4a's durable apply worker — PostgreSQL is the queue. A bounded pool of poller threads, each
 * independently: enumerate tenants with non-terminal work, attempt the claim for each, and if it
 * wins, run the claimed request through the engine. The trigger is a bounded poll
 * ({@link ApplyWorkerProperties#pollInterval}), plus an optional in-process {@link #nudge()} — the
 * poll is what makes {@code 202} a promise; the nudge is latency, never correctness. Nothing in
 * this build calls {@code nudge()} yet — Task 4's HTTP submission path (a separate slice) is what
 * will, once it exists.
 *
 * <p><b>Lives in {@code dp-core}, not {@code launcher}.</b> An earlier draft of this class lived in
 * {@code com.solace.sm.launcher}, exactly where plan 007's own task text places it — but
 * {@code arch-tests}' {@code CompositionBoundaryTest} carries {@code launcherNeverDependsOnAnApplyExecutor}
 * and {@code launcherDoesNotTouchDataAccessOrMigrations}, both with their own planted-violation
 * proofs, and both fired against that draft: the launcher is a PURE composition root that imports
 * configurations owning their own beans ({@code ApplyPersistenceConfiguration},
 * {@code ApplyEngineConfiguration}) and may never hold an {@link ApplyExecutor} or a
 * {@code DataSource} itself. This class belongs beside the engine it drives, in the same module
 * that already owns {@code ApplyExecutor}, {@code ApplyClaimRepository} and
 * {@code TenantEnumerationRepository} — {@link ApplyWorkerConfiguration} is what the launcher
 * imports, exactly as it already imports the other two dp-core configurations.
 *
 * <p><b>Production-wiring honesty:</b> no {@link ApplyExecutor} bean exists in production today
 * (there is no production {@code RuntimeAdapter}/{@code RenderedConfigSource} yet — only
 * {@code demo-launcher} builds those, and production may not import it). This class therefore takes
 * an {@link ObjectProvider} rather than a required {@link ApplyExecutor}: when the provider yields
 * nothing, {@link #start()} logs a WARN naming exactly what is missing, records that in
 * {@link ApplyWorkerStatus}, and does nothing further — no scheduler, no thread pool, no singleton
 * lock. Wiring it this way (rather than {@code @ConditionalOnBean}) also sidesteps a real Spring
 * caveat: conditional bean evaluation across ordinary {@code @Configuration} classes is not
 * guaranteed to see a bean a LATER class defines, where {@code ObjectProvider} defers resolution to
 * the moment it is actually needed.
 */
public final class ApplyWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ApplyWorker.class);

    /**
     * Measured with {@code javap} against the exact Boot version this build pins (3.5.16):
     * {@code WebServerStartStopLifecycle.getPhase()} (the bean whose {@code start()} actually opens
     * the connector) returns {@code Integer.MAX_VALUE - 2048} (2147481599) — NOT
     * {@link SmartLifecycle}'s default phase (0), as an earlier version of this javadoc claimed
     * without checking. Spring's {@code DefaultLifecycleProcessor} starts every
     * {@link SmartLifecycle} bean in ascending phase order during context refresh, so any phase far
     * below that huge constant — in particular an unannotated bean's default of 0, and certainly
     * this class's negative one — is guaranteed to start in an earlier phase group, unconditionally
     * before the web server's, with no dependence on bean registration order (an earlier version of
     * this file relied on registration order instead and, measured, started and warned AFTER
     * "Tomcat started" in the log — the ordering conclusion below was always correct; only this
     * javadoc's stated reason for it was wrong). Later than {@link ApplyStartupRecovery}'s phase, so
     * the sweep (if it runs) completes before the worker starts claiming.
     */
    private static final int PHASE = Integer.MIN_VALUE + 2000;

    private final ObjectProvider<ApplyExecutor> executorProvider;
    private final ApplyClaimRepository claims;
    private final TenantEnumerationRepository tenants;
    private final ApplyWorkerProperties props;
    private final ApplyWorkerStatus status;
    private final WorkerSingletonGuard guard;
    private final ObjectProvider<IndeterminateReconciler> reconcilerProvider;

    private volatile boolean running;
    private ScheduledExecutorService scheduler;
    private ExecutorService pool;

    /**
     * MAJOR fix (post-review): bounds outstanding claims to the pool's own size.
     *
     * <p>An earlier version claimed every claimable {@code REQUESTED} row of every tenant in one
     * poll tick, handing all of them to {@code pool.execute} regardless of {@link #props}'
     * {@code poolSize} — an in-memory handoff of rows the plan forbids, and a violation of the
     * crash-table meaning of {@code RUNNING} ("after the claim commits, DURING execution"): a row
     * could be claimed (and therefore {@code RUNNING}) for a long time before a free thread ever
     * reached it, while sitting unexecuted on the unbounded queue behind {@code newFixedThreadPool}.
     * A graceful shutdown whose {@code awaitTermination} then timed out discarded every queued task
     * via {@code shutdownNow}, stranding each one {@code RUNNING} for no reason beyond the queue
     * depth. One permit per pool slot makes a claim and a worker thread's availability the SAME
     * event: {@link #drainOneTenant} acquires a permit BEFORE claiming (so a claim only ever happens
     * when a thread is about to be free to run it), and {@link #submit} releases it once the task
     * is actually handed to the pool (or immediately, if claiming or submission failed) — see
     * {@code ApplyWorkerBoundedClaimIT.theWorkerPoolIsBoundedAndRespectsItsConfiguredSize}.
     */
    private Semaphore capacity;

    /**
     * Plan 015 T3.1 (P15-6): at most {@code agentCap} of the claimed requests address an agent target at any moment. It is
     * counted here, on the one poll thread that claims, and released when the request leaves the pool; when it is reached the
     * claim query skips agent-target requests, so a worker thread is always left for {@code dp-remote}.
     */
    private final int agentCap;
    private final ObjectProvider<ApplyStartupRecovery> recoveryProvider;
    private final AtomicInteger agentInFlight = new AtomicInteger();

    /** A worker with no agent cap: every pre-agent suite. Production passes the boot-validated cap. */
    public ApplyWorker(ObjectProvider<ApplyExecutor> executorProvider, ApplyClaimRepository claims,
            TenantEnumerationRepository tenants, ApplyWorkerProperties props,
            WorkerSingletonGuard guard, ApplyWorkerStatus status,
            ObjectProvider<IndeterminateReconciler> reconcilerProvider) {
        this(executorProvider, claims, tenants, props, guard, status, reconcilerProvider, props.poolSize());
    }

    public ApplyWorker(ObjectProvider<ApplyExecutor> executorProvider, ApplyClaimRepository claims,
            TenantEnumerationRepository tenants, ApplyWorkerProperties props,
            WorkerSingletonGuard guard, ApplyWorkerStatus status,
            ObjectProvider<IndeterminateReconciler> reconcilerProvider, int agentCap) {
        this(executorProvider, claims, tenants, props, guard, status, reconcilerProvider, agentCap, null);
    }

    /**
     * {@code recovery}: the startup sweep's deferred-resume thread (OR-146 (2)); {@link #stop()} waits for it, bounded by the shutdown
     * grace, BEFORE releasing the singleton guard, so a deferred resume never runs unguarded (final review MAJOR-1).
     */
    public ApplyWorker(ObjectProvider<ApplyExecutor> executorProvider, ApplyClaimRepository claims,
            TenantEnumerationRepository tenants, ApplyWorkerProperties props,
            WorkerSingletonGuard guard, ApplyWorkerStatus status,
            ObjectProvider<IndeterminateReconciler> reconcilerProvider, int agentCap,
            ObjectProvider<ApplyStartupRecovery> recoveryProvider) {
        if (agentCap < 0) {
            throw new IllegalArgumentException("the agent cap is not negative");
        }
        this.agentCap = agentCap;
        this.recoveryProvider = recoveryProvider;
        this.reconcilerProvider = reconcilerProvider;
        this.executorProvider = executorProvider;
        this.claims = claims;
        this.tenants = tenants;
        this.props = props;
        this.status = status;
        this.guard = guard;
    }

    @Override
    public synchronized void start() {
        ApplyExecutor executor = executorProvider.getIfAvailable();
        if (executor == null) {
            status.markNotRunning("no ApplyExecutor bean is configured in this context -- there is "
                    + "no production RuntimeAdapter/RenderedConfigSource yet, so the durable apply "
                    + "worker is NOT running; every admitted apply would sit in REQUESTED forever");
            log.warn(status.reason());
            return;
        }
        // Fails startup on purpose (IllegalStateException) if a second pool already holds this --
        // R8's ruling is CONTINGENT on there being exactly one, system-wide. In the common case
        // ApplyStartupRecovery's own, EARLIER-phase start() already acquired this (see its
        // javadoc), so this call is an idempotent no-op; it stays here as defense in depth, and as
        // the property this class's own correctness depends on regardless of who took it first.
        guard.acquireOrRefuseToStart();

        capacity = new Semaphore(props.poolSize());
        pool = Executors.newFixedThreadPool(props.poolSize(),
                runnable -> daemonThread(runnable, "apply-worker"));
        scheduler = Executors.newSingleThreadScheduledExecutor(
                runnable -> daemonThread(runnable, "apply-worker-poll"));
        running = true;
        status.markRunning("apply worker (pool size " + props.poolSize() + ")");
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                pollOnce(executor);
            } catch (RuntimeException e) {
                // A safety net, not the primary defence (every call inside pollOnce already has
                // its own try/catch): scheduleWithFixedDelay's own contract suppresses every LATER
                // execution once one throws, which would silently and permanently stop polling
                // while ApplyWorkerStatus keeps reporting running() -- an escape here must not be
                // allowed to be the last poll tick this process ever runs.
                log.warn("apply worker poll tick failed unexpectedly; the scheduler will retry next "
                        + "interval regardless", e);
            }
        }, 0, props.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    private static Thread daemonThread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /**
     * An optional latency-only trigger: schedules one extra poll tick right away rather than
     * waiting for the next interval. Never the only path to correctness — nothing calls this yet.
     */
    public void nudge() {
        ScheduledExecutorService s = scheduler;
        ApplyExecutor executor = executorProvider.getIfAvailable();
        if (running && s != null && executor != null) {
            s.execute(() -> pollOnce(executor));
        }
    }

    /**
     * One poll tick. The per-tick {@code tenantsWithNonTerminalWork()} call is a steady-state,
     * cross-tenant read — an {@code I5} exception ruled by owner ruling {@code OR-44}
     * ({@code docs/design/DECISIONS.md}, 2026-09-28): one column, read-only, in the persistence
     * package, feeding the per-tenant scoped loop below, safe because exactly one Solution Manager
     * replica runs this worker ({@code WorkerSingletonGuard}; the install runbook mandates
     * Kubernetes {@code strategy: Recreate}). See {@code TenantEnumerationRepository}'s own javadoc,
     * which also names the only other permitted cross-tenant list ({@code OR-49}).
     */
    private void pollOnce(ApplyExecutor executor) {
        if (!running) {
            return;
        }
        List<TenantId> candidates;
        try {
            candidates = tenants.tenantsWithNonTerminalWork();
        } catch (RuntimeException e) {
            log.warn("apply worker poll tick could not enumerate tenants; retrying next interval", e);
            return;
        }
        for (TenantId t : candidates) {
            if (!running) {
                return;
            }
            drainOneTenant(t, executor);
            reconcileIfDue(t);
        }
    }

    /**
     * Plan 010 T6.8: the poll also runs due reconciliations of {@code APPLY_INDETERMINATE} requests. The tenant is one
     * {@code OR-44}'s existing enumeration already yielded (a tenant whose only open row is {@code APPLY_INDETERMINATE} is
     * among them), so the reconciler adds no cross-tenant read. The cheap "is anything due" read is made on this thread; the
     * reconciliation itself talks to a host, so it runs on the worker pool, under the same capacity permits as a claimed
     * apply - a slow host cannot stall the poll, and cannot take more threads than the pool has.
     */
    private void reconcileIfDue(TenantId t) {
        IndeterminateReconciler reconciler = reconcilerProvider == null ? null : reconcilerProvider.getIfAvailable();
        Semaphore c = capacity;
        ExecutorService p = pool;
        if (reconciler == null || c == null || p == null) {
            return;
        }
        // A reconciliation re-drives a request and may restart a replica on an AGENT target (a wait of up to the verb ceiling),
        // and the tick cannot tell which tenant requests do. So it counts against the same cap as a claimed agent request
        // (review finding 3): otherwise one claimed agent apply plus one due reconciliation take both threads of a pool of two.
        if (agentInFlight.get() >= agentCap) {
            return;
        }
        try {
            if (!reconciler.hasDue(t) || !c.tryAcquire()) {
                return;
            }
            agentInFlight.incrementAndGet();
        } catch (RuntimeException e) {
            log.warn("apply worker could not check tenant {} for due reconciliations; retrying next interval",
                    t.value(), e);
            return;
        }
        try {
            p.execute(() -> {
                try {
                    reconciler.runDue(t);
                } catch (RuntimeException e) {
                    log.warn("reconciliation for tenant {} did not complete cleanly inside the durable worker",
                            t.value(), e);
                } finally {
                    agentInFlight.decrementAndGet();
                    c.release();
                }
            });
        } catch (RejectedExecutionException e) {
            agentInFlight.decrementAndGet();
            c.release();   // the pool is shutting down; the work is durable and is found again after restart
        }
    }

    /**
     * Claims and submits every claimable row of one tenant this tick — not just the first, but
     * NEVER more than there is spare capacity for: a permit is acquired BEFORE the claim, so a row
     * only ever moves {@code REQUESTED → RUNNING} when a worker slot is (about to be) free for it.
     * When every permit is taken, this tenant's remaining claimable rows are simply left
     * {@code REQUESTED} for a later tick — indistinguishable from "not yet polled", which is exactly
     * the property {@code aCrashAfterAdmissionButBeforeAnyClaimIsPickedUpByTheNextPoll} already
     * relies on.
     */
    private void drainOneTenant(TenantId t, ApplyExecutor executor) {
        while (running) {
            Semaphore c = capacity;
            if (c == null || !c.tryAcquire()) {
                return;   // no free worker slot this tick; the row stays REQUESTED for the next one
            }
            Optional<ApplyClaimRepository.Claim> claimed;
            try {
                claimed = claims.claimNext(t, agentInFlight.get() < agentCap);
            } catch (RuntimeException e) {
                c.release();
                log.warn("apply worker could not attempt a claim for tenant {}; retrying next "
                        + "interval", t.value(), e);
                return;
            }
            if (claimed.isEmpty()) {
                c.release();
                return;
            }
            if (claimed.get().agent()) {
                agentInFlight.incrementAndGet();
            }
            submit(t, claimed.get(), executor);
        }
    }

    /** The permit acquired by {@link #drainOneTenant} is released here, on every exit path. */
    private void submit(TenantId t, ApplyClaimRepository.Claim claim, ApplyExecutor executor) {
        ApplyId id = claim.id();
        ExecutorService p = pool;
        if (p == null) {
            release(claim);
            return;
        }
        try {
            p.execute(() -> {
                try {
                    executor.runAdmitted(t, id);
                } catch (RuntimeException e) {
                    // One poisoned request must not take the scheduler or its sibling workers down
                    // with it -- the engine's own bounded retry/terminal mapping already decided this
                    // row's fate; a RuntimeException escaping here is the engine reporting it could not
                    // even land, which is logged and left for the next startup's recovery sweep.
                    log.warn("apply {} for tenant {} did not land cleanly inside the durable worker",
                            id.value(), t.value(), e);
                } finally {
                    release(claim);
                }
            });
        } catch (RejectedExecutionException e) {
            // The claim already committed (the row is RUNNING) before this call raced a concurrent
            // stop()'s pool.shutdown(). Exactly like a claim that outlives the shutdown grace
            // deadline below, the row is left RUNNING, recoverable by the next startup sweep, not
            // corrupted -- and this exception must not escape into the scheduler (see start()'s own
            // safety net) or silently stop every later poll tick.
            log.warn("apply {} for tenant {} was claimed but the worker pool was already shutting "
                    + "down; left RUNNING for the next startup sweep", id.value(), t.value(), e);
            release(claim);
        }
    }

    /** Frees the worker slot, and the agent slot when the request held one. */
    private void release(ApplyClaimRepository.Claim claim) {
        if (claim.agent()) {
            agentInFlight.decrementAndGet();
        }
        capacity.release();
    }

    /** Requests on agent targets this worker holds right now: what the cap bounds. */
    int agentInFlight() {
        return agentInFlight.get();
    }

    /**
     * MINOR fix (post-review): {@link #stop()} used to call {@link #guard}{@code .release()}
     * unconditionally, including when {@code awaitTermination} timed out and {@code shutdownNow()}
     * only INTERRUPTED the pool's threads — which does nothing at all against a thread blocked
     * inside a non-interruptible adapter call (SSH or Docker I/O, most notably). Releasing the
     * singleton lock at that point would drop the one guarantee {@code WorkerSingletonGuard}
     * exists for while this process's own worker thread might still be mid-adapter-call: a second
     * process booting at that exact moment would win the now-free lock, run its startup sweep, and
     * settle the still-live attempt as {@code TIMED_OUT} — the double-execution class R8's "no v1
     * lease" ruling is supposed to make impossible, reached on the shutdown path instead of the
     * crash path.
     *
     * @return whether the pool actually terminated (directly, or after a bounded second wait once
     *         {@code shutdownNow()} was called) — {@code true} always when there was no pool to wait
     *         on
     */
    private boolean drainPool() {
        if (pool == null) {
            return true;
        }
        pool.shutdown();
        try {
            if (pool.awaitTermination(props.shutdownGrace().toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            // Beyond the grace deadline: interrupt. Whatever a worker thread was mid-claim on is
            // not corrupted: a thread that does not unwind leaves its row RUNNING for Task 4b's startup sweep on the next boot,
            // exactly as an ungraceful crash would; an agent LIFECYCLE wait that is interrupted lands APPLY_INDETERMINATE
            // instead (OR-146 (1)), for the reconciler.
            pool.shutdownNow();
            // A thread that DOES respond to interruption (ordinary blocking Java I/O, the common
            // case) unwinds within milliseconds of being interrupted; this second, bounded wait is
            // what tells that case apart from one that does not respond at all, so the caller knows
            // whether it is actually safe to release the singleton lock below.
            return pool.awaitTermination(props.shutdownGrace().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            return false;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        status.markNotRunning("stopped");
        if (scheduler != null) {
            scheduler.shutdown();
        }
        ApplyStartupRecovery recovery = recoveryProvider == null ? null : recoveryProvider.getIfAvailable();
        if (recovery != null) {
            recovery.signalStop();
        }
        boolean drained = drainPool();
        if (drained && recovery != null) {
            // The startup sweep's deferred agent-target resumes run under THIS guard; release it only once that thread is gone.
            drained = recovery.awaitDeferredResumes(props.shutdownGrace());
        }
        if (drained) {
            guard.release();
        } else {
            // The lock is deliberately left held: it is dropped only when this process's own
            // database session ends (process exit, or the pool's connection being discarded),
            // never by this method pretending an undrained shutdown was clean.
            log.warn("apply worker pool (or the startup sweep's deferred resumes) did not terminate during shutdown; leaving the worker "
                    + "singleton lock held rather than releasing it while a worker thread may "
                    + "still be driving an adapter call -- a second process's startup sweep must "
                    + "not be able to resume this row while this process is still touching it");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
