package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.agent.AgentEnrolmentSweep;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The scheduled agent-enrolment sweep (plan 015 T4.6; {@code OR-140}, answering plan section 10 Q-C1 and Q-C2): the loop that
 * used to be a side effect of {@code listTargets}. It lives HERE, beside {@code ScheduleFirer}, because the sweep needs an answer
 * from each plane and the gateway is the one module permitted both {@code -api}s ({@code I7}).
 *
 * <h2>One pass</h2>
 * <ol>
 *   <li>{@link AgentEnrolmentSweep#organizations} - <b>the control plane's cross-organization read for this job, and this class
 *       is its only caller</b> ({@code TenantEnumerationBoundaryTest}; {@code OR-140} amends {@code OR-44}/{@code OR-49} to this
 *       extent and no further);</li>
 *   <li>per organization, {@link AgentTargetSweep#sweep}: page the candidates, ask the data plane which hold a connection,
 *       revoke the rest FIRST, look again, retire the target only if it is still absent. Both writes are audited to the system
 *       actor.</li>
 * </ol>
 *
 * <h2>Exactly once across instances</h2>
 * Matches {@code ScheduleFirer}, which takes no lock: every state move is CONDITIONAL on the row's state ({@code revokeIfActive}
 * updates {@code WHERE status = 'active'}, {@code markRetired} {@code WHERE retired_at IS NULL}), so two SM instances that read the
 * same candidate take turns in the database and the loser changes nothing and writes no audit row. The result is one revoke and one
 * retire per abandoned enrolment, one audit row each. Residual, stated: both instances may do the read work, and a pass whose
 * second data-plane read lands in the race with an agent's replay behaves as T3.5 documented (the single-statement window).
 * {@code OR-44} also mandates one SM replica per install.
 *
 * <p>A failure never stops the loop and never stops the rest of a pass. Property keys: {@code sm.gateway.agent-sweep.enabled}
 * (default {@code true}; {@code false} withdraws the job, and an interrupted enrolment then stays listed degraded until an operator
 * revokes and retires it) and {@code sm.gateway.agent-sweep.poll-interval} (default one minute; the abandon window itself is
 * {@code sm.dp.declare.abandon-after}).
 */
@Component
public class AgentEnrolmentSweepJob implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(AgentEnrolmentSweepJob.class);

    /** After {@code ScheduleFirer} ({@code Integer.MIN_VALUE + 2200}); stops in REVERSE order. */
    private static final int PHASE = Integer.MIN_VALUE + 2300;

    private final AgentEnrolmentSweep controlPlane;
    private final AgentTargetSweep sweep;
    private final boolean enabled;
    private final Duration interval;

    private volatile boolean running;
    private volatile ScheduledExecutorService thread;

    public AgentEnrolmentSweepJob(AgentEnrolmentSweep controlPlane, AgentTargetSweep sweep,
            @Value("${sm.gateway.agent-sweep.enabled:true}") boolean enabled,
            @Value("${sm.gateway.agent-sweep.poll-interval:PT1M}") Duration interval) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.sweep = Objects.requireNonNull(sweep, "sweep");
        this.enabled = enabled;
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("sm.gateway.agent-sweep.poll-interval must be a positive duration, was " + interval);
        }
        this.interval = interval;
    }

    /**
     * One pass over every organization the control plane names. Public so a test drives a pass without a thread. Never throws:
     * every failure is logged by class (an exception's message can carry a value) and the next pass asks again.
     */
    public void runOnce() {
        List<UUID> organizations;
        try {
            organizations = controlPlane.organizations(sweep.window());
        } catch (RuntimeException e) {
            LOG.warn("agent enrolment sweep: organizations with an abandoned enrolment could not be enumerated; retrying next interval ({})",
                    e.getClass().getSimpleName());
            return;
        }
        sweep.retainCursorsFor(organizations);
        for (UUID organization : organizations) {
            try {
                sweep.sweep(organization);
            } catch (RuntimeException e) {
                // AgentTargetSweep catches its own; this keeps one organization from stopping the rest if that ever changes
                LOG.warn("agent enrolment sweep: one organization could not be swept ({})", e.getClass().getSimpleName());
            }
        }
    }

    private void guardedPass() {
        if (!running) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            // scheduleWithFixedDelay suppresses every later run once one throws, which would stop the loop silently
            LOG.warn("agent enrolment sweep: a pass failed unexpectedly ({})", e.getClass().getSimpleName());
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!enabled) {
            LOG.warn("agent enrolment sweep: withdrawn (sm.gateway.agent-sweep.enabled=false); an interrupted enrolment stays degraded until revoked");
            return;
        }
        thread = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "agent-enrolment-sweep");
            t.setDaemon(true);
            return t;
        });
        running = true;
        thread.scheduleWithFixedDelay(this::guardedPass, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
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
