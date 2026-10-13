package com.solace.sm.dp.apply.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every CHECK {@link ApplyCommand} mirrors, refused in Java — one test per rule, each asserting the
 * message NAMES the constraint it is standing in for.
 *
 * <p>The naming assertion is the point: {@code SchemaConstraintsIT} proves the same rules fire in
 * PostgreSQL, and the two halves are only recognisable as the same rule if the Java half says which
 * CHECK it duplicates. A bare {@code IllegalArgumentException} would leave a reader unable to tell
 * whether the schema still needs the constraint.
 */
class ApplyCommandTest {

    private static final ScopeKey SCOPE = new ScopeKey(new TenantId(UUID.randomUUID()),
            new InstanceId(UUID.randomUUID()), PlacementId.INSTANCE_WIDE);

    @Test
    void aLegalApplyCommandIsAccepted() {
        ApplyCommand cmd = new ApplyCommand(SCOPE, Operation.APPLY, 7L, Strategy.MANUAL, null,
                "principal-1");
        assertEquals(7L, cmd.generation());
        assertEquals(Strategy.MANUAL, cmd.strategy());
    }

    @Test
    void aLegalRestartAndALegalStopAreAcceptedToo() {
        new ApplyCommand(SCOPE, Operation.RESTART, null, Strategy.ROLLING_HEALTH_GATED, null, "p");
        new ApplyCommand(SCOPE, Operation.STOP, null, null, null, "p");
    }

    @Test
    void anApplyWithoutAGenerationIsRefusedByTheJavaMirrorOfApplyHasGeneration() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.APPLY, null, Strategy.MANUAL, null, "p"));
        assertTrue(e.getMessage().contains("apply_has_generation"), e.getMessage());
    }

    @Test
    void aRestartCarryingAGenerationIsRefusedByTheSameRuleFromTheOtherSide() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.RESTART, 3L, Strategy.SCHEDULED, null, "p"));
        assertTrue(e.getMessage().contains("apply_has_generation"), e.getMessage());
    }

    @Test
    void aNonPositiveGenerationIsRefusedByTheJavaMirrorOfGenerationPositive() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.APPLY, 0L, Strategy.MANUAL, null, "p"));
        assertTrue(e.getMessage().contains("generation_positive"), e.getMessage());
    }

    @Test
    void anApplyWithoutAStrategyIsRefusedByTheJavaMirrorOfLifecycleHasStrategy() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.APPLY, 1L, null, null, "p"));
        assertTrue(e.getMessage().contains("lifecycle_has_strategy"), e.getMessage());
    }

    @Test
    void aStopCarryingAStrategyIsRefusedBySameRuleFromTheOtherSide() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.STOP, null, Strategy.MANUAL, null, "p"));
        assertTrue(e.getMessage().contains("lifecycle_has_strategy"), e.getMessage());
    }

    @Test
    void aManualRestartIsRefusedByTheJavaMirrorOfRestartNotManual() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.RESTART, null, Strategy.MANUAL, null, "p"));
        assertTrue(e.getMessage().contains("restart_not_manual"), e.getMessage());
    }

    @Test
    void restartAllWithoutAnOutageConfirmationIsRefusedByTheJavaMirrorOfFullOutageConfirmed() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.APPLY, 1L, Strategy.RESTART_ALL, null, "p"));
        assertTrue(e.getMessage().contains("full_outage_confirmed"), e.getMessage());
    }

    @Test
    void restartAllWithAnOutageConfirmationIsAcceptedTheNeighbouringPositiveControl() {
        ApplyCommand cmd = new ApplyCommand(SCOPE, Operation.APPLY, 1L, Strategy.RESTART_ALL,
                "duty-manager-3", "p");
        assertEquals("duty-manager-3", cmd.outageConfirmedBy());
    }

    @Test
    void aBlankRequestedByIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ApplyCommand(SCOPE, Operation.STOP, null, null, null, "   "));
        assertTrue(e.getMessage().contains("requested_by"), e.getMessage());
    }

    @Test
    void aNullScopeOrOperationIsRefused() {
        assertThrows(NullPointerException.class,
                () -> new ApplyCommand(null, Operation.STOP, null, null, null, "p"));
        assertThrows(NullPointerException.class,
                () -> new ApplyCommand(SCOPE, null, null, null, null, "p"));
    }
}
