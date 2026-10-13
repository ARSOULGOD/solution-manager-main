package com.solace.sm.dp.metrics.rollup;

import com.solace.sm.dp.metrics.RunwayDegradation;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the {@code metrics_raw} partition runway (plan 011 P11-3.2; M-Q13 c', Review Focus 5): at boot, after readiness,
 * and then daily, on the shared {@code metricsExecutor}.
 *
 * <p><b>Never boot-fatal and never fatal at all.</b> A database whose runway could not be extended must still let config,
 * apply, health and login come up; only metrics ingest degrades, and that is counted. A failure here (the function's own
 * lock timeout is re-raised after its three attempts) is logged by class only, tells {@link RunwayDegradation} so every
 * placement that has been ingesting counts {@code RUNWAY_DEGRADED}, and is retried at the next daily run.
 */
public final class PartitionRunway {

    private static final Logger LOG = LoggerFactory.getLogger(PartitionRunway.class);

    /** The runway function's runtime-role face: returns the first day of the first month with no partition. */
    public interface Source {
        LocalDate ensure();
    }

    /**
     * Another owner-owned daily function that rides the same loop (plan 015 T4.5: {@code sm_data.agent_gateway_maintenance()},
     * {@code OR-77}'s pattern). It runs after the runway keep, whatever that keep's outcome; one job's failure is logged by class and
     * never reaches the runway's degradation count or another job.
     */
    @FunctionalInterface
    public interface DailyJob {
        void run();
    }

    private final Source source;
    private final ScheduledExecutorService executor;
    private final RollupProperties properties;
    private final Clock clock;
    private final RunwayDegradation degradation;
    private final List<DailyJob> dailyJobs;
    private volatile LocalDate runwayEnd;
    private ScheduledFuture<?> daily;

    public PartitionRunway(Source source, ScheduledExecutorService executor, RollupProperties properties, Clock clock,
            RunwayDegradation degradation) {
        this(source, executor, properties, clock, degradation, List.of());
    }

    public PartitionRunway(Source source, ScheduledExecutorService executor, RollupProperties properties, Clock clock,
            RunwayDegradation degradation, List<DailyJob> dailyJobs) {
        this.dailyJobs = List.copyOf(Objects.requireNonNull(dailyJobs, "dailyJobs"));
        this.source = Objects.requireNonNull(source, "source");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.degradation = Objects.requireNonNull(degradation, "degradation");
    }

    /** Schedules the boot run (now, off the starting thread) and the daily one. Returns at once; throws nothing. */
    public synchronized void start() {
        if (daily != null) {
            return;
        }
        daily = executor.scheduleWithFixedDelay(this::keep, 0, properties.runwayEvery().toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (daily != null) {
            daily.cancel(false);
            daily = null;
        }
    }

    /** One keep: extend the runway, or count the failure. Public so an operator-driven call and the tests share it. */
    public void keep() {
        try {
            runwayEnd = source.ensure();
        } catch (RuntimeException e) {
            LOG.warn("the metrics partition runway could not be extended ({}); ingest degrades until it can",
                    e.getClass().getSimpleName());
            try {
                degradation.runwayCouldNotBeExtended();
            } catch (RuntimeException ignored) {
                // counting is best-effort; the keeper must outlive it
            }
        }
        for (DailyJob job : dailyJobs) {
            try {
                job.run();
            } catch (Throwable e) {
                // Throwable, not RuntimeException: this is the runway's ONLY periodic task, and a task that throws is never run
                // again by scheduleWithFixedDelay, so an Error from a job (a linkage error, an assertion) would stop the runway
                // extending, the one outcome OR-77 leaves visible-only. Nothing is rethrown; class only is logged (I6).
                LOG.warn("a daily maintenance job failed ({}); it is retried at the next daily run", e.getClass().getSimpleName());
            }
        }
    }

    /** The jobs that ride this loop after the keep, in order (so a composition test can see what is wired). */
    public List<DailyJob> dailyJobs() {
        return dailyJobs;
    }

    /** The first day with no partition, as of the last successful keep; empty before one. */
    public Optional<LocalDate> runwayEnd() {
        return Optional.ofNullable(runwayEnd);
    }

    /** True when less than one month of runway is left, or when none has been confirmed (the capabilities flag). */
    public boolean runwayShort() {
        LocalDate end = runwayEnd;
        if (end == null) {
            return true;
        }
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        return end.isBefore(today.plusMonths(1));
    }
}
