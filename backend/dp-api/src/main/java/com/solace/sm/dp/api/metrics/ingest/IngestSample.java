package com.solace.sm.dp.api.metrics.ingest;

import java.time.Instant;
import java.util.Objects;

/**
 * One reading. <b>EXACTLY these components</b> (the I1 check pins it by reflection): no type, no unit, no instance and
 * no label from the producer. A record that grew a {@code metricType} component would let the producer retype a
 * series, which is precisely what the per-version definition exists to prevent.
 *
 * @param metricName   a name the instance's package version declares; anything else is dropped and counted
 * @param ts           when the value was true (for a poll, the UTC midpoint of sent and received, M-Q24 (8))
 * @param value        a finite number; a non-finite one is dropped as {@code PARSE_FAILED}
 * @param receivedAt   when SM had it
 * @param restartToken the source's restart evidence read in the same cycle, or null for none
 */
public record IngestSample(String metricName, Instant ts, double value, Instant receivedAt, String restartToken) {

    public IngestSample {
        Objects.requireNonNull(metricName, "metricName");
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    /** A sample with no restart token. */
    public IngestSample(String metricName, Instant ts, double value, Instant receivedAt) {
        this(metricName, ts, value, receivedAt, null);
    }

    /** The token is evidence about a restart, not a secret, but a sample's text is still never echoed (I6). */
    @Override
    public String toString() {
        return "IngestSample[" + metricName + " @ " + ts + "]";
    }
}
