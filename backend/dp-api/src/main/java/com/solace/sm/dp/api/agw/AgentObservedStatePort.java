package com.solace.sm.dp.api.agw;

import java.util.Optional;

/** The adapter's read side for {@code status}. T2.1b ships a stub answering {@code Optional.empty()}; T3.3 implements. */
public interface AgentObservedStatePort {
    /** The latest observation of the placement, scoped by the placement's own tenant; empty when none exists. */
    Optional<AgentObservation> latest(AgentPlacementRef placement);
}
