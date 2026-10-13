package com.solace.sm.dp.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.api.health.HealthState;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Plan 010 T7.2 - what a probe's observation means (S0-11, OR-39, M3). The vocabulary is the notify one:
 * {@code unreachable} is OR-39's "UNKNOWN, not DOWN" (a host or network problem), {@code not_exposed} is "reachable, but
 * the health endpoint is absent or unexposed" (STATE-VOCABULARY.md), and a service that is reachable and says it is
 * down - or does not answer - is {@code RED}.
 */
class ProbeResultMapperTest {

    private static Observation.Answered answered(int status, String body) {
        return new Observation.Answered(status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static HealthState actuator(Observation o) {
        return ProbeResultMapper.map("actuator-health", o);
    }

    @Test
    void anActuatorUpAnswerIsGreen() {
        assertEquals(HealthState.GREEN, actuator(answered(200, "{\"status\":\"UP\"}")));
        assertEquals(HealthState.GREEN, actuator(answered(200, "{\"status\":\"UP\",\"components\":{\"db\":{\"status\":\"UP\"}}}")));
    }

    @Test
    void anActuatorDownOrOutOfServiceAnswerIsRed() {
        // M3: Boot maps DOWN and OUT_OF_SERVICE to 503 by default
        assertEquals(HealthState.RED, actuator(answered(503, "{\"status\":\"DOWN\"}")));
        assertEquals(HealthState.RED, actuator(answered(503, "{\"status\":\"OUT_OF_SERVICE\"}")));
    }

    @Test
    void theBodyDecidesWhenTheStatusAndTheBodyDisagree() {
        // a proxy or a customised mapping can answer 200 with DOWN, or 503 with UP: never GREEN on the status code alone
        assertEquals(HealthState.RED, actuator(answered(200, "{\"status\":\"DOWN\"}")));
        assertEquals(HealthState.RED, actuator(answered(503, "{\"status\":\"UP\"}")));
    }

    @Test
    void unknownIsNotGreen() {
        // M3: UNKNOWN maps to 200 in Boot. The service cannot say it is up, so it is not green. FLAGGED in the commit.
        assertEquals(HealthState.RED, actuator(answered(200, "{\"status\":\"UNKNOWN\"}")));
    }

    @Test
    void aBodyThatIsNotAnActuatorHealthDocumentIsNotGreen() {
        assertEquals(HealthState.RED, actuator(answered(200, "")));
        assertEquals(HealthState.RED, actuator(answered(200, "<html>ok</html>")));
        assertEquals(HealthState.RED, actuator(answered(200, "{\"state\":\"UP\"}")));
        assertEquals(HealthState.RED, actuator(answered(200, "{\"status\":1}")));
        assertEquals(HealthState.RED, actuator(answered(200, "[\"UP\"]")));
    }

    @Test
    void anAbsentOrSecuredEndpointIsNotExposedNotRed() {
        for (int status : new int[] {404, 401, 403}) {
            assertEquals(HealthState.NOT_EXPOSED, actuator(answered(status, "")), "http " + status);
        }
    }

    @Test
    void otherErrorStatusesAreRed() {
        for (int status : new int[] {500, 502, 504, 400, 301}) {
            assertEquals(HealthState.RED, actuator(answered(status, "{\"status\":\"UP\"}")), "http " + status);
        }
    }

    @Test
    void connectionRefusedOnTheDeclaredPortIsNotExposed() {
        assertEquals(HealthState.NOT_EXPOSED, actuator(Observation.REFUSED));
        assertEquals(HealthState.NOT_EXPOSED, ProbeResultMapper.map("http-status", Observation.REFUSED));
    }

    @Test
    void aTransportFailureIsUnreachableNeverRed() {
        // Review Focus 3, OR-39: "UNKNOWN, not DOWN"
        assertEquals(HealthState.UNREACHABLE, actuator(Observation.UNREACHABLE));
        assertEquals(HealthState.UNREACHABLE, ProbeResultMapper.map("http-status", Observation.UNREACHABLE));
    }

    @Test
    void aReachableHostWhoseServiceDoesNotAnswerIsRed() {
        assertEquals(HealthState.RED, actuator(Observation.SILENT));
        assertEquals(HealthState.RED, ProbeResultMapper.map("http-status", Observation.SILENT));
    }

    @Test
    void anHttpStatusProbeIsGreenOnAnyTwoHundredAndNeverReadsTheBody() {
        assertEquals(HealthState.GREEN, ProbeResultMapper.map("http-status", answered(200, "anything")));
        assertEquals(HealthState.GREEN, ProbeResultMapper.map("http-status", answered(204, "")));
        assertEquals(HealthState.RED, ProbeResultMapper.map("http-status", answered(503, "")));
        assertEquals(HealthState.RED, ProbeResultMapper.map("http-status", answered(302, "")));
        assertEquals(HealthState.NOT_EXPOSED, ProbeResultMapper.map("http-status", answered(404, "")));
    }

    @Test
    void anUnknownKindOrADeferredObservationIsAProgrammingError() {
        assertThrows(IllegalArgumentException.class, () -> ProbeResultMapper.map("tcp", Observation.REFUSED));
        assertThrows(IllegalArgumentException.class, () -> ProbeResultMapper.map("actuator-health", Observation.DEFERRED));
    }
}
