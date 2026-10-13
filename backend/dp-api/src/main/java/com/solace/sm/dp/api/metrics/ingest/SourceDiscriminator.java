package com.solace.sm.dp.api.metrics.ingest;

import java.util.Locale;

/**
 * Which path a sample arrived by (M-Q7's eighth identity part). The wire spelling is the lower-case, hyphenated name
 * ({@code dp-poll}, {@code broker-push}, {@code agent}), which is also what {@code series.source_discriminator}
 * stores.
 */
public enum SourceDiscriminator {
    DP_POLL, BROKER_PUSH, AGENT;

    public String wire() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
