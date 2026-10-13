package com.solace.sm.launcher;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.LifecycleResult;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plan 010 T6.6 (MINOR 16): a test-scope, in-process decorator around the REAL {@link RuntimeAdapter} bean. It
 * is not an sshd harness and it invents no host: the adapter underneath performs every real operation; this class only
 * decides, for one switchable window or one moment, what the engine is told about it.
 *
 * <h2>Three faults, each one-shot</h2>
 * <ul>
 *   <li><b>Connection loss after a write (scenario R1).</b> {@link #armConnectionLossAfterNextWrite()} arms it. The
 *       window opens when the next {@code materializeConfig} RETURNS (the write is on the host), and while it is open
 *       the observations, {@code status} and {@code assessRollback}, throw {@link RuntimeAdapterException}
 *       carrying {@link #CONNECTION_LOST}, as a dropped SSH session does. The window closes on the first
 *       {@code assessRollback} it refuses: that call is the engine's exhaustion path asking what to do, and a lost
 *       answer there is what lands the request {@code APPLY_INDETERMINATE} (without it the engine would observe the
 *       host, restore the pre-image and land {@code FAILED_ROLLED_BACK}, which is BE-D22's own designed path and not
 *       the reconciler's). Closing there also means the connection is back before the reconciler's first inspection,
 *       which runs only after the request has landed. Writes inside the window are NOT refused: the engine's bounded
 *       retry of the same intent writes the same bytes again, really, and {@link #configWrites()} counts it.</li>
 *   <li><b>A kill (scenario 11).</b> {@link #armKillAfterNextWrite()}: the next call after the next write returns throws
 *       {@link SimulatedKill}, an {@link Error}, so no {@code catch (RuntimeException)} in the engine or the worker
 *       survives it - the process is gone mid-apply with the file written and the journal open. Every call after that
 *       throws too, and makes no write.</li>
 *   <li><b>A hook before the restart verb (scenario 2).</b> {@link #beforeNextRestart(Runnable)} runs once, before the
 *       lifecycle verb reaches the host: a test uses it to take the broker down between "staged" and
 *       "restart-observed".</li>
 * </ul>
 *
 * <p>Counters are the observable the evidence records: {@link #configWrites()} is every call that writes the config
 * file (materialize, conditional rematerialize, restore), which is exactly the set of operations that issue the SFTP
 * write; the SSH transport itself is package-private to {@code dp-core} and is not counted from outside it.
 */
final class FaultInjectingRuntimeAdapter implements RuntimeAdapter {

    /** The transport classifier a dropped SSH session produces ({@code SshjDockerTransport}'s connect failure). */
    static final ErrorClass CONNECTION_LOST = new ErrorClass("ssh.connection.failed");

    /** The process died. An Error on purpose: nothing in the engine or the worker catches it. */
    static final class SimulatedKill extends Error {

        private static final long serialVersionUID = 1L;

        SimulatedKill() {
            super("simulated process kill (plan 010 T6.6 scenario 11)", null, false, false);
        }
    }

    private enum Loss {
        IDLE, ARMED, OPEN, CLOSED
    }

    private enum Kill {
        IDLE, ARMED, PENDING, DEAD
    }

    private final RuntimeAdapter delegate;
    private final AtomicInteger materializes = new AtomicInteger();
    private final AtomicInteger rematerializes = new AtomicInteger();
    private final AtomicInteger restores = new AtomicInteger();
    private final AtomicInteger restarts = new AtomicInteger();
    private final AtomicInteger stops = new AtomicInteger();

    private Loss loss = Loss.IDLE;
    private int writesWhenLossClosed = -1;
    private Kill kill = Kill.IDLE;
    private Runnable beforeRestart;

    FaultInjectingRuntimeAdapter(RuntimeAdapter delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    RuntimeAdapter delegate() {
        return delegate;
    }

    // ---- counters -------------------------------------------------------------------------------------------

    int materializes() {
        return materializes.get();
    }

    int rematerializes() {
        return rematerializes.get();
    }

    int restores() {
        return restores.get();
    }

    int restarts() {
        return restarts.get();
    }

    int stops() {
        return stops.get();
    }

    /** Every call that writes the config file: the operations that issue the SFTP write. */
    int configWrites() {
        return materializes.get() + rematerializes.get() + restores.get();
    }

    // ---- the connection-loss window (R1) --------------------------------------------------------------------

    synchronized void armConnectionLossAfterNextWrite() {
        loss = Loss.ARMED;
        writesWhenLossClosed = -1;
    }

    synchronized boolean connectionLossOpen() {
        return loss == Loss.OPEN;
    }

    /** True once the window has opened and then closed: the connection was lost after a write and restored. */
    synchronized boolean connectionLossClosed() {
        return loss == Loss.CLOSED;
    }

    /** {@link #configWrites()} at the moment the window closed, or -1 while it has not. */
    synchronized int configWritesWhenConnectionLossClosed() {
        return writesWhenLossClosed;
    }

    /** Restores the connection by hand (a test whose engine never reached the assessment). */
    synchronized void restoreConnection() {
        if (loss == Loss.OPEN || loss == Loss.ARMED) {
            writesWhenLossClosed = configWrites();
            loss = Loss.CLOSED;
        }
    }

    // ---- the kill (scenario 11) -----------------------------------------------------------------------------

    synchronized void armKillAfterNextWrite() {
        kill = Kill.ARMED;
    }

    synchronized boolean killed() {
        return kill == Kill.DEAD;
    }

    // ---- the restart hook (scenario 2) ----------------------------------------------------------------------

    synchronized void beforeNextRestart(Runnable hook) {
        this.beforeRestart = Objects.requireNonNull(hook, "hook");
    }

    // ---- the seam -------------------------------------------------------------------------------------------

    @Override
    public List<TargetReplica> replicas(PlacementRef placement) {
        dieIfKilled();
        return delegate.replicas(placement);
    }

    @Override
    public PreflightResult prepare(PlacementRef placement, long generation) {
        dieIfKilled();
        return delegate.prepare(placement, generation);
    }

    @Override
    public MaterializeResult materializeConfig(TargetReplica replica, long generation, RenderedConfig config) {
        dieIfKilled();
        materializes.incrementAndGet();
        MaterializeResult result = delegate.materializeConfig(replica, generation, config);
        afterAWrite();
        return result;
    }

    @Override
    public MaterializeResult rematerializeIfCurrent(TargetReplica replica, long generation, RenderedConfig config,
            String expectedCurrentHash) {
        dieIfKilled();
        rematerializes.incrementAndGet();
        MaterializeResult result = delegate.rematerializeIfCurrent(replica, generation, config, expectedCurrentHash);
        afterAWrite();
        return result;
    }

    @Override
    public LifecycleResult startOrUpdate(TargetReplica replica, long generation) {
        dieIfKilled();
        Runnable hook;
        synchronized (this) {
            hook = beforeRestart;
            beforeRestart = null;
        }
        if (hook != null) {
            hook.run();
        }
        restarts.incrementAndGet();
        return delegate.startOrUpdate(replica, generation);
    }

    @Override
    public LifecycleResult stop(TargetReplica replica) {
        dieIfKilled();
        stops.incrementAndGet();
        return delegate.stop(replica);
    }

    @Override
    public RuntimeStatus status(TargetReplica replica) {
        dieIfKilled();
        refuseWhileTheConnectionIsLost(false);
        return delegate.status(replica);
    }

    @Override
    public RollbackAssessment assessRollback(PlacementRef placement, long generation, String intendedHash) {
        dieIfKilled();
        refuseWhileTheConnectionIsLost(true);
        return delegate.assessRollback(placement, generation, intendedHash);
    }

    @Override
    public RestoreResult restore(PlacementRef placement, long generation, String targetHash) {
        dieIfKilled();
        restores.incrementAndGet();
        return delegate.restore(placement, generation, targetHash);
    }

    // ---- the fault machinery --------------------------------------------------------------------------------

    private synchronized void afterAWrite() {
        if (loss == Loss.ARMED) {
            loss = Loss.OPEN;
        }
        if (kill == Kill.ARMED) {
            kill = Kill.PENDING;
        }
    }

    private synchronized void dieIfKilled() {
        if (kill == Kill.PENDING || kill == Kill.DEAD) {
            kill = Kill.DEAD;
            throw new SimulatedKill();
        }
    }

    /** @param closesTheWindow the engine's exhaustion path asked what to do: the lost answer ends the loss */
    private synchronized void refuseWhileTheConnectionIsLost(boolean closesTheWindow) {
        if (loss != Loss.OPEN) {
            return;
        }
        if (closesTheWindow) {
            writesWhenLossClosed = configWrites();
            loss = Loss.CLOSED;
        }
        throw new RuntimeAdapterException(CONNECTION_LOST);
    }

    @Override
    public String toString() {
        return "FaultInjectingRuntimeAdapter[" + delegate.getClass().getSimpleName() + "]";
    }
}
