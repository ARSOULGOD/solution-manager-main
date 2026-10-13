package com.solace.sm.launcher;

import com.solace.sm.dp.apply.runtime.ssh.HostMetricProbe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The pure rules of the live collection gate's attribution step (plan 011 P11-4.4), kept apart so they are unit tested
 * without a host. A stored counter is compared with a fresh read of its OWN host port; the Caches' counters are then
 * compared with each other so the evidence says whether attribution was distinguishable by value at all.
 */
final class AttributionCheck {

    /** Every counter the Cache package declares from {@code /api/cache/stats}: metric name to JSON pointer. */
    static final Map<String, String> COUNTERS = counters();

    /** The traffic request's fixed path: a GET of a key that is absent only misses (404) and writes nothing. */
    static final String TRAFFIC_PATH = "/api/cache";

    private AttributionCheck() {
    }

    private static Map<String, String> counters() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("cache.hit_count", "/hitCount");
        m.put("cache.miss_count", "/missCount");
        m.put("cache.eviction_count", "/evictionCount");
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Why a stored value is not this port's own, or empty when it is. A counter only grows, so a stored value AFTER the fresh
     * read is another series' number; one more than {@code slack} BEFORE it is too (the slack is the counter's own
     * movement over the read window).
     */
    static Optional<String> violation(String container, String counter, double last, double fresh, double slack) {
        if (last > fresh) {
            return Optional.of(container + ": last stored " + counter + " (" + last + ") is after a fresh read of the "
                    + "same host port (" + fresh + "); a counter only grows");
        }
        if (fresh - last > slack) {
            return Optional.of(container + ": last stored " + counter + " (" + last + ") is further behind a fresh read "
                    + "of the same host port (" + fresh + ") than the counter's own movement (" + slack + ")");
        }
        return Optional.empty();
    }

    /** Twice the largest step between consecutive stored values: the movement a read window can show. Flat means zero. */
    static double slack(List<Double> values) {
        double largest = 0;
        for (int i = 1; i < values.size(); i++) {
            largest = Math.max(largest, Math.abs(values.get(i) - values.get(i - 1)));
        }
        return 2 * largest;
    }

    /** The counters whose last stored values are not all the same across the Caches (none for fewer than two Caches). */
    static List<String> differing(Map<String, Map<String, Double>> lastByContainer) {
        List<String> names = new ArrayList<>();
        if (lastByContainer.size() < 2) {
            return names;
        }
        for (String counter : COUNTERS.keySet()) {
            long distinct = lastByContainer.values().stream().map(m -> m.get(counter)).filter(java.util.Objects::nonNull)
                    .distinct().count();
            if (distinct > 1) {
                names.add(counter);
            }
        }
        return names;
    }

    static Map<String, String> trafficParams(int n) {
        return Map.of("key", "sm-attribution-" + n);
    }

    /** The fixed {@code curl} of the metrics probe grammar for request {@code n} to a loopback port; refuses anything else. */
    static HostMetricProbe.Curl trafficCommand(int port, int n, Duration timeout) {
        return HostMetricProbe.curl(port, TRAFFIC_PATH, trafficParams(n), timeout);
    }
}
