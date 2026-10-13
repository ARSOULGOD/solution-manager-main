package com.solace.sm.gateway.http.metrics;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code sm.gateway.metrics.*} (plan 011 P11-7.2): the monitoring routes' own bounds. Every default is a ruling
 * (M-Q22 {@code OR-79}, M-Q24), every one is configurable, and none may be raised by a request.
 *
 * @param queryRatePerSecond   queries (range, instant and export requests) one session may start per second (M-Q24 (5),
 *                             M-Q22 (5)): the token bucket's capacity AND its refill rate. Per process, like the
 *                             login gate
 * @param exportAsyncTimeout   how long an export may stream before it is cut (M-Q22 (2): 30 minutes). Set EXPLICITLY on
 *                             the MVC async support, never left to the container's default (MF18). It equals the data
 *                             plane's export {@code statement-timeout} by default; they are two bounds on one stream
 * @param exportMaxThreads     how many export streams may hold a thread at once (W4-09): the MVC async executor's size,
 *                             with no queue. The data plane's own caps (2 per install by default) are taken on the
 *                             streaming thread, so without this every ADMITTED request would first spawn a thread and be
 *                             refused afterwards; a request past it is the same {@code 429 export-concurrency}. Keep it
 *                             above the data plane's install cap (default 8: that cap doubled, plus a margin)
 * @param rawMinBucketSeconds  what the capabilities advertise as the raw tier's smallest bucket: the collector's default
 *                             scrape interval. The gateway cannot read the data plane's declared intervals, and the UI
 *                             only uses this to offer presets
 */
@ConfigurationProperties(prefix = "sm.gateway.metrics")
public record MetricsGatewayProperties(
        @DefaultValue("10") int queryRatePerSecond,
        @DefaultValue("30m") Duration exportAsyncTimeout,
        @DefaultValue("8") int exportMaxThreads,
        @DefaultValue("30") int rawMinBucketSeconds) {

    public MetricsGatewayProperties {
        if (queryRatePerSecond < 1) {
            throw new IllegalArgumentException("sm.gateway.metrics.query-rate-per-second must be at least 1, was "
                    + queryRatePerSecond);
        }
        if (exportAsyncTimeout == null || exportAsyncTimeout.isNegative() || exportAsyncTimeout.isZero()) {
            throw new IllegalArgumentException("sm.gateway.metrics.export-async-timeout must be a positive duration");
        }
        if (exportMaxThreads < 1) {
            throw new IllegalArgumentException("sm.gateway.metrics.export-max-threads must be at least 1, was "
                    + exportMaxThreads);
        }
        if (rawMinBucketSeconds < 1) {
            throw new IllegalArgumentException("sm.gateway.metrics.raw-min-bucket-seconds must be at least 1");
        }
    }

    public static MetricsGatewayProperties defaults() {
        return new MetricsGatewayProperties(10, Duration.ofMinutes(30), 8, 30);
    }
}
