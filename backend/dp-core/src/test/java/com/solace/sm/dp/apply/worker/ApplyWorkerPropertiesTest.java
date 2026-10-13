package com.solace.sm.dp.apply.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ApplyWorkerPropertiesTest {

    @Test
    void defaultsAreValid() {
        ApplyWorkerProperties props = ApplyWorkerProperties.defaults();

        assertEquals(2, props.poolSize());
        assertEquals(Duration.ofSeconds(1), props.pollInterval());
        assertEquals(Duration.ofSeconds(30), props.shutdownGrace());
    }

    @Test
    void poolSizeMustBeAtLeastOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new ApplyWorkerProperties(0, Duration.ofSeconds(1), Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,
                () -> new ApplyWorkerProperties(-1, Duration.ofSeconds(1), Duration.ofSeconds(30)));
    }

    @Test
    void pollIntervalMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new ApplyWorkerProperties(2, Duration.ZERO, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,
                () -> new ApplyWorkerProperties(2, Duration.ofSeconds(-1), Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,
                () -> new ApplyWorkerProperties(2, null, Duration.ofSeconds(30)));
    }

    @Test
    void shutdownGraceMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new ApplyWorkerProperties(2, Duration.ofSeconds(1), Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ApplyWorkerProperties(2, Duration.ofSeconds(1), null));
    }

    private static ApplyWorkerProperties pool(int size) {
        return new ApplyWorkerProperties(size, Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    // ---- plan 015 T3.1 (P15-6): the agent concurrency cap leaves a thread for dp-remote ---------------------------------------

    @Test
    void theAgentCapDefaultsToOneBelowThePool() {
        assertEquals(1, pool(2).agentCap(null, true));
        assertEquals(3, pool(4).agentCap(null, true));
    }

    @Test
    void anExplicitCapIsAtLeastOneAndBelowThePool() {
        assertEquals(2, pool(4).agentCap(2, true));
        assertThrows(IllegalArgumentException.class, () -> pool(4).agentCap(0, true), "zero would starve every agent step");
        assertThrows(IllegalArgumentException.class, () -> pool(4).agentCap(4, true), "a cap equal to the pool starves dp-remote");
        assertThrows(IllegalArgumentException.class, () -> pool(4).agentCap(9, false));
    }

    @Test
    void withTheGatewayListeningThePoolHoldsAtLeastTwoThreads() {
        assertThrows(IllegalArgumentException.class, () -> pool(1).agentCap(null, true),
                "one thread cannot be both the agent cap and the thread left for dp-remote");
        assertEquals(1, pool(1).agentCap(null, false), "no gateway: an agent step can only fail fast, so nothing is reserved and nothing hangs REQUESTED");
        assertEquals(1, pool(2).agentCap(null, true));
    }

    // ---- OR-146 (2): the agent-session grace of the startup recovery sweep -----------------------------------------------------

    private static ApplyWorkerProperties bound(java.util.Map<String, String> source) {
        return new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(source))
                .bindOrCreate("sm.dp.apply.worker", ApplyWorkerProperties.class);
    }

    @Test
    void theAgentSessionGraceDefaultsToThirtySecondsBothWays() {
        assertEquals(Duration.ofSeconds(30), ApplyWorkerProperties.defaults().agentSessionGrace());
        assertEquals(Duration.ofSeconds(30), pool(2).agentSessionGrace(), "the three-argument form takes the default");
        assertEquals(Duration.ofSeconds(30), bound(java.util.Map.of()).agentSessionGrace(), "bound with nothing set");
    }

    @Test
    void theAgentSessionGraceBindsFromItsPropertyAndMayBeZero() {
        assertEquals(Duration.ofSeconds(45),
                bound(java.util.Map.of("sm.dp.apply.worker.agent-session-grace", "45s")).agentSessionGrace());
        assertEquals(Duration.ZERO, bound(java.util.Map.of("sm.dp.apply.worker.agent-session-grace", "0s")).agentSessionGrace(),
                "zero means no wait: the sweep resumes at once, as before OR-146");
        assertEquals(Duration.ofSeconds(1), bound(java.util.Map.of("sm.dp.apply.worker.pool-size", "3")).pollInterval(),
                "the other properties still bind (two constructors, one binding)");
    }

    @Test
    void aNegativeAgentSessionGraceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ApplyWorkerProperties(2, Duration.ofSeconds(1),
                Duration.ofSeconds(30), Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new ApplyWorkerProperties(2, Duration.ofSeconds(1),
                Duration.ofSeconds(30), null));
    }
}
