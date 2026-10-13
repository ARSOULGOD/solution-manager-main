package com.solace.sm.dp.apply.worker;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;
import com.solace.sm.dp.apply.persistence.WorkerSingletonGuard;
import com.solace.sm.dp.apply.reconcile.IndeterminateReconciler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

/**
 * Task 4b's startup sweep — across every tenant, {@code RUNNING} rows only.
 *
 * <p>Plan 005 routed this here by name: {@code resume} "has no production caller... [until] the
 * plan that adds an application class." Without this, a crashed apply stays {@code RUNNING} in
 * production forever, while every test passes.
 *
 * <p><b>Lives in {@code dp-core}, not {@code launcher}</b> — see {@link ApplyWorker}'s javadoc for
 * why: {@code arch-tests}' {@code launcherNeverDependsOnAnApplyExecutor} forbids the launcher from
 * holding this class's one dependency directly.
 *
 * <p><b>Runs before the HTTP connector accepts.</b> The connector is opened by
 * {@code WebServerStartStopLifecycle}, itself an ordinary {@link SmartLifecycle} bean whose
 * {@code start()} calls {@code WebServer.start()} — there is no separate, phase-independent
 * "{@code startWebServer()} during {@code finishRefresh()}" hook, an earlier version of this
 * javadoc's claim to the contrary. Spring's {@code DefaultLifecycleProcessor} starts every
 * {@code SmartLifecycle} bean in ascending {@link #getPhase()} order during
 * {@code AbstractApplicationContext.finishRefresh()}, and {@code WebServerStartStopLifecycle}'s
 * phase is measured (see {@link ApplyWorker}'s javadoc) at {@code Integer.MAX_VALUE - 2048} — far
 * above this class's phase. So by construction this sweep's {@link #start()} (synchronous, and
 * blocking until every enumerated tenant has been swept) has already returned by the time the
 * connector's own, much-later phase group runs. {@link #getPhase()} is set very early besides, so
 * this runs before {@link ApplyWorker} even if both were somehow reordered by a future change.
 *
 * <p><b>Migrations already happened by the time this runs</b> — {@code SmDataMigrationMain} is a
 * separate one-shot process the install runbook runs before this application starts
 * ({@code ApplyPersistenceConfiguration}'s own javadoc), and {@code smDataSchemaReadiness} refuses
 * to let ANY bean here touch an unmigrated schema. "After migrations" is therefore an architectural
 * fact, not something this class re-checks.
 *
 * <p><b>Never calls {@code outcomes.start}</b> — see {@code ApplyExecutor.resumeRunning}'s own
 * javadoc. Isolated twice over: once per tenant (a failure enumerating or sweeping one tenant does
 * not stop the rest) and once per request within {@code resumeRunning} itself.
 *
 * <p><b>BLOCKER fix (post-review): acquires {@link WorkerSingletonGuard} FIRST, before enumerating
 * a single tenant.</b> An earlier version of this class had no guard dependency at all — the guard
 * was acquired only later, inside {@link ApplyWorker#start()} (a LATER phase). Because this sweep's
 * phase runs first, a second process would enumerate and {@code resumeRunning} every tenant with a
 * {@code RUNNING} row BEFORE its own {@code ApplyWorker.start()} ever discovered it must refuse to
 * start. That resumes a row a live first process is still actively executing — the exact double
 * execution {@code WorkerSingletonGuard} exists to make impossible, defeated by ordering rather than
 * by the guard itself being wrong. The guard is idempotent for a second acquisition by the SAME
 * session (see its own javadoc), so {@link ApplyWorker#start()}'s own call lower down is now a
 * no-op in the common case and stays as defense in depth — e.g. if this bean is ever disabled while
 * the worker is not.
 *
 * <p><b>OR-146 (2): an in-flight request on an AGENT target waits, bounded, for its agent.</b> After an SM restart the agent
 * reconnects some seconds after this sweep (measured: the sweep at +4.7 s, the session at +6.5 to +9.9 s), and a sweep that
 * resumes the request before then cannot read the host and strands it {@code APPLY_INDETERMINATE} for the reconciler to settle
 * about 30 s later. So such a request (a probe says its placement is on an agent target and that agent's session is not live
 * yet) is DEFERRED: the sweep returns without it, and one daemon thread polls the agent's session, tenant-scoped, until it is
 * live (then the request resumes normally and ends {@code APPLIED}) or the grace
 * ({@code sm.dp.apply.worker.agent-session-grace}) is spent (then the request is resumed exactly as it always was, and lands
 * {@code APPLY_INDETERMINATE} for the reconciler: no new failure mode). The wait never runs on the startup thread, so it never
 * delays the connector or any later phase; dp-remote requests, and agent requests whose session is already live, resume inline as
 * before. The grace is ONE window shared by all deferred requests, and they are resumed serially on that one thread (a resume
 * in flight can take a verb ceiling), each re-checked for liveness immediately before its own resume. A request still waiting when
 * the process stops stays {@code RUNNING} for the next startup's sweep, as any interrupted resume does; {@link ApplyWorker#stop()}
 * waits for this thread (bounded by the shutdown grace) before it releases the singleton guard.
 */
public final class ApplyStartupRecovery implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ApplyStartupRecovery.class);

    /** Ahead of everything else, including {@link ApplyWorker}'s phase. */
    private static final int PHASE = Integer.MIN_VALUE + 1000;

    private final ObjectProvider<ApplyExecutor> executorProvider;
    private final TenantEnumerationRepository tenants;
    private final WorkerSingletonGuard guard;
    private final ApplyRecoveryStatus status;
    private final ObjectProvider<IndeterminateReconciler> reconcilerProvider;

    /** How often the deferred requests' sessions are looked at: well inside the measured 2 to 10 s reconnect. */
    private static final long POLL_MILLIS = 500;

    private final AgentSessionProbe agentProbe;
    private final Duration agentSessionGrace;
    private volatile Thread graceThread;
    private volatile boolean stopping;
    private volatile boolean ran;

    /** No agent probe: every request resumes inline, as before OR-146. */
    public ApplyStartupRecovery(ObjectProvider<ApplyExecutor> executorProvider,
            TenantEnumerationRepository tenants, WorkerSingletonGuard guard,
            ApplyRecoveryStatus status, ObjectProvider<IndeterminateReconciler> reconcilerProvider) {
        this(executorProvider, tenants, guard, status, reconcilerProvider, null, Duration.ZERO);
    }

    public ApplyStartupRecovery(ObjectProvider<ApplyExecutor> executorProvider,
            TenantEnumerationRepository tenants, WorkerSingletonGuard guard,
            ApplyRecoveryStatus status, ObjectProvider<IndeterminateReconciler> reconcilerProvider,
            AgentSessionProbe agentProbe, Duration agentSessionGrace) {
        this.agentProbe = agentProbe;
        this.agentSessionGrace = agentSessionGrace == null ? Duration.ZERO : agentSessionGrace;
        this.reconcilerProvider = reconcilerProvider;
        this.executorProvider = executorProvider;
        this.tenants = tenants;
        this.guard = guard;
        this.status = status;
    }

    @Override
    public synchronized void start() {
        ApplyExecutor executor = executorProvider.getIfAvailable();
        if (executor == null) {
            status.markSkipped("no ApplyExecutor bean is configured in this context -- the startup "
                    + "apply-recovery sweep is NOT running; any RUNNING row left by a crash before "
                    + "this build existed would stay RUNNING forever");
            log.warn(status.reason());
            ran = true;
            return;
        }

        // Fails startup on purpose (IllegalStateException) if a second pool already holds this --
        // see the class javadoc. Taken BEFORE the first enumeration, not after, and before a single
        // row is read.
        guard.acquireOrRefuseToStart();

        // MINOR fix (post-review): the plan states the sweep "never throws into startup", but an
        // earlier version left the enumeration call itself unguarded -- a transient database error
        // here would abort the WHOLE boot rather than being logged and leaving the sweep to the
        // next restart, exactly as a per-tenant or per-request failure already is below. The guard
        // acquisition above is deliberately NOT inside this try: refusing to start when a second
        // pool holds the singleton lock is the one exception that MUST escape.
        List<TenantId> candidates;
        try {
            candidates = tenants.tenantsWithNonTerminalWork();
        } catch (RuntimeException e) {
            status.markSkipped("could not enumerate tenants with non-terminal work at startup; no "
                    + "sweep ran, and any RUNNING row stays RUNNING until the next restart");
            log.warn(status.reason(), e);
            ran = true;
            return;
        }

        // Plan 010 T6.8: an APPLY_INDETERMINATE request is no longer left alone. It is handed to the reconciler by giving it a
        // bookkeeping row due now, and the sweep moves on: no host is observed here, so boot cannot wait on one (MINOR 9).
        IndeterminateReconciler reconciler = reconcilerProvider == null ? null : reconcilerProvider.getIfAvailable();
        int tenantCount = 0;
        List<Deferred> deferred = new ArrayList<>();
        for (TenantId t : candidates) {
            tenantCount++;
            if (reconciler != null) {
                try {
                    reconciler.enqueueIndeterminate(t);
                } catch (RuntimeException e) {
                    log.warn("startup apply-recovery sweep could not hand tenant {}'s indeterminate requests to the "
                            + "reconciler; the poll will", t.value(), e);
                }
            }
            try {
                // The classifier per request the plan calls for: resumeRunning's own return value
                // was previously discarded, which left this sweep's actual per-request outcome
                // unobservable from the logs.
                for (ApplyRequestRow row : executor.resumableRunning(t)) {
                    Optional<BooleanSupplier> live = agentSession(row);
                    if (live.isPresent() && !isLive(live.get())) {
                        deferred.add(new Deferred(t, row, live.get()));    // OR-146 (2): waits for its agent, off this thread
                        continue;
                    }
                    report(t, executor.resumeRunningOne(t, row));
                }
            } catch (RuntimeException e) {
                // Isolation at the TENANT level, on top of resumeRunning's own per-request
                // isolation: a failure enumerating or sweeping one tenant must not abort the sweep
                // for every other tenant with work outstanding.
                log.warn("startup apply-recovery sweep failed for tenant {}; leaving it for the "
                        + "next restart", t.value(), e);
            }
        }
        if (!deferred.isEmpty()) {
            awaitAgents(executor, deferred);
        }
        status.markRan("swept " + tenantCount + " tenant(s) with non-terminal work" + (deferred.isEmpty() ? ""
                : "; " + deferred.size() + " agent-target request(s) deferred: resumed serially off the startup thread, each when its agent's session is live or once a shared " + agentSessionGrace + " window is spent"));
        ran = true;
    }

    private void report(TenantId t, ApplyOutcome outcome) {
        if (outcome instanceof ApplyOutcome.Landed landed) {
            log.info("startup apply-recovery sweep resumed apply {} for tenant {}: "
                            + "landed on {}{}", landed.applyId().value(), t.value(),
                    landed.state(),
                    landed.errorClass() == null ? "" : " (" + landed.errorClass() + ")");
        } else {
            log.info("startup apply-recovery sweep resumed a request for tenant {}: {}",
                    t.value(), outcome);
        }
    }

    /** The live-session check when the request is on an agent target and a grace is configured; empty otherwise. */
    private Optional<BooleanSupplier> agentSession(ApplyRequestRow row) {
        if (agentProbe == null || agentSessionGrace.isZero()) {
            return Optional.empty();
        }
        try {
            return agentProbe.liveCheck(row);
        } catch (RuntimeException e) {
            return Optional.empty();       // cannot tell: resume inline, exactly as before OR-146
        }
    }

    private static boolean isLive(BooleanSupplier check) {
        try {
            return check.getAsBoolean();
        } catch (RuntimeException e) {
            return false;                  // an unreadable session is "not yet", until the grace is spent
        }
    }

    private record Deferred(TenantId tenant, ApplyRequestRow row, BooleanSupplier live) { }

    private void awaitAgents(ApplyExecutor executor, List<Deferred> deferred) {
        long deadline = System.nanoTime() + agentSessionGrace.toNanos();
        Thread thread = new Thread(() -> {
            List<Deferred> pending = new ArrayList<>(deferred);
            try {
                while (!pending.isEmpty() && !stopping) {
                    for (Iterator<Deferred> it = pending.iterator(); it.hasNext() && !stopping;) {
                        Deferred d = it.next();
                        // Re-checked per request, immediately before it would be resumed: an earlier resume can block for a verb
                        // ceiling, so neither "live" nor "spent" may be read from before it. One live session resumes normally even
                        // when the shared grace is already spent; a request whose agent is still away resumes (and lands
                        // APPLY_INDETERMINATE) only once the grace is spent.
                        boolean live = isLive(d.live());
                        if (live || System.nanoTime() - deadline >= 0) {
                            it.remove();
                            try {
                                report(d.tenant(), executor.resumeRunningOne(d.tenant(), d.row()));
                            } catch (RuntimeException e) {
                                log.warn("startup apply-recovery could not resume a deferred agent-target request for tenant {}; "
                                        + "leaving it for the next restart", d.tenant().value(), e);
                            }
                        }
                    }
                    if (!pending.isEmpty() && !stopping) {
                        Thread.sleep(POLL_MILLIS);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();     // shutdown: what is left stays RUNNING for the next sweep
            }
        }, "apply-recovery-agent-grace");
        thread.setDaemon(true);
        graceThread = thread;
        thread.start();
    }

    /** Tells the deferred-resume thread to start no further resume; one already in flight is not touched yet. */
    void signalStop() {
        stopping = true;
    }

    /**
     * MAJOR-1 (final review): the deferred-resume thread drives the same {@code RUNNING} rows the worker's singleton guard protects,
     * so the worker calls this BEFORE releasing the guard. A resume in flight gets {@code grace} to finish, then is interrupted
     * (its agent wait ends {@code APPLY_INDETERMINATE}, or the row stays {@code RUNNING} for the next sweep), then gets {@code grace}
     * again, exactly as the worker's own pool does. {@code false} means the thread is still alive and the guard must NOT be released.
     */
    boolean awaitDeferredResumes(Duration grace) {
        stopping = true;
        Thread thread = graceThread;
        if (thread == null || !thread.isAlive()) {
            return true;
        }
        try {
            thread.join(grace.toMillis());
            if (!thread.isAlive()) {
                return true;
            }
            thread.interrupt();
            thread.join(grace.toMillis());
            return !thread.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            thread.interrupt();
            return false;
        }
    }

    /** The fallback when no worker drained it first (the worker is disabled): the guard is released by the caller of this bean's own stop. */
    @Override
    public void stop() {
        awaitDeferredResumes(Duration.ofSeconds(5));
    }

    @Override
    public boolean isRunning() {
        return ran;
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
