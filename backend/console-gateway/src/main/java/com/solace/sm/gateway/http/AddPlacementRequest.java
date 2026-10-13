package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.UUID;

/**
 * Request body of {@code addPlacement} (plan 008 S3, {@code BE-D47}; console.yaml, {@code additionalProperties: false}
 * at both levels). Exactly {@code declareInstance}'s {@code placement} object, plus the target it runs on: the SAME
 * record, so the two cannot disagree on a field, a bound or the {@code metricsPorts} grammar.
 *
 * <p>⛔ No {@code organizationId}, no probe {@code kind} or {@code path}, no metrics definition, no host of any kind: the
 * tenant is the session's ({@code I5}), the probe and the metrics come from the instance's DECLARED package version
 * (resolved by {@link PlacementOrchestrator}), and nothing about where the data plane connects comes from the browser
 * ({@code OR-82}). Structural faults are {@code 400}, exactly as {@link DeclareInstanceRequest}; the semantic ones (the
 * envelope, the grammar the database also checks) are {@code 422} naming the field.
 */
public record AddPlacementRequest(UUID targetId, DeclareInstanceRequest.Placement placement) {

    public AddPlacementRequest {
        if (targetId == null || placement == null) {
            throw new IllegalArgumentException("targetId and placement are required");
        }
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
