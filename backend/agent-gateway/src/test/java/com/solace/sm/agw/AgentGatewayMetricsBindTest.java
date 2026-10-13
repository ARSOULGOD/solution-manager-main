package com.solace.sm.agw;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Plan 015 W4 lane D: an outside count is a function counter of the registry, read at scrape time. */
class AgentGatewayMetricsBindTest {

    /**
     * The weak-source defect itself (found in the W4 lane D fix round): Micrometer holds the SOURCE weakly, so a meter bound to an
     * object nothing else references is collected and stops reading. This pins that the hazard is real and observable here, which is
     * why {@code bindCount} takes the owner as {@code source}; the composed context's survival is {@code AgentGatewayCompositionIT}.
     */
    @Test
    void aSourceNothingElseReferencesIsCollectedAndTheMeterStopsReading() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentGatewayMetrics metrics = new AgentGatewayMetrics(registry);
        AtomicLong throwaway = new AtomicLong(7);
        java.lang.ref.WeakReference<AtomicLong> probe = new java.lang.ref.WeakReference<>(throwaway);
        metrics.bindCount("agent_telemetry_weak_total", throwaway, AtomicLong::get);
        throwaway = null;                                   // never read while alive: the meter would remember its last reading
        for (int i = 0; i < 50 && probe.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(probe.get() == null, "this JVM did not collect the weakly held source");
        assertEquals(true, registry.get("agent_telemetry_weak_total").functionCounter().count() != 7.0,
                "the meter no longer reads the dropped source");
    }

    @Test
    void aBoundCountIsAFunctionCounterThatReadsTheLiveValue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentGatewayMetrics metrics = new AgentGatewayMetrics(registry);
        AtomicLong outside = new AtomicLong(4);

        metrics.bindCount("agent_telemetry_example_total", outside, AtomicLong::get);

        assertEquals(4.0, registry.get("agent_telemetry_example_total").functionCounter().count());
        outside.addAndGet(6);
        assertEquals(10.0, registry.get("agent_telemetry_example_total").functionCounter().count());
        assertEquals(10L, outside.get());
    }
}
