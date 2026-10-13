package com.solace.sm.dp.health;

import java.util.UUID;

/**
 * The seam through which the scheduled staleness job finishes what the agent path cannot: an agent that stopped reporting leaves its
 * last verdict in {@code placement_health} (nothing polls an agent placement), so the job asks this to mark such placements
 * (plan 015 T3.3, T4.6; {@code AGW-D28} item 1; {@code OR-140}). Per organization and instance; implemented in {@code dp.agent}.
 * Nothing on a read path calls it.
 */
@FunctionalInterface
public interface AgentReportStaleness {

    /** Records {@code unreachable} for the instance's agent placements whose agent has been silent past the window; idempotent. */
    void markStale(UUID organizationId, UUID instanceId);
}
