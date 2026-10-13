package com.solace.sm.dp.api.apply;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A scheduled apply or rollback, as the gateway hands it to the data plane (plan 012 T7, {@code OPS-Q9} Sch-2): what to
 * run ({@link Operation}, the placement, the generation or the rollback target, the INNER strategy and its outage
 * confirmation) and the window {@code [notBefore, notAfter)} it may be admitted in.
 *
 * <p>The strategy is one of the four the engine runs; {@code scheduled} is never a value here (the window is the
 * schedule's own fields, {@code OR-60} as amended by {@code OR-121}). A rollback names its target exactly one of two ways,
 * as {@link RollbackCommand} does, and is copied forward AT FIRE, never at submit.
 *
 * @param placementId       required: v1 refuses instance-wide apply (BE-D23)
 * @param generation        an apply's staged generation; null for a rollback
 * @param rollbackApplyId   a rollback's target by apply ("undo this apply"); null for an apply
 * @param rollbackGeneration a rollback's target by generation; null for an apply
 * @param strategy          one of {@code manual}, {@code rolling-health-gated}, {@code rolling-no-validation},
 *                          {@code restart-all}
 * @param outageConfirmedBy REQUIRED for {@code restart-all}, null otherwise: the PRINCIPAL ID that confirmed the outage,
 *                          written by the gateway from the session ({@code OPS-Q7} C-2); stored with the schedule and
 *                          handed to the admitted apply, so a typed name is never stored
 * @param notBefore         the window opens (inclusive)
 * @param notAfter          the window closes (exclusive); after it the schedule is {@code MISSED}, never run
 * @param actor             the session's principal id - never a request field; re-authorized at fire
 */
public record ScheduleCommand(Operation operation, UUID placementId, Long generation, UUID rollbackApplyId,
        Long rollbackGeneration, String strategy, String outageConfirmedBy, Instant notBefore, Instant notAfter,
        UUID actor) {

    /** The four strategies a schedule may carry: the engine's, without {@code scheduled} itself. */
    public static final Set<String> STRATEGIES = Set.of("manual", "rolling-health-gated", "rolling-no-validation",
            "restart-all");

    /** What the schedule will run when its window opens. */
    public enum Operation {
        APPLY("apply"),
        ROLLBACK("rollback");

        private final String wire;

        Operation(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public ScheduleCommand {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(placementId, "placementId");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(notBefore, "notBefore");
        Objects.requireNonNull(notAfter, "notAfter");
        Objects.requireNonNull(actor, "actor");
        if (!STRATEGIES.contains(strategy)) {
            throw new IllegalArgumentException("a schedule carries one of the engine's four strategies, never 'scheduled'");
        }
        OutageConfirmation.require(strategy, outageConfirmedBy);
        if (outageConfirmedBy != null && !OutageConfirmation.RESTART_ALL.equals(strategy)) {
            throw new IllegalArgumentException("only restart-all carries an outage confirmation");
        }
        if (!notAfter.isAfter(notBefore)) {
            throw new IllegalArgumentException("notAfter must be after notBefore");
        }
        if (operation == Operation.APPLY) {
            if (generation == null || generation < 1 || rollbackApplyId != null || rollbackGeneration != null) {
                throw new IllegalArgumentException("a scheduled apply names a positive generation and no rollback target");
            }
        } else {
            if (generation != null || (rollbackApplyId == null) == (rollbackGeneration == null)
                    || (rollbackGeneration != null && rollbackGeneration < 1)) {
                throw new IllegalArgumentException(
                        "a scheduled rollback names exactly one of rollbackApplyId and rollbackGeneration");
            }
        }
    }

    /** A scheduled apply of {@code generation}. */
    public static ScheduleCommand apply(UUID placementId, long generation, String strategy, String outageConfirmedBy,
            Instant notBefore, Instant notAfter, UUID actor) {
        return new ScheduleCommand(Operation.APPLY, placementId, generation, null, null, strategy, outageConfirmedBy,
                notBefore, notAfter, actor);
    }

    /** A scheduled rollback; exactly one of {@code applyId} and {@code generation} names the target. */
    public static ScheduleCommand rollback(UUID placementId, UUID applyId, Long generation, String strategy,
            String outageConfirmedBy, Instant notBefore, Instant notAfter, UUID actor) {
        return new ScheduleCommand(Operation.ROLLBACK, placementId, null, applyId, generation, strategy,
                outageConfirmedBy, notBefore, notAfter, actor);
    }
}
