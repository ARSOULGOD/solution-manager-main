package com.solace.sm.dp.apply.runtime.agent;

import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a live placement to the agent that owns it (plan 015 T3.1). Read from the tables on every call and bound by
 * {@code (tenant, instance, placement)} (I5): the agent is the one the placement's TARGET names, never a value an agent or a
 * caller supplied. A placement that is retired, not on an agent target, or not this tenant's resolves empty.
 */
@FunctionalInterface
public interface AgentPlacementCatalog {

    Optional<AgentPlacement> resolve(PlacementRef placement);

    /**
     * The container start the data plane last recorded for the placement ({@code placement_start_observation}, the one baseline
     * source, {@code AGW-D28} item 2: fed by the acknowledgement of an APPLY, REMATERIALIZE or RESTART and by reports, forward
     * only). Empty when none was ever recorded. The default answers empty.
     */
    default Optional<Instant> observedStart(AgentPlacement placement) {
        return Optional.empty();
    }

    /**
     * The KEYED config hash of the newest APPLY or REMATERIALIZE that the placement's agent acknowledged as applied, with a wire
     * generation at or below {@code atOrBelowGeneration}; empty when there is none. This is what answers {@code status} once the
     * in-memory plain digest is gone (an SM restart, plan 015 W3 integration finding 1): the agent's own claim of which generation
     * it persisted, read as the keyed hash the command row already holds. No plain digest is stored to make this possible
     * ({@code I6}, {@code OR-32}). The default answers empty, which is "nothing to fall back on".
     */
    default Optional<String> appliedConfigHash(AgentPlacement placement, long atOrBelowGeneration) {
        return Optional.empty();
    }

    /**
     * The start the agent's acknowledgement recorded as {@code write_baseline} for the MATERIALIZE intent, taken from the newest
     * attempt of the intent that carries one (the ack writes it on the attempt that created the command, which a retry or a resume
     * may have replaced; {@code OR-139}, clarified). Empty when the acknowledgement carried no start (fail closed). The default
     * answers empty.
     */
    default Optional<PreBaseline> ackedWriteBaseline(AgentPlacement placement, UUID intentId) {
        return Optional.empty();
    }

    /**
     * @param agentId    the agent whose target the placement is on
     * @param ref        the same placement as the {@code dp-api} port addresses it
     * @param location   where the config file lives, as the package declared it for the placement
     * @param healthKind the placement's declared probe kind ({@code actuator-health} or {@code http-status}), or {@code null}
     *                   when the package declares none (then the health signal is {@code NOT_DECLARED}, whatever is reported)
     */
    record AgentPlacement(UUID agentId, AgentPlacementRef ref, String containerName, ConfigLocation location,
            String healthKind) {

        public AgentPlacement {
            Objects.requireNonNull(agentId, "agent id");
            Objects.requireNonNull(ref, "placement ref");
            Objects.requireNonNull(containerName, "container name");
            Objects.requireNonNull(location, "location");
        }

        public boolean healthSignalDeclared() {
            return healthKind != null;
        }
    }
}
