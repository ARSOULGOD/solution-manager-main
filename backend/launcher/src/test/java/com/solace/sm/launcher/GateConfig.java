package com.solace.sm.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Plan 011 P11-8.1 Step 3: how the as-an-Operator timings are taken. Every value is a {@code sm.bench.gate.*} property. */
record GateConfig(int runs, long spacingMillis, int timeoutSeconds, List<Integer> instanceCounts, String entityType, String metric,
        String operatorUsername) {

    GateConfig {
        if (runs < 1) {
            throw new IllegalArgumentException("sm.bench.gate.runs must be at least 1, was " + runs);
        }
        if (spacingMillis < 0) {
            throw new IllegalArgumentException("sm.bench.gate.spacing-ms must not be negative");
        }
    }

    /**
     * The defaults: 20 timed runs after one cold one, 150 ms apart (the limit is 10 per second per session, so about 6.7 per
     * second stays under it), one 5-minute ceiling per request, over 1, 10 and 200 instances, for the first gauge of the load.
     */
    static GateConfig from(Function<String, String> props) {
        List<Integer> counts = new ArrayList<>();
        for (String part : orDefault(props, "sm.bench.gate.instance-counts", "1,10,200").split(",")) {
            counts.add(Integer.parseInt(part.trim()));
        }
        return new GateConfig(Integer.parseInt(orDefault(props, "sm.bench.gate.runs", "20")),
                Long.parseLong(orDefault(props, "sm.bench.gate.spacing-ms", "150")),
                Integer.parseInt(orDefault(props, "sm.bench.gate.timeout-seconds", "300")), List.copyOf(counts),
                orDefault(props, "sm.bench.gate.entity-type", "cache-engine"),
                orDefault(props, "sm.bench.gate.metric", "bench.gauge_1"),
                orDefault(props, "sm.bench.gate.operator", "bench-operator"));
    }

    private static String orDefault(Function<String, String> props, String name, String dflt) {
        String v = props.apply(name);
        return v == null || v.isBlank() ? dflt : v.trim();
    }
}
