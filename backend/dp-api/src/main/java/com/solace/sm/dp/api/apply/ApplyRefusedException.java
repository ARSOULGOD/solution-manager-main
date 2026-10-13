package com.solace.sm.dp.api.apply;

import java.util.Objects;

/**
 * The data plane refused a request for a reason the caller can act on. The message is fixed per reason: nothing the
 * request carried, and no id, is ever in it ({@code I6}).
 */
public final class ApplyRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why. The gateway maps each onto exactly one status. */
    public enum Reason {
        /** The instance, the placement or the named request is not this organization's: indistinguishable. */
        NOT_FOUND,
        /** The generation does not exist for this instance (never staged, or retired). */
        GENERATION_NOT_STAGED,
        /** An apply, restart or unresolved indeterminate request already holds an overlapping scope. */
        SCOPE_BUSY,
        /** A stop is already in flight on this exact scope. */
        STOP_IN_FLIGHT,
        /** The generation is at or below the fence for this scope: a lower one was already staged or applied. */
        GENERATION_NOT_ABOVE_WATERMARK,
        /** A concurrent admission won the race for the same scope. */
        CONCURRENT_ADMISSION,
        /**
         * OR-42: the named rollback target was not written to this placement ({@code STAGED}, {@code APPLIED} or
         * {@code SUPERSEDED} after staging), or no eligible generation lies below the named apply's.
         */
        ROLLBACK_TARGET_NOT_ELIGIBLE,
        /**
         * W2-01: the instance's latest generation holds a designer edit that was never written to this placement. A
         * level change copies the latest generation, so it would carry that edit to the host under {@code logs.setLevel}
         * alone; the edit needs its own {@code config.apply}.
         */
        UNAPPLIED_DRAFT_PENDING
    }

    private final transient Reason reason;

    public ApplyRefusedException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
