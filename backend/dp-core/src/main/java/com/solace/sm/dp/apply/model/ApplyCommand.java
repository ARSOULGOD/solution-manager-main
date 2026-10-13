package com.solace.sm.dp.apply.model;

import java.util.Objects;

/**
 * What an operator asked for, before admission has decided anything about it.
 *
 * <p>The compact constructor mirrors the row CHECKs of design §3.1 so a malformed command fails in
 * Java first, with a sentence naming the rule, instead of arriving at PostgreSQL as a 23514 the
 * caller has to decode. <b>The CHECKs stay the last line</b> — both halves are proven, in
 * {@code ApplyCommandTest} and in {@code SchemaConstraintsIT} respectively, because a validation that
 * only exists in Java is one refactor away from not existing.
 *
 * @param scope             tenant + instance + placement (or {@link PlacementId#INSTANCE_WIDE})
 * @param operation         apply, restart or stop
 * @param generation        the config generation; present iff {@code operation == APPLY}
 *                          ({@code apply_has_generation}, {@code generation_positive})
 * @param strategy          present iff the operation is apply or restart ({@code lifecycle_has_strategy});
 *                          never {@code MANUAL} for a restart ({@code restart_not_manual})
 * @param outageConfirmedBy required by {@code full_outage_confirmed} when the strategy is
 *                          {@code restart-all}
 * @param requestedBy       the principal id — never a display name (design §3.1)
 */
public record ApplyCommand(ScopeKey scope, Operation operation, Long generation, Strategy strategy,
        String outageConfirmedBy, String requestedBy) {

    public ApplyCommand {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(operation, "operation");
        if ((operation == Operation.APPLY) != (generation != null)) {
            throw new IllegalArgumentException(
                    "apply_has_generation: an apply carries a generation and nothing else may — "
                            + operation + " with generation " + generation);
        }
        if (generation != null && generation <= 0) {
            throw new IllegalArgumentException(
                    "generation_positive: generation must be > 0, was " + generation);
        }
        boolean lifecycle = operation == Operation.APPLY || operation == Operation.RESTART;
        if (lifecycle != (strategy != null)) {
            throw new IllegalArgumentException(
                    "lifecycle_has_strategy: apply and restart carry a strategy and stop may not — "
                            + operation + " with strategy " + strategy);
        }
        if (operation == Operation.RESTART && strategy == Strategy.MANUAL) {
            throw new IllegalArgumentException(
                    "restart_not_manual: a manual restart is the operator's own act, not a request");
        }
        if (strategy == Strategy.RESTART_ALL && outageConfirmedBy == null) {
            throw new IllegalArgumentException(
                    "full_outage_confirmed: restart-all needs a named outage confirmation");
        }
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("requested_by must be a principal id, not blank");
        }
    }
}
