package com.solace.sm.dp.apply.runtime.agent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code sm.dp.agent.*}: the agent path's data-plane bounds (plan 015 T3.1). There is no global agent switch (global
 * constraint 5): these size the path, they do not turn it on.
 *
 * @param readbackWait          how long a finished APPLY or REMATERIALIZE waits for the actual-state report that proves it
 *                              before the engine's own read-back runs ({@code AGW-D21}); default 45 s
 * @param handoff               the in-memory payload hand-off's bound
 * @param maxConcurrentRequests the most apply requests on agent targets the worker holds at once; absent means
 *                              {@code pool-size - 1}. Validated at boot by {@code ApplyWorkerProperties#agentCap}: at least 1
 *                              and below the pool size, so one worker thread is always free for {@code dp-remote}
 */
@ConfigurationProperties(prefix = "sm.dp.agent")
public record AgentRuntimeProperties(
        @DefaultValue("45s") Duration readbackWait,
        @DefaultValue Handoff handoff,
        Integer maxConcurrentRequests) {

    public AgentRuntimeProperties {
        if (readbackWait == null || readbackWait.isNegative()) {
            throw new IllegalArgumentException("sm.dp.agent.readback-wait must not be negative, was " + readbackWait);
        }
    }

    /** @param maxEntries how many payloads the hand-off holds before the oldest is dropped (a miss re-renders); default 64 */
    public record Handoff(@DefaultValue("64") int maxEntries) {
        public Handoff {
            if (maxEntries < 1) {
                throw new IllegalArgumentException("sm.dp.agent.handoff.max-entries must be >= 1, was " + maxEntries);
            }
        }
    }

    public static AgentRuntimeProperties defaults() {
        return new AgentRuntimeProperties(Duration.ofSeconds(45), new Handoff(64), null);
    }
}
