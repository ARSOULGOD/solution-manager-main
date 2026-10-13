package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.UUID;

/**
 * Request body of {@code stopInstance} ({@code additionalProperties: false}). {@code placementId} absent or null means
 * the whole instance. ⛔ There is no {@code generation}, no {@code strategy} and no {@code outageConfirmedBy}: the
 * engine's {@code ApplyCommand} rejects all three on a stop, so accepting one would be an API that cannot execute -
 * each is a {@code 400} here, as is any other key.
 */
public record StopRequest(UUID placementId) {

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
