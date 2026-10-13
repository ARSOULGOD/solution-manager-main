package com.solace.sm.gateway.http;

import java.util.List;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code ApplyObservation}: one request as the journal records it. {@code stateVersion} is
 * the request's own stamp. {@code placementId} and {@code generation} are omitted (not null) for an instance-wide
 * request and a stop.
 *
 * <p>{@code reconcileReason} (T6.8) is the indeterminate reconciler's give-up classifier, present only while the request
 * stays {@code APPLY_INDETERMINATE} after the reconciler gave up. Each step's {@code gateProved} (T6.9) is set by the DP only for a passed {@code rolling-health-gated} gate.
 */
public record ApplyObservation(long stateVersion, @WireOptional Availability availability, UUID applyId,
        String operation, @WireOptional UUID placementId, @WireOptional Long generation,
        @WireOptional String requestedAt, String state, boolean terminal, @WireOptional String reconcileReason,
        @WireOptional List<Step> steps) {

    /**
     * One journaled step; {@code reason} is a classifier, never raw text. The last three ({@code OR-108} (1)) are set only on
     * the step that found a retained pre-image changed: declared, non-secret dotted paths (at most 50), never a value.
     */
    public record Step(String step, @WireOptional UUID placementId, String outcome, @WireOptional String reason,
            @WireOptional String gateProved, @WireOptional List<String> changedPaths,
            @WireOptional Boolean changedPathsTruncated, @WireOptional Boolean otherContentChanged) {
    }
}
