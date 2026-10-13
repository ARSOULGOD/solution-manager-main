package com.solace.sm.dp.apply.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.apply.RestoreRuntimeCommand;
import com.solace.sm.dp.api.apply.RollbackCommand;
import com.solace.sm.dp.api.apply.SubmitApplyCommand;
import com.solace.sm.dp.api.fleet.FleetApplyCommand;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 012 T2 ({@code OPS-Q7} C-2; design section 5.3, finding F7): the middle line of the three that stand behind
 * {@code restart-all}'s outage confirmation. The gateway answers {@code 400} first; the {@code full_outage_confirmed}
 * CHECKs are last; THESE constructors are between them, and they are what stops a rollback or a restore - which copies a
 * generation forward before the engine's own check - from minting one for a command that was never admissible.
 *
 * <p>A command that names {@code restart-all} without a confirmation cannot be constructed, so nothing downstream can be
 * handed one. Every other strategy takes no confirmation and needs none.
 */
class OutageConfirmationCommandsTest {

    private static final UUID PLACEMENT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @Test
    void restartAllWithoutAConfirmationCannotBeBuiltForAnyOfTheFourOperations() {
        for (String missing : new String[] {null, "", "   "}) {
            String label = "confirmation=" + (missing == null ? "null" : "'" + missing + "'");
            assertRefused(label, () -> new SubmitApplyCommand(PLACEMENT, 1, "restart-all", missing, ACTOR));
            assertRefused(label, () -> new RollbackCommand(PLACEMENT, null, 1L, "restart-all", missing, ACTOR));
            assertRefused(label, () -> new RestoreRuntimeCommand(PLACEMENT, "restart-all", missing, ACTOR));
            assertRefused(label, () -> new FleetApplyCommand(UUID.randomUUID(),
                    List.of(new FleetApplyCommand.Member(UUID.randomUUID(), PLACEMENT, 1, null)), "restart-all",
                    missing, 1, "halt-remaining", ACTOR, UUID.randomUUID()));
        }
    }

    @Test
    void restartAllWithTheConfirmingPrincipalIsAccepted() {
        String principal = ACTOR.toString();
        assertDoesNotThrow(() -> new SubmitApplyCommand(PLACEMENT, 1, "restart-all", principal, ACTOR));
        assertDoesNotThrow(() -> new RollbackCommand(PLACEMENT, null, 1L, "restart-all", principal, ACTOR));
        assertDoesNotThrow(() -> new RestoreRuntimeCommand(PLACEMENT, "restart-all", principal, ACTOR));
        assertDoesNotThrow(() -> new FleetApplyCommand(UUID.randomUUID(),
                List.of(new FleetApplyCommand.Member(UUID.randomUUID(), PLACEMENT, 1, null)), "restart-all", principal,
                1, "halt-remaining", ACTOR, UUID.randomUUID()));
    }

    @Test
    void everyOtherStrategyNeedsNoConfirmation() {
        for (String strategy : new String[] {"manual", "rolling-health-gated", "rolling-no-validation", "scheduled"}) {
            assertDoesNotThrow(() -> new SubmitApplyCommand(PLACEMENT, 1, strategy, null, ACTOR), strategy);
            assertDoesNotThrow(() -> new RollbackCommand(PLACEMENT, null, 1L, strategy, null, ACTOR), strategy);
            assertDoesNotThrow(() -> new RestoreRuntimeCommand(PLACEMENT, strategy, null, ACTOR), strategy);
            assertDoesNotThrow(() -> new FleetApplyCommand(UUID.randomUUID(),
                    List.of(new FleetApplyCommand.Member(UUID.randomUUID(), PLACEMENT, 1, null)), strategy, null, 1,
                    "halt-remaining", ACTOR, UUID.randomUUID()), strategy);
        }
    }

    private static void assertRefused(String label, org.junit.jupiter.api.function.Executable build) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, build, label);
        assertTrue(refused.getMessage().contains("restart-all"), "names the strategy: " + refused.getMessage());
    }
}
