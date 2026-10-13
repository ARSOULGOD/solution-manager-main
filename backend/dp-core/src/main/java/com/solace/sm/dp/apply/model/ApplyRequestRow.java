package com.solace.sm.dp.apply.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One {@code apply_request} row, minus the four resolution columns (which travel as a
 * {@link Resolution} because they are written together or not at all — {@code resolution_is_evidence}
 * is an equality over all of them).
 *
 * @param scope       the three fence columns
 * @param requestedAt when the operator asked
 * @param state       where SM's part of the operation currently stands (design §3.2)
 * @param terminalAt  non-null exactly when {@code state.isTerminal()} ({@code terminal_has_timestamp})
 * @param stateVersion the stamp the database assigned (OR-35, V008): null on a row not yet written, never null
 *                    on one read back. Nothing in Java sets it — the runtime role has no column privilege on it
 */
public record ApplyRequestRow(ApplyId applyId, ScopeKey scope, Operation operation, Long generation,
        Strategy strategy, String outageConfirmedBy, String requestedBy, Instant requestedAt,
        RequestState state, Instant terminalAt, Long stateVersion) {

    /** A row not yet written: no stamp. */
    public ApplyRequestRow(ApplyId applyId, ScopeKey scope, Operation operation, Long generation,
            Strategy strategy, String outageConfirmedBy, String requestedBy, Instant requestedAt,
            RequestState state, Instant terminalAt) {
        this(applyId, scope, operation, generation, strategy, outageConfirmedBy, requestedBy, requestedAt, state,
                terminalAt, null);
    }

    public ApplyRequestRow {
        Objects.requireNonNull(applyId, "apply id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(requestedBy, "requested by");
        Objects.requireNonNull(requestedAt, "requested at");
        Objects.requireNonNull(state, "state");
        if (state.isTerminal() != (terminalAt != null)) {
            throw new IllegalArgumentException(
                    "terminal_has_timestamp: " + state + " with terminalAt " + terminalAt);
        }
    }
}
