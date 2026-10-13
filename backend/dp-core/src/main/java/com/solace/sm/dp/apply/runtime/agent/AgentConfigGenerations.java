package com.solace.sm.dp.apply.runtime.agent;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import java.util.List;
import java.util.OptionalLong;

/**
 * The two questions the agent path asks the journal about CONFIG generations (not wire generations), so it can re-render after
 * an SM restart without storing a byte (plan 015 T3.1, {@code OR-126} A5).
 */
public interface AgentConfigGenerations {

    /** The config generation the apply request bound, or empty when the request is not this tenant's or has none. */
    OptionalLong generationOfApply(TenantId tenant, ApplyId apply);

    /**
     * The generations a reported file digest can belong to when no byte is held in memory: the placement's intended (newest)
     * generation and its last-applied one. Newest first, no duplicates; empty when none is known.
     */
    List<Long> candidates(PlacementRef placement);
}
