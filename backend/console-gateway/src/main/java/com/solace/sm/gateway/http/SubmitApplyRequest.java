package com.solace.sm.gateway.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.dp.api.apply.OutageConfirmation;
import java.util.Set;
import java.util.UUID;

/**
 * Request body of {@code submitApply} (console.yaml: {@code generation}, {@code strategy} and {@code placementId} are
 * required; {@code outageConfirmedBy} is optional). The contract does not close the object, so a stray key is ignored -
 * and no stray key is ever read: there is no organization, no actor and no tenant here ({@code I5}).
 *
 * <p>Structural faults - a missing field, a wrong type, a generation below 1, a strategy that is not one of the five
 * engine tokens, and {@code restart-all} without a confirmation - are {@code 400}. Whether the strategy is SERVED
 * ({@code 501}) and whether the generation exists ({@code 422}) are not decided here.
 *
 * <p><b>{@code schedule} (plan 012 T7, {@code OPS-Q9} Sch-2)</b> is optional: absent is an ordinary apply, present is a
 * request to be admitted later inside {@code [notBefore, notAfter)} - see {@link ScheduleWindow}. A malformed one,
 * including an explicit JSON {@code null}, is {@code 400}.
 *
 * <p><b>{@code outageConfirmedBy} is a flag, not a name (plan 012 T2, {@code OPS-Q7} C-2).</b> Any non-blank value means
 * "the operator confirmed the outage"; the value is never stored. The server records the session principal's id
 * instead ({@link #confirmation}), so an audit never rests on a string the browser chose.
 */
public record SubmitApplyRequest(JsonNode generation, String strategy, UUID placementId, String outageConfirmedBy,
        JsonNode schedule) {

    static final String RESTART_ALL = OutageConfirmation.RESTART_ALL;

    /** The contract's {@code Strategy} enum, verbatim; pinned by {@code SchemaMirrorLockTest}. */
    static final Set<String> STRATEGIES = Set.of("manual", "rolling-health-gated", "restart-all",
            "rolling-no-validation", "scheduled");

    public SubmitApplyRequest {
        // A JsonNode on purpose: a Long component would let Jackson coerce 1.5 to 1 and "3" to 3.
        if (generation == null || !generation.isIntegralNumber() || !generation.canConvertToLong()
                || generation.longValue() < 1) {
            throw new IllegalArgumentException("generation is required and an integer of at least 1");
        }
        if (strategy == null || !STRATEGIES.contains(strategy)) {
            throw new IllegalArgumentException("strategy must be one of the engine's five tokens");
        }
        if (placementId == null) {
            throw new IllegalArgumentException("placementId is required: v1 refuses instance-wide apply");
        }
        requireConfirmationForRestartAll(strategy, outageConfirmedBy);
        // Absent is an ordinary apply; PRESENT must be a well-formed window, and an explicit JSON null is present (plan 012 T7)
        if (schedule != null) {
            ScheduleWindow.parse(schedule);
        }
    }

    /**
     * {@code restart-all} asserts a full outage, so it needs the operator's confirmation: absent or blank is a
     * {@code 400}, found here - while the body is read, before any secondary verb or data-plane call (design 5.3).
     */
    static void requireConfirmationForRestartAll(String strategy, String outageConfirmedBy) {
        OutageConfirmation.require(strategy, outageConfirmedBy);
    }

    /**
     * What the data plane stores as the outage confirmation: the SESSION PRINCIPAL's id for {@code restart-all}
     * ({@code OPS-Q7} C-2), and nothing for every other strategy - a name typed into a body is never stored.
     */
    static String confirmation(String strategy, UUID principalId) {
        return RESTART_ALL.equals(strategy) ? principalId.toString() : null;
    }

    /** The validated generation. */
    long generationNumber() {
        return generation.longValue();
    }

    /** The validated window ({@code schedule}), or null when the request asks for an ordinary, immediate apply. */
    ScheduleWindow scheduleWindow() {
        return schedule == null ? null : ScheduleWindow.parse(schedule);
    }

    /** ⛔ {@code outageConfirmedBy} names a person; it is not printed. */
    @Override
    public String toString() {
        return "SubmitApplyRequest[generation=" + generation + ", strategy=" + strategy + "]";
    }
}
