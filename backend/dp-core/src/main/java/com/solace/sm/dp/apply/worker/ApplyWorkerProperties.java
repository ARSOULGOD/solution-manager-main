package com.solace.sm.dp.apply.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Task 4a's worker bounds — {@code sm.dp.apply.worker.*}.
 *
 * @param poolSize      how many claimed requests this process may execute concurrently. Bounded
 *                       ({@code theWorkerPoolIsBoundedAndRespectsItsConfiguredSize}), never unlimited
 *                       — a fixed-size pool, not a thread per claim. Concurrency within ONE process,
 *                       never confused with the SINGLE-PROCESS-WIDE guard {@code WorkerSingletonGuard}
 *                       enforces: many threads may claim inside one pool, but only one pool may ever
 *                       exist against this database.
 * @param pollInterval   the bounded poll's own trigger — "the honest v1 answer": the worker polls for
 *                       claimable rows on this interval, in addition to an optional in-process nudge
 *                       after a submission commits. The default is a measured value, order of a
 *                       second — {@code LISTEN}/{@code NOTIFY} is deliberately not the only path
 *                       (not durable), so this interval is what turns {@code 202} into a promise.
 * @param shutdownGrace  how long a graceful shutdown waits for in-flight claims to finish before
 *                        interrupting them. Beyond this deadline the row is left {@code RUNNING} —
 *                        recoverable by the next startup's sweep, never corrupted.
 * @param agentSessionGrace how long the startup recovery sweep waits, as ONE window shared by every in-flight request on an
 *                        AGENT target (started when the sweep returns; the deferred requests are then resumed one after another on
 *                        one thread, each as soon as its own agent's session is live or the window is spent), for that
 *                        agent's session to be live again before resuming it (OR-146 (2)). Measured against the real agent
 *                        (plan 015 evidence 015-g-a1, M3): SM answers HTTP 4 to 5 s after a kill, the sweep runs at about
 *                        +4.7 s, and the agent's new session opens at +6.5 to +9.9 s, so the session returns 2 to 5 s AFTER
 *                        the sweep. 30 s is three times the longest measured gap from the kill (about 10 s), leaves room for
 *                        the agent's own reconnect jitter, and is no longer than the reconciler's own tick (about 30 s), so a
 *                        request that never gets its agent back still settles within the time the old path took. Zero
 *                        disables the wait (the request is resumed at once and, with no session, lands
 *                        {@code APPLY_INDETERMINATE}, as before OR-146). The wait never blocks startup: it runs on its own
 *                        thread after the sweep returns. dp-remote requests never wait.
 */
@ConfigurationProperties(prefix = "sm.dp.apply.worker")
public record ApplyWorkerProperties(
        @DefaultValue("2") int poolSize,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("30s") Duration shutdownGrace,
        @DefaultValue("30s") Duration agentSessionGrace) {

    /** The three bounds that predate {@code agentSessionGrace}; it takes its default. */
    public ApplyWorkerProperties(int poolSize, Duration pollInterval, Duration shutdownGrace) {
        this(poolSize, pollInterval, shutdownGrace, Duration.ofSeconds(30));
    }

    @ConstructorBinding
    public ApplyWorkerProperties {
        if (poolSize < 1) {
            throw new IllegalArgumentException("sm.dp.apply.worker.pool-size must be >= 1, was "
                    + poolSize);
        }
        positive(pollInterval, "sm.dp.apply.worker.poll-interval");
        positive(shutdownGrace, "sm.dp.apply.worker.shutdown-grace");
        if (agentSessionGrace == null || agentSessionGrace.isNegative()) {
            throw new IllegalArgumentException("sm.dp.apply.worker.agent-session-grace must be zero (no grace) or a positive duration, was "
                    + agentSessionGrace);
        }
    }

    private static void positive(Duration d, String property) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(property + " must be a positive duration, was " + d);
        }
    }

    /**
     * The most requests on agent targets the worker holds at once (plan 015 T3.1, P15-6): {@code sm.dp.agent.max-concurrent-requests},
     * default {@code poolSize - 1} (the whole pool when the gateway is off). An agent request can block a worker thread for the whole of a per-operation ceiling (an agent
     * that never answers), so the cap is what guarantees at least one thread is always free for {@code dp-remote}.
     *
     * <p>Boot-validated: an explicit value must be at least 1 and below the pool size; and with the agent gateway's listener on
     * the pool must hold at least two threads, or the cap could not leave one free. Refused with {@link IllegalArgumentException}.
     *
     * @param configured     {@code sm.dp.agent.max-concurrent-requests}, or {@code null} for the default
     * @param gatewayEnabled whether this process listens for agents ({@code sm.agent.gateway.enabled})
     */
    public int agentCap(Integer configured, boolean gatewayEnabled) {
        if (gatewayEnabled && poolSize < 2) {
            throw new IllegalArgumentException("sm.dp.apply.worker.pool-size must be >= 2 when the agent gateway is enabled, was "
                    + poolSize + ": the agent concurrency cap must leave one worker thread for dp-remote");
        }
        if (configured == null) {
            // With no gateway listening an agent step can only fail fast (agent.session.absent), so nothing needs reserving:
            // a cap of zero would leave an agent-target request REQUESTED forever, a silent no-op (review finding 10).
            return gatewayEnabled ? poolSize - 1 : poolSize;
        }
        if (configured < 1 || configured >= poolSize) {
            throw new IllegalArgumentException("sm.dp.agent.max-concurrent-requests must be >= 1 and < sm.dp.apply.worker.pool-size ("
                    + poolSize + "), was " + configured);
        }
        return configured;
    }

    /** Bound to {@code sm.dp.apply.worker.*} when absent — every field spelt out, none implicit. */
    public static ApplyWorkerProperties defaults() {
        return new ApplyWorkerProperties(2, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30));
    }
}
