package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.UUID;

/**
 * Request body of {@code restoreRuntimeConfig} (console.yaml, OR-45, OR-60): {@code placementId} is required and
 * {@code strategy} is optional, defaulting to {@code manual} (the generic, package-driven strategies of S0-10: a
 * restarting one also needs {@code instance.restart}). The contract CLOSES the object ({@code additionalProperties:
 * false}), unlike {@code submitApply} and {@code rollbackApply}, so a stray key is a {@code 400} (an any-setter that refuses,
 * as {@link FleetApplyRequest} and {@link LoadPackageRequest} do - Spring Boot turns {@code FAIL_ON_UNKNOWN_PROPERTIES}
 * off). That matters since plan 012 T7: {@code schedule} is a meaningful key on the two sibling operations, and a client
 * reusing its scheduling form here would otherwise get a {@code 202} and a restart NOW instead of a refusal (W3 review
 * W3B-3). No stray key is ever read: no organization, no actor, no tenant ({@code I5}).
 *
 * <p>Structural faults are {@code 400}: a missing {@code placementId} (v1 refuses instance-wide apply, BE-D23) and a
 * {@code strategy} that is present but not one of the five engine tokens, and {@code restart-all} without
 * {@code outageConfirmedBy}. An explicit JSON {@code null} for {@code strategy} reads as absent. Whether the strategy is
 * SERVED ({@code 501}) is not decided here.
 *
 * <p>{@code outageConfirmedBy} is a flag, not a name (plan 012 T2, {@code OPS-Q7} C-2): any non-blank value confirms the
 * outage and is never stored - the server records the session principal's id.
 */
public record RestoreRuntimeRequest(UUID placementId, String strategy, String outageConfirmedBy) {

    static final String DEFAULT_STRATEGY = "manual";

    public RestoreRuntimeRequest {
        if (placementId == null) {
            throw new IllegalArgumentException("placementId is required: v1 refuses instance-wide apply");
        }
        if (strategy == null) {
            strategy = DEFAULT_STRATEGY;
        } else if (!SubmitApplyRequest.STRATEGIES.contains(strategy)) {
            throw new IllegalArgumentException("strategy must be one of the engine's five tokens");
        }
        SubmitApplyRequest.requireConfirmationForRestartAll(strategy, outageConfirmedBy);
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }

    /** ⛔ {@code outageConfirmedBy} names a person; it is not printed. */
    @Override
    public String toString() {
        return "RestoreRuntimeRequest[strategy=" + strategy + "]";
    }
}
