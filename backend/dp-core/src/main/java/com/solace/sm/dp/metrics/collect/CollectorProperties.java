package com.solace.sm.dp.metrics.collect;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The collector's bounds - {@code sm.dp.metrics.collect.*} (plan 011 P11-4.3, M-Q3 {@code OR-72}).
 *
 * <p><b>The per-target reservation is configuration, not a measurement.</b> MF6 (exec latency over a pooled session) and
 * MF7 (the host's effective sshd {@code MaxSessions}) are measured by the integrator on the E2E host; they only SET
 * these defaults, they are not baked into code. The effective cap on one target is
 * {@code min(perTargetCap, sshProbesPerSession - healthReserve)}, never below 1 ({@link SourceScheduler#effectiveCap}):
 * the SSH session pool serves the health poller too, and a collector that could hold every probe slot of a target's
 * connection would starve health.
 *
 * @param enabled        {@code false} withdraws the background loop (nothing is collected, rolled up or reconciled)
 * @param tick           how often the loop reads the tenant list, refreshes every tenant's plan and rolls up; default 10 s
 * @param dispatchEvery  how often due sources are started; default 1 s (finer than {@code tick} so a 10 s interval keeps
 *                       its cadence and a jitter of 10 % means something)
 * @param workerThreads  platform threads that fetch, across all targets; default 16 (discovery default D-4)
 * @param perTargetCap   most fetch runs in flight against ONE target; default 4 (plan: {@code min(4, MaxSessions - 2)})
 * @param healthReserve  probe slots of a target's SSH connection the collector never takes, so health keeps its own; default 1
 * @param jitter         each next-due time is stretched or shrunk by up to this fraction of the interval; default 0.1
 * @param maxBackoffMultiplier a failing host is read no less often than every this many intervals (1x, 2x, 4x, 8x); default 8
 */
@ConfigurationProperties(prefix = "sm.dp.metrics.collect")
public record CollectorProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10s") Duration tick,
        @DefaultValue("1s") Duration dispatchEvery,
        @DefaultValue("16") int workerThreads,
        @DefaultValue("4") int perTargetCap,
        @DefaultValue("1") int healthReserve,
        @DefaultValue("0.1") double jitter,
        @DefaultValue("8") int maxBackoffMultiplier) {

    public CollectorProperties {
        positive(tick, "tick");
        positive(dispatchEvery, "dispatch-every");
        if (workerThreads < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.collect.worker-threads must be >= 1, was " + workerThreads);
        }
        if (perTargetCap < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.collect.per-target-cap must be >= 1, was " + perTargetCap);
        }
        if (healthReserve < 0) {
            throw new IllegalArgumentException("sm.dp.metrics.collect.health-reserve must be >= 0, was " + healthReserve);
        }
        if (jitter < 0 || jitter > 0.5) {
            throw new IllegalArgumentException("sm.dp.metrics.collect.jitter must be between 0 and 0.5, was " + jitter);
        }
        if (maxBackoffMultiplier < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.collect.max-backoff-multiplier must be >= 1, was "
                    + maxBackoffMultiplier);
        }
    }

    /** The defaults, for code that is not Spring-bound. */
    public static CollectorProperties defaults() {
        return new CollectorProperties(true, Duration.ofSeconds(10), Duration.ofSeconds(1), 16, 4, 1, 0.1, 8);
    }

    private static void positive(Duration d, String name) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException("sm.dp.metrics.collect." + name + " must be a positive duration, was " + d);
        }
    }
}
