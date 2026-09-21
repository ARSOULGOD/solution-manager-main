package com.solace.sm.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.solace.sm.dp.apply.runtime.ssh.SshDockerMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DemoMetricHistoryTest {

    @Test
    void retainsSamplesAndSharesOneSshReadInsideTheRefreshBurst() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-15T09:00:00Z"));
        DemoMetricHistory history = new DemoMetricHistory(Duration.ofSeconds(5), 100, clock);
        UUID instanceId = UUID.randomUUID();
        AtomicInteger reads = new AtomicInteger();

        history.observe(instanceId, () -> sample(reads.incrementAndGet()));
        history.observe(instanceId, () -> sample(reads.incrementAndGet()));
        clock.advance(Duration.ofSeconds(10));
        history.observe(instanceId, () -> sample(reads.incrementAndGet()));

        assertEquals(2, reads.get());
        assertEquals(2, history.samples(instanceId, Instant.parse("2026-09-15T08:59:00Z")).size());
        assertEquals(2, history.samples(instanceId, Instant.parse("2026-09-15T09:00:05Z"))
                .get(0).metrics().heapUsed());
    }

    private static SshDockerMetrics.MetricsSnapshot sample(int value) {
        return new SshDockerMetrics.MetricsSnapshot(0, 0, 0, 0, 0, value);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
