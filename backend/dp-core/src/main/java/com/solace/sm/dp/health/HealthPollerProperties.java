package com.solace.sm.dp.health;

import com.solace.sm.dp.apply.runtime.ssh.HostProbe;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The health poller's bounds - {@code sm.dp.health.*} (plan 010 T7.2, {@code OR-39}).
 *
 * @param enabled     {@code false} withdraws the poller (and its session pool); production sets nothing
 * @param interval    time between the end of one round and the start of the next (plus jitter); default 30 s
 * @param timeout     one probe's bound; default 5 s, at most {@value HostProbe#MAX_TIMEOUT_SECONDS} s. The honest
 *                    detection bound for a dying service is {@code interval + timeout}: there is no push from the MI
 * @param jitter      each wait is stretched by a random fraction of up to this much, so rounds do not align
 * @param maxBackoff  a failing host is probed no less often than this (it doubles from {@code 2 * interval})
 * @param concurrency placements probed at once within a round
 */
@ConfigurationProperties(prefix = "sm.dp.health")
public record HealthPollerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30s") Duration interval,
        @DefaultValue("5s") Duration timeout,
        @DefaultValue("0.1") double jitter,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("8") int concurrency) {

    public HealthPollerProperties {
        positive(interval, "sm.dp.health.interval");
        positive(timeout, "sm.dp.health.timeout");
        positive(maxBackoff, "sm.dp.health.max-backoff");
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0
                || timeout.compareTo(Duration.ofSeconds(HostProbe.MAX_TIMEOUT_SECONDS)) > 0) {
            throw new IllegalArgumentException("sm.dp.health.timeout must be between 1 s and "
                    + HostProbe.MAX_TIMEOUT_SECONDS + " s, was " + timeout);
        }
        if (interval.compareTo(timeout) <= 0) {
            throw new IllegalArgumentException("sm.dp.health.interval must exceed sm.dp.health.timeout");
        }
        if (jitter < 0 || jitter > 1) {
            throw new IllegalArgumentException("sm.dp.health.jitter must be between 0 and 1, was " + jitter);
        }
        if (concurrency < 1) {
            throw new IllegalArgumentException("sm.dp.health.concurrency must be >= 1, was " + concurrency);
        }
    }

    private static void positive(Duration d, String property) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(property + " must be a positive duration, was " + d);
        }
    }
}
