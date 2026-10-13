package com.solace.sm.dp.agent;

import static com.solace.sm.dp.api.agw.AgentHealthObservation.Failure;
import static com.solace.sm.dp.api.agw.AgentHealthObservation.State;
import static com.solace.sm.dp.api.agw.AgentHealthObservation.Strength;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.solace.sm.dp.api.agw.AgentHealthObservation;
import com.solace.sm.dp.api.health.HealthState;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Plan 015 T3.3: the agent's probe facts reach the SAME {@code ProbeResultMapper} dp-remote uses, so a gate means the same on both. */
class AgentReportIntakeMappingTest {

    private static AgentHealthObservation facts(State state, int status, String actuator, Failure failure) {
        return new AgentHealthObservation(state, Instant.EPOCH, Strength.READINESS, status, actuator, failure);
    }

    private static Optional<HealthState> map(String kind, AgentHealthObservation facts) {
        return AgentReportIntake.map(kind, facts);
    }

    @Test
    void actuatorHealthIsGreenOnlyWhenTheBodyStatusIsUp() {
        assertEquals(Optional.of(HealthState.GREEN), map("actuator-health", facts(State.HEALTHY, 200, "UP", Failure.NONE)));
        assertEquals(Optional.of(HealthState.RED), map("actuator-health", facts(State.HEALTHY, 200, "UNKNOWN", Failure.NONE)));
        assertEquals(Optional.of(HealthState.RED), map("actuator-health", facts(State.HEALTHY, 200, "", Failure.NONE)),
                "a 200 with no status is not up");
        assertEquals(Optional.of(HealthState.RED), map("actuator-health", facts(State.UNHEALTHY, 503, "DOWN", Failure.NONE)));
    }

    @Test
    void theDockerHealthCheckIsAHintAndNeverTheGate() {
        assertEquals(Optional.of(HealthState.RED), map("actuator-health", facts(State.HEALTHY, 503, "DOWN", Failure.NONE)),
                "Docker says healthy; the declared probe says down");
        assertEquals(Optional.of(HealthState.GREEN), map("actuator-health", facts(State.UNHEALTHY, 200, "UP", Failure.NONE)));
    }

    @Test
    void httpStatusReadsTheStatusAloneAndNeverTheBody() {
        assertEquals(Optional.of(HealthState.GREEN), map("http-status", facts(State.HEALTHY, 204, "", Failure.NONE)));
        assertEquals(Optional.of(HealthState.RED), map("http-status", facts(State.HEALTHY, 500, "UP", Failure.NONE)));
    }

    @Test
    void absentEndpointsAndTransportFailuresMapAsTheyDoForDpRemote() {
        assertEquals(Optional.of(HealthState.NOT_EXPOSED), map("actuator-health", facts(State.UNHEALTHY, 404, "", Failure.NONE)));
        assertEquals(Optional.of(HealthState.NOT_EXPOSED), map("actuator-health", facts(State.UNHEALTHY, 0, "", Failure.REFUSED)));
        assertEquals(Optional.of(HealthState.RED), map("actuator-health", facts(State.UNHEALTHY, 0, "", Failure.TIMEOUT)));
        assertEquals(Optional.of(HealthState.UNREACHABLE), map("actuator-health", facts(State.UNREACHABLE, 0, "", Failure.NONE)),
                "a container that is not running");
    }

    @Test
    void noProbeRunIsSilenceNotAVerdict() {
        assertEquals(Optional.empty(), map("actuator-health", null));
        assertEquals(Optional.empty(), map("actuator-health", facts(State.NOT_DECLARED, 0, "", Failure.NONE)));
        assertEquals(Optional.empty(), map("actuator-health", facts(State.UNSPECIFIED, 0, "", Failure.NONE)));
    }

    @Test
    void anUnknownDeclaredKindIsSilenceNotACrash() {
        assertEquals(Optional.empty(), map("tcp", facts(State.HEALTHY, 200, "UP", Failure.NONE)));
    }

    @Test
    void anActuatorStatusWithQuotesCannotBreakOutOfTheSynthesisedBody() {
        assertEquals(Optional.of(HealthState.RED), map("actuator-health",
                facts(State.HEALTHY, 200, "UP\",\"x\":\"", Failure.NONE)));
    }
}
