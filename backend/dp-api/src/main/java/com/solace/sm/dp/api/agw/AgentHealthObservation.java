package com.solace.sm.dp.api.agw;

import java.time.Instant;

/**
 * Facts about the declared health probe, as the agent ran it (OR-130 D-4). The DP maps them with its own probe
 * mapper, identically to {@code dp-remote}; the DP's gate reads the <em>declared</em> strength, never
 * {@code strength} here. {@code state} is the Docker health check: an informational hint, never the gate.
 *
 * @param httpStatus     the declared probe's HTTP status; 0 = no HTTP response
 * @param actuatorStatus the {@code status} string of an actuator-health body; {@code ""} otherwise
 */
public record AgentHealthObservation(State state, Instant observedAt, Strength strength, int httpStatus,
        String actuatorStatus, Failure failure) {
    public enum State { UNSPECIFIED, HEALTHY, STARTING, UNHEALTHY, NOT_DECLARED, UNREACHABLE }

    public enum Strength { UNSPECIFIED, READINESS, LIVENESS }

    public enum Failure { NONE, REFUSED, TIMEOUT }

    public AgentHealthObservation {
        state = state == null ? State.UNSPECIFIED : state;
        strength = strength == null ? Strength.UNSPECIFIED : strength;
        failure = failure == null ? Failure.NONE : failure;
        actuatorStatus = actuatorStatus == null ? "" : actuatorStatus;
    }
}
