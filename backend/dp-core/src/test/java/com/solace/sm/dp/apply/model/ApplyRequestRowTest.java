package com.solace.sm.dp.apply.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code terminal_has_timestamp}, at the layer that refuses it first.
 *
 * <p>Plan 004's CHECK of the same name is proven against live PostgreSQL by
 * {@code SchemaConstraintsIT}. What this class records is the <b>layering</b>: the model type
 * refuses the row before the database is asked, so a wrong-shaped row surfaces as a readable
 * sentence in the engine rather than as a 23514 out of a repository. Both layers, on purpose —
 * neither is redundant, because only the database sees rows the model never built.
 */
class ApplyRequestRowTest {

    private static final ScopeKey SCOPE = new ScopeKey(new TenantId(UUID.randomUUID()),
            new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    private static ApplyRequestRow row(RequestState state, Instant terminalAt) {
        return new ApplyRequestRow(ApplyId.next(), SCOPE, Operation.APPLY, 7L, Strategy.MANUAL,
                null, "operator-1", NOW, state, terminalAt);
    }

    /**
     * {@code APPLY_INDETERMINATE} is a landing place without being terminal (§3.2: it BLOCKS the
     * scope until an operator resolves it), so stamping it would claim the request is finished.
     * This is the exact pairing {@code OutcomeService.land} encodes as
     * {@code terminal.isTerminal() ? now : null}.
     */
    @Test
    void anIndeterminateRowMayNotCarryATerminalTimestamp() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class,
                        () -> row(RequestState.APPLY_INDETERMINATE, NOW));
        assertTrue(refused.getMessage().contains("terminal_has_timestamp"),
                "the model must refuse it by the CHECK's own name. Was: " + refused.getMessage());
    }

    @Test
    void aTerminalRowMustCarryOne() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> row(RequestState.STAGED, null));
        assertTrue(refused.getMessage().contains("terminal_has_timestamp"));
    }

    @Test
    void theTwoLegalShapesAreAccepted() {
        assertEquals(RequestState.APPLY_INDETERMINATE,
                row(RequestState.APPLY_INDETERMINATE, null).state());
        assertEquals(NOW, row(RequestState.STAGED, NOW).terminalAt());
    }
}
