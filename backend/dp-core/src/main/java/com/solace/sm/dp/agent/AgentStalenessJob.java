package com.solace.sm.dp.agent;

import com.solace.sm.dp.apply.persistence.agent.JdbcAgentReportRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The scheduled agent-staleness verdict (plan 015 T4.6; {@code OR-140}, answering plan section 10 Q-C3; {@code AGW-D28} item 1):
 * the loop that used to be a side effect of {@code getHealth}. Every pass marks every organization's silent agent placements
 * {@code unreachable} and fires {@code instance.health} (through {@code HealthRecorder}, on a change only), for an instance nobody
 * is reading as much as for one somebody is.
 *
 * <h2>One pass</h2>
 * <ol>
 *   <li>{@link JdbcAgentReportRepository#tenantsWithStaleProbedPlacements} - <b>the data plane's cross-tenant read for this job,
 *       and this class is its only caller</b> ({@code TenantEnumerationBoundaryTest}; {@code OR-140} amends
 *       {@code OR-44}/{@code OR-49} to this extent and no further). It lists only tenants that HAVE a stale placement;</li>
 *   <li>per tenant, {@link JdbcAgentReportRepository#staleProbedInstances} (bound by tenant, {@code I5}), then per instance
 *       {@link AgentHealthStaleness#markStale}, which re-reads the watermark immediately before each write.</li>
 * </ol>
 *
 * <h2>Exactly once across instances</h2>
 * Matches {@code ScheduleFirer}, which takes no lock: the write is conditional. {@code HealthRecorder#record} writes in one
 * transaction and fires the event only if the row CHANGED, so two SM instances that both find a placement stale take turns in the
 * database, the loser changes nothing, and exactly one {@code instance.health} is fired. Residual, stated: both may do the read
 * work, and a report landing between {@code markStale}'s re-read and its write is overwritten by the stale verdict until the next
 * report (one statement wide; it heals within one report interval). {@code OR-44} also mandates one SM replica per install.
 *
 * <p>A failure never stops the loop and never stops the rest of a pass. Property keys: {@code sm.dp.agent.staleness-job.enabled}
 * (default {@code true}) and {@code sm.dp.agent.staleness-job.poll-interval} (default 30 s); the window itself is
 * {@code sm.dp.agent.report-staleness} (default 5 minutes).
 */
public final class AgentStalenessJob implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(AgentStalenessJob.class);

    /** After the data plane's workers and the gateway's {@code ScheduleFirer}; stops in REVERSE order. */
    private static final int PHASE = Integer.MIN_VALUE + 2250;

    /** What one pass needs; the production form is the repository and the per-instance step, a test supplies its own. */
    interface Source {
        Instant cutoff();

        List<UUID> tenants(Instant cutoff);

        List<UUID> instances(UUID tenant, Instant cutoff);

        void mark(UUID tenant, UUID instance);
    }

    private final Source source;
    private final boolean enabled;
    private final Duration interval;

    private volatile boolean running;
    private volatile ScheduledExecutorService thread;

    public AgentStalenessJob(JdbcAgentReportRepository reports, AgentHealthStaleness staleness, boolean enabled, Duration interval) {
        this(sourceOver(Objects.requireNonNull(reports, "reports"), Objects.requireNonNull(staleness, "staleness")), enabled, interval);
    }

    private static Source sourceOver(JdbcAgentReportRepository reports, AgentHealthStaleness staleness) {
        return new Source() {
            @Override
            public Instant cutoff() {
                return staleness.cutoff();
            }

            @Override
            public List<UUID> tenants(Instant cutoff) {
                return reports.tenantsWithStaleProbedPlacements(cutoff);
            }

            @Override
            public List<UUID> instances(UUID tenant, Instant cutoff) {
                return reports.staleProbedInstances(tenant, cutoff);
            }

            @Override
            public void mark(UUID tenant, UUID instance) {
                staleness.markStale(tenant, instance);
            }
        };
    }

    AgentStalenessJob(Source source, boolean enabled, Duration interval) {
        this.source = Objects.requireNonNull(source, "source");
        this.enabled = enabled;
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("sm.dp.agent.staleness-job.poll-interval must be a positive duration, was " + interval);
        }
        this.interval = interval;
    }

    /**
     * One pass over every organization with a stale agent placement. Public so a test drives a pass without a thread. Never throws:
     * every failure is logged by class and the next pass asks again.
     */
    public void runOnce() {
        Instant cutoff = source.cutoff();
        List<UUID> tenants;
        try {
            tenants = source.tenants(cutoff);
        } catch (RuntimeException e) {
            LOG.warn("agent staleness job: organizations with a silent agent could not be enumerated; retrying next interval ({})",
                    e.getClass().getSimpleName());
            return;
        }
        for (UUID tenant : tenants) {
            List<UUID> instances;
            try {
                instances = source.instances(tenant, cutoff);
            } catch (RuntimeException e) {
                LOG.warn("agent staleness job: one organization's silent instances could not be read ({})", e.getClass().getSimpleName());
                continue;
            }
            for (UUID instance : instances) {
                try {
                    source.mark(tenant, instance);
                } catch (RuntimeException e) {
                    LOG.warn("agent staleness job: one instance could not be marked ({})", e.getClass().getSimpleName());
                }
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
            LOG.warn("agent staleness job: a pass failed unexpectedly ({})", e.getClass().getSimpleName());
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!enabled) {
            LOG.warn("agent staleness job: withdrawn (sm.dp.agent.staleness-job.enabled=false); a silent agent's last verdict stands");
            return;
        }
        thread = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "agent-staleness-job");
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
