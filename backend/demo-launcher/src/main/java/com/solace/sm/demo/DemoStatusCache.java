package com.solace.sm.demo;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Shares one remote status observation across the requests made by one page navigation. */
final class DemoStatusCache<T> {
    private final Duration ttl;
    private final Clock clock;
    private final Map<UUID, Entry<T>> entries = new HashMap<>();

    DemoStatusCache(Duration ttl, Clock clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized T get(UUID instanceId, Loader<T> loader) throws IOException {
        Instant now = clock.instant();
        Entry<T> current = entries.get(instanceId);
        if (current != null && current.observedAt().plus(ttl).isAfter(now)) {
            return current.value();
        }
        T loaded = loader.load();
        entries.put(instanceId, new Entry<>(clock.instant(), loaded));
        return loaded;
    }

    synchronized void put(UUID instanceId, T value) {
        entries.put(instanceId, new Entry<>(clock.instant(), value));
    }

    synchronized void invalidate(UUID instanceId) {
        entries.remove(instanceId);
    }

    @FunctionalInterface
    interface Loader<T> {
        T load() throws IOException;
    }

    private record Entry<T>(Instant observedAt, T value) {}
}
