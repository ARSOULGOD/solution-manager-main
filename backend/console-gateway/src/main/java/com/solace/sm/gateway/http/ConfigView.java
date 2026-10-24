package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code ConfigView} ({@code viewConfig}, plan 017 task 6): the allow-listed keys of one agent
 * placement's configuration file, the count of keys hidden on the host, and whether the agent cut the list at its key cap. No
 * file path, host or container is carried; a key that is not on the allow-list is never named, only counted.
 */
public record ConfigView(List<Entry> entries, int hiddenCount, boolean truncated) {

    public ConfigView {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /** One allow-listed scalar leaf: its dotted key path ({@code a.b[0].c}) and its value as text. */
    public record Entry(String path, String value) {

        public Entry {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(value, "value");
        }

        /** A value is configuration content: never in a log line through a stray {@code toString}. */
        @Override
        public String toString() {
            return "Entry[redacted]";
        }
    }
}
