package com.solace.sm.dp.api.apply;

import java.util.Objects;
import java.util.UUID;

/**
 * An apply, as the gateway hands it to the data plane.
 *
 * @param placementId      required: v1 refuses instance-wide apply (BE-D23)
 * @param generation       the staged generation to apply
 * @param strategy         one of the five engine tokens, verbatim ({@code manual}, {@code rolling-health-gated}, ...)
 * @param outageConfirmedBy only meaningful for {@code restart-all} and REQUIRED for it (plan 012 T2, {@code OPS-Q7} C-2):
 *                         the PRINCIPAL ID of the session that confirmed the outage, written by the gateway - never a
 *                         string the browser typed ({@code null} for every other strategy)
 * @param actor            the session's principal id - never a request field
 */
public record SubmitApplyCommand(UUID placementId, long generation, String strategy, String outageConfirmedBy,
        UUID actor) {

    public SubmitApplyCommand {
        Objects.requireNonNull(placementId, "placementId");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(actor, "actor");
        OutageConfirmation.require(strategy, outageConfirmedBy);
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }
}
