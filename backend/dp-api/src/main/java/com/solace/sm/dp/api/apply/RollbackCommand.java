package com.solace.sm.dp.api.apply;

import java.util.Objects;
import java.util.UUID;

/**
 * An operator rollback, as the gateway hands it to the data plane (OR-42).
 *
 * <p>The target is named exactly one of two ways, mirroring the contract's {@code oneOf}: {@code applyId} - the
 * target is the highest eligible generation BELOW that apply's generation on the placement ("undo this apply") - or
 * {@code generation} - the target directly, which must itself be eligible.
 *
 * @param placementId required: a rollback is an apply, and v1 refuses instance-wide apply (BE-D23, OR-60)
 * @param applyId     an apply request of this instance, or null
 * @param generation  the generation to copy forward, or null
 * @param strategy    one of the five engine tokens; {@code scheduled} answers 501 until it is built
 * @param outageConfirmedBy REQUIRED for {@code restart-all}, null otherwise: the PRINCIPAL ID that confirmed the outage,
 *                    written by the gateway from the session (plan 012 T2, {@code OPS-Q7} C-2). Checked here, in the
 *                    constructor, so a command that lacks it never reaches the service - which would otherwise have
 *                    copied a generation forward before the engine's own check refused it
 * @param actor       the session's principal id - never a request field
 */
public record RollbackCommand(UUID placementId, UUID applyId, Long generation, String strategy,
        String outageConfirmedBy, UUID actor) {

    public RollbackCommand {
        Objects.requireNonNull(placementId, "placementId");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(actor, "actor");
        OutageConfirmation.require(strategy, outageConfirmedBy);
        if ((applyId == null) == (generation == null)) {
            throw new IllegalArgumentException("exactly one of applyId and generation names the rollback target");
        }
        if (generation != null && generation < 1) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }
}
