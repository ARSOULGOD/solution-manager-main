package com.solace.sm.gateway.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Request body of {@code rollbackApply} (console.yaml, OR-42): {@code strategy} and {@code placementId} are required,
 * and EXACTLY ONE of {@code applyId} and {@code generation} names the target. As {@link SubmitApplyRequest}, the object
 * is not closed here, so a stray key is ignored, and no stray key is ever read: there is no organization, no actor and no
 * tenant in this body ({@code I5}).
 *
 * <p>Structural faults are {@code 400}: a missing {@code placementId} (v1 refuses instance-wide apply, BE-D23, OR-60),
 * neither or both targets, a generation that is not an integer of at least 1 (a {@code JsonNode} on purpose: a
 * {@code Long} component would let Jackson coerce {@code 1.5} to {@code 1} and {@code "3"} to {@code 3}), a strategy
 * that is not one of the five engine tokens, and {@code restart-all} without {@code outageConfirmedBy}. Whether the
 * strategy is SERVED ({@code 501}) and whether the target is eligible ({@code 409}) are not decided here.
 *
 * <p>{@code schedule} (plan 012 T7) is optional, as for {@code submitApply}: present means "copy forward and run it inside this
 * window", and the copy-forward happens when the window opens, never at submit.
 *
 * <p>{@code outageConfirmedBy} is a flag, not a name (plan 012 T2, {@code OPS-Q7} C-2): any non-blank value confirms the
 * outage and is never stored - the server records the session principal's id. Documented in the contract as ignored.
 */
public record RollbackRequest(UUID applyId, JsonNode generation, UUID placementId, String strategy,
        String outageConfirmedBy, JsonNode schedule) {

    /** An immediate rollback: no {@code schedule}. */
    public RollbackRequest(UUID applyId, JsonNode generation, UUID placementId, String strategy,
            String outageConfirmedBy) {
        this(applyId, generation, placementId, strategy, outageConfirmedBy, null);
    }

    public RollbackRequest {
        if (placementId == null) {
            throw new IllegalArgumentException("placementId is required: v1 refuses instance-wide apply");
        }
        if (strategy == null || !SubmitApplyRequest.STRATEGIES.contains(strategy)) {
            throw new IllegalArgumentException("strategy must be one of the engine's five tokens");
        }
        SubmitApplyRequest.requireConfirmationForRestartAll(strategy, outageConfirmedBy);
        if ((applyId == null) == (generation == null)) {
            throw new IllegalArgumentException("exactly one of applyId and generation names the rollback target");
        }
        if (generation != null && (!generation.isIntegralNumber() || !generation.canConvertToLong()
                || generation.longValue() < 1)) {
            throw new IllegalArgumentException("generation is an integer of at least 1");
        }
        // Absent is an ordinary rollback; PRESENT must be a well-formed window, and an explicit JSON null is present (T7)
        if (schedule != null) {
            ScheduleWindow.parse(schedule);
        }
    }

    /** The validated window ({@code schedule}), or null when the request asks for an immediate rollback. */
    ScheduleWindow scheduleWindow() {
        return schedule == null ? null : ScheduleWindow.parse(schedule);
    }

    /** The validated generation, or null when the target is named by {@code applyId}. */
    Long generationNumber() {
        return generation == null ? null : generation.longValue();
    }

    /** ⛔ {@code outageConfirmedBy} names a person; it is not printed. */
    @Override
    public String toString() {
        return "RollbackRequest[applyId=" + applyId + ", generation=" + generation + ", strategy=" + strategy + "]";
    }
}
