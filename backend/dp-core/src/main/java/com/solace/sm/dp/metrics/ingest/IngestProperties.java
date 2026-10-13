package com.solace.sm.dp.metrics.ingest;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The ingest path's bounds, {@code sm.dp.metrics.ingest.*} (plan 011 P11-3.1).
 *
 * @param bufferTicks         batches held per placement before the oldest is dropped (D-16); default 20
 * @param maxSeriesPerInstance the series cap per instance (M-Q24 (10)); default 500
 * @param maxBatchSamples     a larger batch is refused whole; default 5000
 * @param defaultIntervalSeconds the interval a polled metric that declares none is read at (OQ-9 (a)); default 30
 * @param retryDelay          what {@code RetryAfter} carries and how long a failing placement or database rests; default 5 s
 * @param callerWait          how long {@code ingest} waits for its own batch before answering {@code RetryAfter}; default 30 s
 * @param drainInterval       how often held batches and pending counters are retried; default 1 s
 * @param activeWindow        a placement that sent a batch within this long counts as "ingesting" when the partition
 *                            keeper reports a failure (P11-3.2); default 5 min
 * @param pushCallerWait      W3-14: how long {@code ingest} waits for a {@code BROKER_PUSH} batch's commit; default 250 ms. The
 *                            push path never uses the commit result, and its single worker must keep draining the bounded
 *                            receive queue while the database is slow, so a push batch is handed over and, past this wait,
 *                            answered {@code RetryAfter}: it stays held in the buffer (D-16) exactly as for any other caller
 */
@ConfigurationProperties(prefix = "sm.dp.metrics.ingest")
public record IngestProperties(
        @DefaultValue("20") int bufferTicks,
        @DefaultValue("500") int maxSeriesPerInstance,
        @DefaultValue("5000") int maxBatchSamples,
        @DefaultValue("30") int defaultIntervalSeconds,
        @DefaultValue("5s") Duration retryDelay,
        @DefaultValue("30s") Duration callerWait,
        @DefaultValue("1s") Duration drainInterval,
        @DefaultValue("5m") Duration activeWindow,
        @DefaultValue("250ms") Duration pushCallerWait) {

    public IngestProperties {
        atLeastOne(bufferTicks, "buffer-ticks");
        atLeastOne(maxSeriesPerInstance, "max-series-per-instance");
        atLeastOne(maxBatchSamples, "max-batch-samples");
        if (defaultIntervalSeconds < 1 || defaultIntervalSeconds > 86400) {
            throw new IllegalArgumentException("sm.dp.metrics.ingest.default-interval-seconds must be 1..86400, was "
                    + defaultIntervalSeconds);
        }
        positive(retryDelay, "retry-delay");
        positive(callerWait, "caller-wait");
        positive(drainInterval, "drain-interval");
        positive(activeWindow, "active-window");
        positive(pushCallerWait, "push-caller-wait");
    }

    /** The defaults, for code that is not Spring-bound. */
    public static IngestProperties defaults() {
        return new IngestProperties(20, 500, 5000, 30, Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofMillis(250));
    }

    private static void atLeastOne(int v, String name) {
        if (v < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.ingest." + name + " must be at least 1, was " + v);
        }
    }

    private static void positive(Duration d, String name) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException("sm.dp.metrics.ingest." + name + " must be a positive duration, was " + d);
        }
    }
}
