package com.solace.sm.gateway.http;

import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code FleetMember}. {@code applyId}, {@code requestState} and
 * {@code requestStateVersion} are present exactly when {@code disposition} is {@code admitted}: before that the pre-minted
 * id is a reservation naming no request, so no id is shown that {@code observeApply} would answer {@code 404} for.
 */
public record FleetMember(int ordinal, @WireOptional Integer batch, UUID instanceId, UUID placementId, long generation,
        String disposition, @WireOptional UUID applyId, @WireOptional String settled, @WireOptional String refusalCode,
        @WireOptional String admissionRefusal, @WireOptional String requestState,
        @WireOptional Long requestStateVersion) {

    public FleetMember {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(placementId, "placementId");
        Objects.requireNonNull(disposition, "disposition");
    }
}
