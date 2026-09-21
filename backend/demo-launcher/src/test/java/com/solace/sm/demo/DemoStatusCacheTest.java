package com.solace.sm.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DemoStatusCacheTest {

    @Test
    void reusesOneRemoteObservationDuringANavigationBurst() throws Exception {
        UUID instanceId = UUID.randomUUID();
        AtomicInteger remoteReads = new AtomicInteger();
        DemoStatusCache<String> cache = new DemoStatusCache<>(Duration.ofSeconds(5),
                Clock.fixed(Instant.parse("2026-09-15T07:20:00Z"), ZoneOffset.UTC));

        String first = cache.get(instanceId, () -> "observation-" + remoteReads.incrementAndGet());
        String second = cache.get(instanceId, () -> "observation-" + remoteReads.incrementAndGet());

        assertEquals("observation-1", first);
        assertEquals(first, second);
        assertEquals(1, remoteReads.get());
    }
}
