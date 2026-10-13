package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ApplyId;

/**
 * What §3.3's admission transaction decided. A refusal is a RETURN VALUE, not an exception: every
 * refusal here is an expected, operator-facing answer ("something else is running on this scope"),
 * and modelling expected answers as exceptions is how callers end up catching
 * {@code RuntimeException}.
 */
public sealed interface AdmissionOutcome
        permits AdmissionOutcome.Admitted, AdmissionOutcome.AlreadyAdmitted, AdmissionOutcome.Refused {

    /** The request row is committed in {@code REQUESTED}; the engine may now plan its steps. */
    record Admitted(ApplyId applyId) implements AdmissionOutcome {
    }

    /**
     * Plan 010 T11.2 ({@code fleet-apply.md} section 4.2): a request already exists under the pre-minted id the caller
     * supplied. Nothing was written. Answered only to a caller that SUPPLIED an id (a fleet's re-admission after a
     * crash between admission and the member's record); a single submit supplies none and can never see it.
     */
    record AlreadyAdmitted(ApplyId applyId) implements AdmissionOutcome {
    }

    /** Nothing was written (the transaction rolled back if it had to). */
    record Refused(RefusalReason reason, String detail) implements AdmissionOutcome {
    }
}
