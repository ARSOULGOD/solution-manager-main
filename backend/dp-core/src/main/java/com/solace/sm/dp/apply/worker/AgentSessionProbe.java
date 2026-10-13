package com.solace.sm.dp.apply.worker;

import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.agent.AgentPlacementCatalog;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * What the startup recovery sweep needs to know about a request's target (OR-146 (2)): is the request's placement on an agent
 * target, and if so is that agent's session live now? Both answers come from dp-core's own tables, tenant-scoped, with bind
 * parameters ({@link AgentPlacementCatalog#resolve} and {@link AgentSessionPort#liveFor}); nothing here comes from the request's
 * caller or from an agent.
 */
@FunctionalInterface
public interface AgentSessionProbe {

    /**
     * Empty when the request is not on an agent target, or has no single placement (an instance-wide stop): the sweep handles it
     * inline, as it always did. Otherwise the check to poll: {@code true} once the placement's agent holds a live session and an
     * unexpired lease for it - the same condition {@code AgentRuntimeAdapter} fails fast on.
     */
    Optional<BooleanSupplier> liveCheck(ApplyRequestRow row);

    static AgentSessionProbe over(AgentPlacementCatalog catalog, AgentSessionPort sessions) {
        return row -> {
            if (row.scope().placement().isInstanceWide()) {
                return Optional.empty();
            }
            PlacementRef placement = new PlacementRef(row.scope().tenant(), row.scope().instance(), row.scope().placement());
            return catalog.resolve(placement).map(p -> () -> sessions.liveFor(p.agentId(), p.ref()));
        };
    }
}
