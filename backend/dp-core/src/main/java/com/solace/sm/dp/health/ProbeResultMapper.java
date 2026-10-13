package com.solace.sm.dp.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.dp.api.health.HealthState;
import java.io.IOException;

/**
 * What an {@link Observation} means for the placement's health (plan 010 T7.2; owner ruling S0-11, {@code OR-39}, M3).
 * Pure: no I/O, no clock.
 *
 * <pre>
 * Refused (nothing listens on the declared port)           -> not_exposed   "reachable, but not exposed" (a config finding)
 * Unreachable (transport failure)                          -> unreachable   OR-39: UNKNOWN, not DOWN
 * Silent (accepted, no usable answer)                      -> RED
 * Answered 404 / 401 / 403                                 -> not_exposed   the endpoint is absent or secured
 * actuator-health, 2xx and body status "UP"                -> GREEN
 * actuator-health, anything else answered                  -> RED           (DOWN / OUT_OF_SERVICE are 503 by default, M3;
 *                                                                            the BODY decides, never the status alone)
 * http-status, 2xx                                         -> GREEN         (the body is never read)
 * http-status, anything else answered                      -> RED
 * </pre>
 *
 * <p><b>Flagged, not settled by M3:</b> Boot's {@code UNKNOWN} status maps to HTTP 200; it is read as not-green
 * (RED), because a service that says it does not know cannot be called up. {@code 401/403/404 -> not_exposed} follows
 * {@code STATE-VOCABULARY.md} ("the health endpoint is absent or unexposed") and is the owner's to confirm.
 * {@code missing} and {@code bootstrap} are never produced here: both need facts the poller does not read (that the
 * instance is absent beyond N intervals; that its first restart has not happened).
 */
public final class ProbeResultMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProbeResultMapper() {
    }

    /** @param kind the placement's {@code health_kind}: {@code actuator-health} or {@code http-status} */
    public static HealthState map(String kind, Observation observation) {
        if (!"actuator-health".equals(kind) && !"http-status".equals(kind)) {
            throw new IllegalArgumentException("unknown health probe kind");
        }
        if (observation == Observation.DEFERRED) {
            throw new IllegalArgumentException("a deferred observation has no health");
        }
        if (observation == Observation.REFUSED) {
            return HealthState.NOT_EXPOSED;
        }
        if (observation == Observation.UNREACHABLE) {
            return HealthState.UNREACHABLE;
        }
        if (observation == Observation.SILENT) {
            return HealthState.RED;
        }
        Observation.Answered answered = (Observation.Answered) observation;
        int status = answered.httpStatus();
        if (status == 404 || status == 401 || status == 403) {
            return HealthState.NOT_EXPOSED;
        }
        boolean success = status >= 200 && status < 300;
        if ("http-status".equals(kind)) {
            return success ? HealthState.GREEN : HealthState.RED;
        }
        return success && reportsUp(answered.body()) ? HealthState.GREEN : HealthState.RED;
    }

    /** True only for a JSON object whose {@code status} is the string {@code UP}. */
    private static boolean reportsUp(byte[] body) {
        if (body.length == 0) {
            return false;
        }
        try {
            JsonNode status = JSON.readTree(body).path("status");
            return status.isTextual() && "UP".equals(status.textValue());
        } catch (IOException e) {
            return false;
        }
    }
}
