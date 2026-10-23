package com.solace.sm.dp.api.agw;

import java.util.Map;
import java.util.UUID;

/**
 * The stored retention caps of an agent's placements, implemented by the data plane (plan 016 task 9). The gateway reads it
 * when a session opens and whenever its lease is renewed or re-issued, and pushes only the caps that differ from what that
 * session was last sent. A placement absent from the map has no stored cap, so the agent keeps its default.
 */
@FunctionalInterface
public interface AgentLogRetentionSource {
    /** Placement id to stored cap in bytes (0 means collection off). Must not throw for an unknown agent: answer empty. */
    Map<UUID, Long> capsFor(AgentSessionRef ref);

    /** No stored caps (the interim wiring before the data plane supplies them). */
    AgentLogRetentionSource NONE = ref -> Map.of();
}
