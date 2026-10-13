package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.dp.api.apply.ApplySchedulePort;
import com.solace.sm.dp.api.apply.DueSchedule;
import com.solace.sm.dp.api.apply.ScheduleCommand.Operation;
import com.solace.sm.dp.api.apply.ScheduleReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The scheduled-apply firer (plan 012 T7; {@code OPS-Q10} (c), confirmed by {@code OR-122} (2)): the gateway's first
 * background loop. It lives HERE because the decision it must repeat - "may the submitter still do this?" - is the control
 * plane's, which the data plane may not call ({@code I7}), and the gateway is the one module permitted both {@code -api}s.
 *
 * <h2>One pass</h2>
 * <ol>
 *   <li>{@link ApplySchedulePort#tenantsWithDueSchedules} - <b>the only cross-organization enumeration this feature adds,
 *       and this is its only caller</b> ({@code ApplyScheduleBoundaryTest}); anything else that needs a tenant list is a new
 *       enumeration and needs an owner ruling;</li>
 *   <li>per organization, {@link ApplySchedulePort#due} - that organization's {@code PENDING} schedules whose window has
 *       opened, bound to the organization the data plane NAMED, never to a guess;</li>
 *   <li>per schedule: if its window has closed, {@code miss} (nobody is asked whether the submitter could have run it);
 *       otherwise the SUBMITTER's verbs are required AGAIN at the instance, in the order the submit asked them
 *       ({@code config.apply} or {@code config.rollback}, then {@code instance.restart}, then
 *       {@code instance.restart.overrideSafety}), and only then {@code admit}.</li>
 * </ol>
 *
 * <h2>What a failure means</h2>
 * A submitter who lost a verb, was deactivated, or whose instance is no longer visible to them is a REVOCATION: the
 * schedule is closed {@code REFUSED} with {@code schedule.authority.revoked} (W4) and nothing runs. Anything else - the
 * control plane not answering, a database error - is NOT a revocation and NOT a go-ahead: it is logged by class only and the
 * schedule stays {@code PENDING} for the next tick, until its window closes and it is {@code MISSED}. One schedule's failure
 * never stops the rest of the pass, and a pass's failure never stops the loop.
 *
 * <h2>A crash between the engine's commit and the schedule's record ({@code OR-125} (5))</h2>
 * The fire is two commits: the data plane's admission of the ordinary apply, and the schedule row's move to
 * {@code ADMITTED}. The data plane PRE-MINTS the apply's id from the schedule's own ({@code ScheduleApplyIds}) before it asks the
 * engine, and every call this loop makes ({@code admit}, {@code refuse}, {@code miss}) first reconciles a {@code PENDING}
 * schedule whose apply already exists: a retry after such a crash records that apply instead of admitting again, so a schedule
 * that ran is never left {@code PENDING}, closed {@code REFUSED}, or {@code MISSED}. This loop needs no new state for it: it simply
 * keeps calling, and a stale row is reconciled on its next tick, whether or not its window has since closed.
 *
 * <h2>Why not exactly-once</h2>
 * Nothing here needs a singleton: every state move is made by the data plane under a lock on the schedule's row and is
 * conditional on {@code PENDING}, so two firers (or a firer and a cancel) take turns and the loser reads
 * {@code NOT_PENDING}.
 *
 * <p>Property keys: {@code sm.gateway.schedule.enabled} (default {@code true}; {@code false} withdraws the loop and every
 * schedule then stays {@code PENDING} until it is missed) and {@code sm.gateway.schedule.poll-interval} (default 15 s).
 */
@Component
public class ScheduleFirer implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleFirer.class);

    /**
     * After {@code ApplyWorker} ({@code Integer.MIN_VALUE + 2000}), the startup sweep and {@code FleetScheduler}
     * ({@code + 2100}), so an admitted apply finds a running worker; far below the web server's. Stops in REVERSE order.
     */
    private static final int PHASE = Integer.MIN_VALUE + 2200;

    private final ApplySchedulePort schedules;
    private final AuthorizationService authorization;
    private final Clock clock;
    private final boolean enabled;
    private final Duration pollInterval;

    private volatile boolean running;
    private volatile ScheduledExecutorService thread;

    public ScheduleFirer(ApplySchedulePort schedules, AuthorizationService authorization,
            @Qualifier("smDataClock") Clock clock,
            @Value("${sm.gateway.schedule.enabled:true}") boolean enabled,
            @Value("${sm.gateway.schedule.poll-interval:PT15S}") Duration pollInterval) {
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.enabled = enabled;
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("sm.gateway.schedule.poll-interval must be a positive duration, was "
                    + pollInterval);
        }
        this.pollInterval = pollInterval;
    }

    /**
     * One pass over every organization with a due schedule. Public so a test drives a pass without a thread. Never throws:
     * every failure is logged by class (an exception's message can carry a value) and the schedule stays {@code PENDING}.
     */
    public void fireDue() {
        Instant passStart = clock.instant();
        List<UUID> organizations;
        try {
            organizations = schedules.tenantsWithDueSchedules(passStart);
        } catch (RuntimeException e) {
            LOG.warn("schedule firer: organizations with a due schedule could not be enumerated; retrying next interval ({})",
                    e.getClass().getSimpleName());
            return;
        }
        for (UUID organization : organizations) {
            List<DueSchedule> due;
            try {
                due = schedules.due(organization, passStart);
            } catch (RuntimeException e) {
                LOG.warn("schedule firer: one organization's due schedules could not be read ({})",
                        e.getClass().getSimpleName());
                continue;
            }
            for (DueSchedule schedule : due) {
                try {
                    fire(organization, schedule);
                } catch (RuntimeException e) {
                    // not a revocation and not a go-ahead: the schedule stays PENDING and the next tick asks again
                    LOG.warn("schedule firer: one schedule could not be fired and stays pending ({})",
                            e.getClass().getSimpleName());
                }
            }
        }
    }

    private void fire(UUID organization, DueSchedule schedule) {
        // A fresh reading per schedule: a pass over a backlog must not spend a control-plane round trip on a schedule whose
        // window closed while the pass worked through the ones before it. It only CHOOSES the path: the data plane reads its
        // own clock after it holds the row's lock and decides the window (and records the time) with that, so a round trip
        // or a lock wait between this reading and the admission cannot admit a schedule after its window (W3 review W3B-1).
        if (schedule.expiredAt(clock.instant())) {
            schedules.miss(organization, schedule.scheduleId());
            return;
        }
        if (!submitterStillMay(organization, schedule)) {
            schedules.refuse(organization, schedule.scheduleId(), ScheduleReason.AUTHORITY_REVOKED);
            return;
        }
        schedules.admit(organization, schedule.scheduleId());
    }

    /**
     * Whether the SUBMITTER still holds every verb the submit asked, at the instance. A denial or an invisible instance
     * answers {@code false}; any other failure propagates (unknown is not revoked).
     *
     * <p><b>Why a {@link ResourceNotFoundException} is {@code authority.revoked}, not {@code target.gone}</b> (Fable F-9). The
     * authorization API answers "no such resource" for THREE states it deliberately does not tell apart (the D10 existence
     * rule): the instance is gone, the instance is out of the submitter's scope, and the submitter is deactivated (an
     * inactive principal resolves to nothing). The first is a target fact; the other two are exactly the revocation W4
     * names, and a deactivated submitter must read {@code schedule.authority.revoked}. Mapping the not-found to
     * {@code target.gone} would mislabel W4. A genuinely gone instance or placement that the submitter can still see is
     * reached later, at admission, and closes {@code schedule.target.gone} there.
     */
    private boolean submitterStillMay(UUID organization, DueSchedule schedule) {
        PrincipalRef submitter = new PrincipalRef(schedule.requestedBy());
        ResourceRef instance = ResourceRef.of(ResourceKind.INSTANCE, schedule.instanceId(), new TenantId(organization));
        try {
            for (Permission verb : verbsOf(schedule)) {
                authorization.require(submitter, verb, instance);
            }
            return true;
        } catch (AccessDeniedException | ResourceNotFoundException revoked) {
            return false;
        }
    }

    /** The verbs {@code ApplyOrchestrator} asks at submit, in its order. */
    static List<Permission> verbsOf(DueSchedule schedule) {
        List<Permission> verbs = new ArrayList<>();
        verbs.add(schedule.operation() == Operation.ROLLBACK ? Permission.CONFIG_ROLLBACK : Permission.CONFIG_APPLY);
        if (!"manual".equals(schedule.strategy())) {
            verbs.add(Permission.INSTANCE_RESTART);
            if (ApplyOrchestrator.OVERRIDES_SAFETY.contains(schedule.strategy())) {
                verbs.add(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY);
            }
        }
        return verbs;
    }

    private void guardedPass() {
        if (!running) {
            return;
        }
        try {
            fireDue();
        } catch (RuntimeException e) {
            // fireDue catches per schedule; this is a safety net, because scheduleWithFixedDelay suppresses every later run
            // once one throws, which would stop the loop silently while isRunning() keeps answering true
            LOG.warn("schedule firer: a pass failed unexpectedly ({})", e.getClass().getSimpleName());
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!enabled) {
            LOG.warn("schedule firer: withdrawn (sm.gateway.schedule.enabled=false); a schedule will stay PENDING until it is missed");
            return;
        }
        thread = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "schedule-firer");
            t.setDaemon(true);
            return t;
        });
        running = true;
        thread.scheduleWithFixedDelay(this::guardedPass, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
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
            // let the pass in flight finish its admission and its record; the next process would admit under the row's lock
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
