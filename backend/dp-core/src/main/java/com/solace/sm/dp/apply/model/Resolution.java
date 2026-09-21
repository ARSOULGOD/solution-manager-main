package com.solace.sm.dp.apply.model;

import java.util.Objects;

/**
 * The operator's confirmation of an {@code APPLY_INDETERMINATE} request — design §3.4's
 * evidence-bearing flavour, and the only way {@code INDETERMINATE_RESOLVED} is ever reached.
 *
 * <p>{@link #evidence} is a {@link ResolutionEvidence} rather than JSON text: the persistence layer
 * serializes it, so no caller can hand the database a string and hope
 * {@code resolution_is_evidence} catches what is wrong with it. The CHECK still runs, and still
 * proves the shape at the last line. {@code ResolutionEvidence} is sealed to
 * {@link ApplyResolutionEvidence}/{@link RestartResolutionEvidence} ({@code BE-D17}) — which one a
 * caller may pass is not enforced here, because that depends on the request's own {@code operation}
 * column, which this record does not carry; {@code OutcomeService.resolveIndeterminate} is where that
 * match is checked, against the row it already holds.
 *
 * <p><b>There is no free-text component, deliberately.</b> An earlier version carried an operator
 * {@code note}, written to §3.1's {@code resolution_note} column. An external review pointed out what
 * that is under {@code I6}: the one column an authenticated human could paste a token, a password or
 * command output into during an incident — persisted, and then printed by Spring JDBC's bind-value
 * TRACE line the moment someone turns TRACE on to diagnose that incident. A comment box needs its own
 * design (sensitive-content handling, retention, access control, logging) before any engine path
 * writes it, and v1 has none. The column stays in the DDL (schema ahead of use, stated in the design's
 * residual table); {@code ApplyRequestRepository.resolveIndeterminate} never binds it, so it is NULL
 * for every v1 resolution — {@code OutcomeTransitionsIT} asserts exactly that.
 *
 * @param resolvedBy the principal who confirmed — never a display name
 * @param outcome    whether the generation counts as applied
 * @param evidence   the typed observation set the confirmation rests on
 */
public record Resolution(String resolvedBy, ResolutionOutcome outcome, ResolutionEvidence evidence) {

    public Resolution {
        Objects.requireNonNull(resolvedBy, "resolved by");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(evidence, "resolution evidence");
    }
}
