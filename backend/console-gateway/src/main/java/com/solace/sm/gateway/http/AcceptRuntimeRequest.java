package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.UUID;

/**
 * Request body of {@code acceptRuntimeConfig} (console.yaml: {@code baseStateVersion} and {@code placementId} are both
 * required, {@code additionalProperties: false}). Structural faults - a missing field, a wrong type, a stray key - are
 * {@code 400}, as {@link StageConfigRequest}.
 *
 * <p>⛔ {@code I5}: there is no organization, no host, no path and no command here. {@code placementId} names which
 * placement's file is read back (OR-60) and is resolved to a LIVE placement of the path's instance before anything is read.
 */
public record AcceptRuntimeRequest(Long baseStateVersion, UUID placementId) {

    public AcceptRuntimeRequest {
        if (baseStateVersion == null || baseStateVersion < 0) {
            throw new IllegalArgumentException("baseStateVersion is required and not negative");
        }
        if (placementId == null) {
            throw new IllegalArgumentException("placementId is required: it names which placement's file is read back");
        }
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
