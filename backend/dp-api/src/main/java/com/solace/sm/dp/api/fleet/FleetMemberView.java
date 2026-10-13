package com.solace.sm.dp.api.fleet;

import java.util.UUID;

/**
 * One member of a fleet, as the journal holds it ({@code fleet-apply.md} section 7's {@code FleetMember}).
 *
 * <p>{@code applyId}, {@code requestState} and {@code requestStateVersion} are present <b>exactly when the member is
 * {@code admitted}</b>: before that the pre-minted id is a reservation naming no {@code apply_request}, and for a
 * {@code skipped} or {@code not_admitted} member it never will, so no id is ever shown that {@code observeApply} would
 * answer {@code 404} for.
 *
 * @param disposition {@code refused | pending | admitted | not_admitted | skipped}
 * @param settled     {@code succeeded | failed}, written once and never changed; absent while unsettled
 */
public record FleetMemberView(int ordinal, Integer batch, UUID instanceId, UUID placementId, long generation,
        String disposition, UUID applyId, String settled, String refusalCode, String admissionRefusal,
        String requestState, Long requestStateVersion) {
}
