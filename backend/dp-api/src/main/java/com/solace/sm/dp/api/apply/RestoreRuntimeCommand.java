package com.solace.sm.dp.api.apply;

import java.util.Objects;
import java.util.UUID;

/**
 * Restore-runtime (OR-45, {@code B/D12}'s manual drift reconcile): re-apply SM's CURRENT desired generation to one
 * placement, overwriting whatever drift the host holds.
 *
 * @param placementId required: v1 refuses instance-wide apply (BE-D23, OR-60)
 * @param strategy    one of the five engine tokens; the gateway has already defaulted an absent one to {@code manual}
 * @param outageConfirmedBy REQUIRED for {@code restart-all}, null otherwise: the PRINCIPAL ID that confirmed the outage,
 *                    written by the gateway from the session (plan 012 T2, {@code OPS-Q7} C-2); checked in the
 *                    constructor so a command without it never reaches the service, which mints a generation first
 * @param actor       the session's principal id - never a request field
 */
public record RestoreRuntimeCommand(UUID placementId, String strategy, String outageConfirmedBy, UUID actor) {

    public RestoreRuntimeCommand {
        Objects.requireNonNull(placementId, "placementId");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(actor, "actor");
        OutageConfirmation.require(strategy, outageConfirmedBy);
    }
}
