package com.solace.sm.demo;

import com.solace.sm.dp.apply.runtime.ssh.SshDockerMetrics;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Process-local live samples; restart intentionally starts a new retention window. */
@Service
final class DemoMetricHistory {
    private final Duration snapshotTtl;
    private final int maxSamples;
    private final Clock clock;
    private final Map<UUID, ArrayDeque<Sample>> samples = new HashMap<>();

    DemoMetricHistory() {
        this(Duration.ofSeconds(5), 1_000, Clock.systemUTC());
    }

    DemoMetricHistory(Duration snapshotTtl, int maxSamples, Clock clock) {
        this.snapshotTtl = snapshotTtl;
        this.maxSamples = maxSamples;
        this.clock = clock;
    }

    synchronized Sample observe(UUID instanceId, Loader loader) throws IOException {
        Instant now = clock.instant();
        ArrayDeque<Sample> history = samples.computeIfAbsent(instanceId, ignored -> new ArrayDeque<>());
        Sample latest = history.peekLast();
        if (latest != null && now.isBefore(latest.observedAt().plus(snapshotTtl))) {
            return latest;
        }
        Sample observed = new Sample(now, loader.load());
        history.addLast(observed);
        while (history.size() > maxSamples) {
            history.removeFirst();
        }
        return observed;
    }

    synchronized List<Sample> samples(UUID instanceId, Instant from) {
        ArrayDeque<Sample> history = samples.get(instanceId);
        if (history == null) {
            return List.of();
        }
        List<Sample> result = new ArrayList<>();
        for (Sample sample : history) {
            if (!sample.observedAt().isBefore(from)) {
                result.add(sample);
            }
        }
        return List.copyOf(result);
    }

    record Sample(Instant observedAt, SshDockerMetrics.MetricsSnapshot metrics) {}

    @FunctionalInterface
    interface Loader {
        SshDockerMetrics.MetricsSnapshot load() throws IOException;
    }
}
