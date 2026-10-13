package com.solace.sm.dp.fleet;

import java.util.UUID;

/**
 * One {@code sm_data.fleet_member} row ({@code fleet-apply.md} section 4.3).
 *
 * @param batch            {@code ordinal / batchSize}; null for a {@code refused} member
 * @param applyId          the PRE-MINTED id the member's request is admitted under; null for a {@code refused} member.
 *                         It names no {@code apply_request} until the member is {@code admitted}
 * @param disposition      {@code refused | pending | admitted | not_admitted | skipped}
 * @param refusalCode      {@code FORBIDDEN | NOT_FOUND}, set exactly when {@code refused}
 * @param admissionRefusal the admission refusal's name, set exactly when {@code not_admitted}
 * @param settled          {@code succeeded | failed}, set once and never changed
 */
public record MemberRecord(UUID tenantId, UUID fleetId, int ordinal, Integer batch, UUID instanceId,
        UUID placementId, long generation, UUID applyId, String disposition, String refusalCode,
        String admissionRefusal, String settled) {

    public static final String REFUSED = "refused";
    public static final String PENDING = "pending";
    public static final String ADMITTED = "admitted";
    public static final String NOT_ADMITTED = "not_admitted";
    public static final String SKIPPED = "skipped";

    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";

    public boolean is(String disposition) {
        return this.disposition.equals(disposition);
    }
}
