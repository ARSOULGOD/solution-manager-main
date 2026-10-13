package com.solace.sm.dp.metrics.push;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The broker push path's bounds, {@code sm.dp.metrics.push.*} (plan 011 P11-5.1).
 *
 * @param enabled       {@code false} withdraws the push step from the metrics loop (nothing is subscribed)
 * @param queueCapacity events waiting between the broker's receive thread and the worker; a full queue drops the NEWEST and counts
 *                      {@code buffer_dropped} at the connection, so a slow database never backs the broker up; default 10,000
 * @param maxBodyBytes  an event larger than this is refused unread and counted {@code parse_failed}; default 256 KiB
 * @param batchSize     most events one worker pass takes, grouped into one ingest batch per placement; default 200
 */
@ConfigurationProperties(prefix = "sm.dp.metrics.push")
public record PushProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10000") int queueCapacity,
        @DefaultValue("262144") int maxBodyBytes,
        @DefaultValue("200") int batchSize) {

    public PushProperties {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.push.queue-capacity must be >= 1, was " + queueCapacity);
        }
        if (maxBodyBytes < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.push.max-body-bytes must be >= 1, was " + maxBodyBytes);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.push.batch-size must be >= 1, was " + batchSize);
        }
    }

    public static PushProperties defaults() {
        return new PushProperties(true, 10_000, 262_144, 200);
    }
}
