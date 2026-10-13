package com.solace.sm.dp.apply.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;
import com.solace.sm.dp.apply.persistence.WorkerSingletonGuard;
import com.solace.sm.dp.apply.reconcile.IndeterminateReconciler;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Plan 015 Final, review MAJOR-1: the startup sweep's deferred-resume thread must be gone before {@link ApplyWorker#stop()} releases
 * the {@link WorkerSingletonGuard}, and the wait is bounded by the worker's shutdown grace (not a literal), interrupting once.
 */
class DeferredResumeShutdownTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private record Rig(ApplyStartupRecovery recovery, ApplyWorker worker, WorkerSingletonGuard guard,
            AtomicBoolean resumeThreadAliveAtRelease, CountDownLatch inFlight, AtomicBoolean releasedOnce) { }

    /** A sweep with one agent-target request whose agent never returns, so the (tiny) grace is spent and the resume is in flight. */
    private Rig rig(long unwindMillis, boolean honoursInterrupt) throws Exception {
        TenantId tenant = new TenantId(UUID.randomUUID());
        ApplyRequestRow row = mock(ApplyRequestRow.class);
        ApplyExecutor executor = mock(ApplyExecutor.class);
        when(executor.resumableRunning(tenant)).thenReturn(List.of(row));
        CountDownLatch inFlight = new CountDownLatch(1);
        when(executor.resumeRunningOne(any(), any())).thenAnswer(inv -> {
            inFlight.countDown();
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(unwindMillis + 20_000);
            while (System.nanoTime() < until) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    if (honoursInterrupt) {
                        Thread.sleep(unwindMillis);        // the step unwinding after the interrupt
                        return null;
                    }
                    // an uninterruptible adapter call: keep going for the whole unwind time
                    long ignoring = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(unwindMillis);
                    while (System.nanoTime() < ignoring) {
                        Thread.onSpinWait();
                    }
                    return null;
                }
            }
            return null;
        });
        TenantEnumerationRepository tenants = mock(TenantEnumerationRepository.class);
        when(tenants.tenantsWithNonTerminalWork()).thenReturn(List.of(tenant));
        WorkerSingletonGuard guard = mock(WorkerSingletonGuard.class);
        AtomicBoolean aliveAtRelease = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        ApplyStartupRecovery recovery = new ApplyStartupRecovery(provider(executor), tenants, guard, new ApplyRecoveryStatus(),
                provider((IndeterminateReconciler) null), r -> Optional.of(() -> false), Duration.ofMillis(100));
        doAnswer(inv -> {
            released.set(true);
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().equals("apply-recovery-agent-grace") && t.isAlive()) {
                    aliveAtRelease.set(true);
                }
            }
            return null;
        }).when(guard).release();
        ApplyWorker worker = new ApplyWorker(provider(executor), null, tenants,
                new ApplyWorkerProperties(1, Duration.ofSeconds(1), Duration.ofMillis(500)), guard, new ApplyWorkerStatus(),
                provider((IndeterminateReconciler) null), 0, provider(recovery));
        recovery.start();
        assertTrue(inFlight.await(10, TimeUnit.SECONDS), "the deferred resume never started");
        return new Rig(recovery, worker, guard, aliveAtRelease, inFlight, released);
    }

    @Test
    void theGuardIsReleasedOnlyAfterTheDeferredResumeThreadIsGone() throws Exception {
        Rig rig = rig(200, true);
        rig.worker().stop();
        assertTrue(rig.releasedOnce().get(), "the guard is released once the thread has unwound");
        assertFalse(rig.resumeThreadAliveAtRelease().get(), "the deferred-resume thread was still alive when the guard was released");
    }

    @Test
    void aResumeThatCannotBeInterruptedInsideTheGraceKeepsTheGuardHeld() throws Exception {
        Rig rig = rig(3_000, false);
        long started = System.nanoTime();
        rig.worker().stop();
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        verify(rig.guard(), never()).release();
        assertTrue(tookMillis < 2_500, "bounded by two shutdown graces (500 ms each), not unbounded: " + tookMillis);
        assertEquals(false, rig.releasedOnce().get());
        Thread.sleep(3_200);   // let the stubborn daemon thread finish before the test ends
    }
}
