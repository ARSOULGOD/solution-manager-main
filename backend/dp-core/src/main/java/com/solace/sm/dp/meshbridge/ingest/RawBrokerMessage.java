package com.solace.sm.dp.meshbridge.ingest;

import java.time.Instant;
import java.util.Objects;

/**
 * One event as the broker delivered it, handed OUT of {@code meshbridge.ingest} as bytes and a topic (plan 011 P11-5.1): no JCSMP
 * type, no mapper. {@code meshbridgeHoldsNoOwnMapper} keeps this package free of JSON; the decoder that reads the bytes is
 * {@code dp.metrics.push}'s. {@code receivedAt} is the Data Plane's UTC receipt instant, the sample's {@code ts} (OQ-4 (a)).
 *
 * <p>The body is the MI's own payload and may carry a secret (a File Mesh {@code start} event's plaintext broker password,
 * facts F15): {@link #toString()} names neither it nor its size, and nothing here logs it.
 */
public record RawBrokerMessage(String topic, byte[] body, Instant receivedAt) {

    public RawBrokerMessage {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    @Override
    public String toString() {
        return "RawBrokerMessage[redacted]";
    }
}
