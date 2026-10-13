package com.solace.sm.dp.api.apply;

/**
 * The one rule about {@code restart-all}'s outage confirmation that every command carrying a strategy shares (plan 012
 * T2, {@code OPS-Q7} C-2; {@code docs/design/backend/ops-501.md} section 5.3): {@code restart-all} asserts a full outage,
 * so a command that names it without a confirmation is not a command.
 *
 * <p>The gateway is the first line - it answers {@code 400} before any data-plane call - and the engine's own
 * {@code ApplyCommand} check plus the {@code full_outage_confirmed} / {@code fleet_outage_confirmed} CHECKs are the last.
 * This is the line between them: a rollback or a restore copies a generation forward BEFORE the engine's check, so
 * without it a refused {@code restart-all} would first mint a generation and then surface as an
 * {@code IllegalArgumentException}.
 */
public final class OutageConfirmation {

    /** The strategy token that asserts a full outage. */
    public static final String RESTART_ALL = "restart-all";

    private OutageConfirmation() {
    }

    /** @throws IllegalArgumentException when {@code strategy} is {@code restart-all} and the confirmation is absent or blank */
    public static void require(String strategy, String outageConfirmedBy) {
        if (RESTART_ALL.equals(strategy) && (outageConfirmedBy == null || outageConfirmedBy.isBlank())) {
            throw new IllegalArgumentException("outageConfirmedBy is required when strategy is restart-all");
        }
    }
}
