package com.solace.sm.dp.apply.runtime;

import java.util.Optional;
import java.util.UUID;

/**
 * Which transport reaches a placement's target (plan 015 T3.1; {@code BE-D66}): read from the tables on every call, never
 * guessed, never configured globally. There is no global switch: {@code dp-remote} and agent placements run in one process
 * and the adapter is chosen per placement.
 *
 * <p>The answer is the {@code target_connection.transport} wire value ({@code ssh-docker}, {@code agent}). It stays a string on
 * purpose: {@link RoutingRuntimeAdapter} refuses a value it does not know instead of defaulting it to the nearest transport.
 */
public interface PlacementTransports {

    /** {@code ssh-docker}: the dp-remote transport. */
    String SSH_DOCKER = "ssh-docker";

    /** {@code agent}: the host-local agent's. */
    String AGENT = "agent";

    /**
     * The transport of the target the placement is on, bound by {@code (tenant, instance, placement)} (I5); empty when the
     * placement does not exist for that tenant and instance. A retired placement still answers (its target's transport):
     * whether a retired placement may be acted on is each adapter's own rule, not the router's.
     */
    Optional<String> transportOf(PlacementRef placement);

    /** Whether the target is an agent target, bound by tenant (I5): what the pollers use to skip it. */
    boolean isAgentTarget(UUID tenantId, UUID targetId);

    /** Nothing is an agent target: the answer for a context without the agent path (and every pre-agent test). */
    PlacementTransports NONE = new PlacementTransports() {
        @Override
        public Optional<String> transportOf(PlacementRef placement) {
            return Optional.of(SSH_DOCKER);
        }

        @Override
        public boolean isAgentTarget(UUID tenantId, UUID targetId) {
            return false;
        }
    };
}
