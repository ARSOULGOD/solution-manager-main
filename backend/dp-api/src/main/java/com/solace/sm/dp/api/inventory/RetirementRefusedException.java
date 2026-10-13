package com.solace.sm.dp.api.inventory;

import java.util.Objects;

/**
 * The data plane refused an undeclare step for a reason the caller can act on. The message is fixed per reason: no id,
 * no other request's id and nothing the request carried is ever in it ({@code I6}).
 */
public final class RetirementRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why. The gateway maps each onto exactly one status. */
    public enum Reason {
        /**
         * The instance is not this organization's, or is not one an undeclare applies to ({@code pending},
         * {@code abandoned}, {@code retired}): indistinguishable, {@code 404} ({@code ops-501.md} section 4.5).
         */
        NOT_FOUND,
        /** An apply, stop or unresolved {@code APPLY_INDETERMINATE} request is open on the instance: {@code 409}. */
        REQUEST_IN_FLIGHT,
        /** A {@code RUNNING} fleet holds a live reservation on one of the instance's placements: {@code 409}. */
        FLEET_IN_FLIGHT,
        /**
         * A {@code PENDING} apply schedule names the instance (OR-131, W5 review W5B-5): {@code 409}. Cancel it first, or it
         * would fire into a {@code retiring} instance and close {@code REFUSED target-gone} for good.
         */
        SCHEDULE_PENDING,
        /** {@code completeRetire} on an instance no undeclare is in progress on (it is {@code active}). */
        NOT_RETIRING
    }

    private final transient Reason reason;

    public RetirementRefusedException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
