package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.UUID;

/**
 * Request body of {@code setLogLevel} ({@code additionalProperties: false}): exactly {@code placementId}, {@code level}
 * and {@code mode}. There is no logger name: the package names the logger, never the browser. Any other key is a
 * {@code 400}, as is a missing member; the closed vocabularies are checked by the orchestrator.
 */
public record SetLogLevelRequest(UUID placementId, String level, String mode) {

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
