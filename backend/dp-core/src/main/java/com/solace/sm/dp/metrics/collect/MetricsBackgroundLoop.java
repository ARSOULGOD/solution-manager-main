package com.solace.sm.dp.metrics.collect;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;
import com.solace.sm.dp.metrics.rollup.RollupService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The metrics background loop (plan 011 P11-4.3, M-Q3 {@code OR-72}, widened by OQ-6 (a) / {@code OR-92}): the FOURTH
 * named caller of {@link TenantEnumerationRepository#tenantsWithActiveInstances()}, called ONCE per tick and fanned out
 * per tenant to (i) scrape planning, (ii) the rollup, on its own cadence, and (iii) the broker push-subscription
 * reconcile (P11-5.1: {@link PushReconcile}). One list, one named caller, one purpose widened from "the metrics
 * collector" to "the metrics background loop"; it is the tenants with an ACTIVE instance, so an idle tenant is collected
 * with no session and no apply in flight (the apply worker's list would miss every one of them, B4).
 *
 * <p>Two schedules, one thread each: {@link #tick} (default 10 s) reads the list and refreshes every tenant's plan and
 * rollup; {@link MetricsCollector#dispatch} (default 1 s) starts what is due, so a 10 s interval keeps its cadence. A
 * tenant that leaves the list (its last active instance retires) stops collecting on the next tick, and its rollups and
 * subscriptions stop with it. One tenant's failure never stops another's, and a tick's failure never stops the schedule.
 *
 * <p>Everything below the list is scoped to one tenant from there on (I5); this class holds no SQL.
 */
public final class MetricsBackgroundLoop implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsBackgroundLoop.class);

    private final TenantEnumerationRepository tenants;
    private final CollectionPlanBuilder builder;
    private final SourceScheduler scheduler;
    private final MetricsCollector collector;
    private final RollupService rollups;
    private final CollectorProperties properties;
    private final Clock clock;
    private final PushReconcile push;

    private ScheduledExecutorService executor;
    private volatile boolean running;

    /** The loop without broker push (plan 011 P11-4.3's shape): its third step does nothing. */
    public MetricsBackgroundLoop(TenantEnumerationRepository tenants, CollectionPlanBuilder builder,
            SourceScheduler scheduler, MetricsCollector collector, RollupService rollups,
            CollectorProperties properties, Clock clock) {
        this(tenants, builder, scheduler, collector, rollups, properties, clock, PushReconcile.NONE);
    }

    /**
     * @param push the THIRD per-tenant step (plan 011 P11-5.1, OQ-6 (a)): the broker push-subscription reconcile, fed from
     *             the same single {@code tenantsWithActiveInstances()} call as the other two
     */
    public MetricsBackgroundLoop(TenantEnumerationRepository tenants, CollectionPlanBuilder builder,
            SourceScheduler scheduler, MetricsCollector collector, RollupService rollups,
            CollectorProperties properties, Clock clock, PushReconcile push) {
        this.push = Objects.requireNonNull(push, "push");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.builder = Objects.requireNonNull(builder, "builder");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.collector = Objects.requireNonNull(collector, "collector");
        this.rollups = Objects.requireNonNull(rollups, "rollups");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One tick: the list once, then each tenant's plan and rollup, then the counts the workers left. */
    public void tick() {
        Instant now = clock.instant();
        List<TenantId> active = tenants.tenantsWithActiveInstances();
        for (TenantId tenant : active) {
            try {
                CollectionPlan plan = builder.build(tenant);
                scheduler.replace(tenant, plan.units(), now);
            } catch (RuntimeException e) {
                // the tenant keeps the plan it had; classifier only
                LOG.warn("metrics loop: a tenant's plan could not be built ({})", e.getClass().getSimpleName());
            }
            try {
                rollups.rollUp(tenant, now);
            } catch (RuntimeException e) {
                LOG.warn("metrics loop: a tenant's rollup failed ({})", e.getClass().getSimpleName());
            }
            try {
                push.reconcile(tenant);
            } catch (RuntimeException e) {
                // the organization keeps the subscriptions it had
                LOG.warn("metrics loop: a tenant's push reconcile failed ({})", e.getClass().getSimpleName());
            }
        }
        scheduler.retainTenants(active);
        collector.flushCounts();
        try {
            push.retainTenants(active);
            push.flushCounts();
        } catch (RuntimeException e) {
            LOG.warn("metrics loop: the push step's housekeeping failed ({})", e.getClass().getSimpleName());
        }
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------------

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "sm-metrics-loop");
            t.setDaemon(true);
            return t;
        });
        running = true;
        executor.scheduleWithFixedDelay(() -> guarded("tick", this::tick), 0, properties.tick().toMillis(),
                TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(() -> guarded("dispatch", collector::dispatch),
                properties.dispatchEvery().toMillis(), properties.dispatchEvery().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void guarded(String what, Runnable body) {
        try {
            if (running) {
                body.run();
            }
        } catch (Throwable t) {
            LOG.warn("metrics loop: a {} failed ({})", what, t.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
