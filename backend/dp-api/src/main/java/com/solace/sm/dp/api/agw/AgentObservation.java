package com.solace.sm.dp.api.agw;

import java.time.Instant;

/**
 * What the adapter's {@code status} reads for one placement (P15-1): liveness, the agent's health facts, the plain
 * digest the agent reported for the host file, the container's start time and the generation the agent last persisted.
 * {@code reportedSha256} is a plain digest of rendered bytes — {@code toString} omits it; persisted state holds only
 * the keyed hash. <b>Hash form:</b> the wire carries bare lowercase hex; this field is the DP canonical form
 * {@code sha256:<hex>} (the gateway maps with {@code dpHash}), so comparing it to a {@link CommandPayload} digest needs
 * no conversion.
 *
 * @param live          the agent's session is open and its lease holds the placement
 * @param health        {@code null} when no probe is declared or none was reported
 * @param reportedSha256 {@code null} when the agent reported none
 * @param persistedGeneration the last generation the agent reported persisting; {@code null} when none
 * @param startedAt     host clock, no offset; {@code null} when unknown
 */
public record AgentObservation(boolean live, AgentHealthObservation health, String reportedSha256,
        Long persistedGeneration, Instant startedAt, Instant observedAt) {
    @Override
    public String toString() {
        return "AgentObservation[live=" + live + ", health=" + health + ", persistedGeneration=" + persistedGeneration
                + ", startedAt=" + startedAt + ", observedAt=" + observedAt + ", reportedSha256=<redacted>]";
    }
}
