package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code ConfigHistory} ({@code Versioned} + {@code entries}), newest first.
 *
 * <p>⛔ {@code I6}: an entry carries identity, time, actor and the KEYED digest - never a value. {@code configHash}
 * is computed over references (OR-32).
 */
public record ConfigHistory(long stateVersion, @WireOptional Availability availability, List<Entry> entries) {

    public ConfigHistory {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /** {@code at} is an RFC 3339 instant; {@code by} is the acting principal's id. */
    public record Entry(long generation, String at, String by, @WireOptional String configHash) {
    }
}
