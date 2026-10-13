package com.solace.sm.dp.apply.runtime.ssh;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounds of the per-target SSH session pool that serves health polling (and, later, plan 011's collector) -
 * {@code sm.dp.ssh.pool.*}. Every bound is positive; an unbounded pool is not a configuration this class can express.
 *
 * @param maxSessions         connections kept open at once (one per target); past it the least recently used IDLE one
 *                            is closed, and when none is idle the probe is refused {@code ssh.pool.busy}
 * @param idleTimeout         a connection unused for this long is closed by {@link SshSessionPool#evictIdle()}
 * @param maxConcurrentProbes the bounded probe pool: probes in flight across all targets
 * @param maxProbesPerSession probes in flight on ONE connection (sshd's default {@code MaxSessions} is 10)
 * @param acquireTimeout      how long a probe waits for a free slot before {@code ssh.pool.busy}
 * @param keepAliveInterval   sshj keep-alive period; a failed keep-alive kills the transport, which is how a dropped
 *                            connection is noticed between polls
 */
@ConfigurationProperties(prefix = "sm.dp.ssh.pool")
public record SshSessionPoolProperties(
        @DefaultValue("32") int maxSessions,
        @DefaultValue("5m") Duration idleTimeout,
        @DefaultValue("8") int maxConcurrentProbes,
        @DefaultValue("4") int maxProbesPerSession,
        @DefaultValue("5s") Duration acquireTimeout,
        @DefaultValue("15s") Duration keepAliveInterval) {

    public SshSessionPoolProperties {
        if (maxSessions < 1 || maxConcurrentProbes < 1 || maxProbesPerSession < 1) {
            throw new IllegalArgumentException("sm.dp.ssh.pool.max-sessions, max-concurrent-probes and "
                    + "max-probes-per-session must be >= 1");
        }
        positive(idleTimeout, "sm.dp.ssh.pool.idle-timeout");
        positive(acquireTimeout, "sm.dp.ssh.pool.acquire-timeout");
        positive(keepAliveInterval, "sm.dp.ssh.pool.keep-alive-interval");
    }

    public static SshSessionPoolProperties defaults() {
        return new SshSessionPoolProperties(32, Duration.ofMinutes(5), 8, 4, Duration.ofSeconds(5),
                Duration.ofSeconds(15));
    }

    private static void positive(Duration d, String property) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(property + " must be a positive duration, was " + d);
        }
    }
}
