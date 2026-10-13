package com.solace.sm.dp.apply.model;

/**
 * {@code apply_request.resolution_observed}, typed — the shape {@code resolution_is_evidence} demands,
 * enforced at the seam so the CHECK is the last line rather than the only one.
 *
 * <p><b>{@code BE-D17}: the shape is operation-specific, and the type is now sealed on that fact.</b>
 * A single evidence shape that always carries a {@code stagedGeneration} cannot represent a
 * {@code restart} resolution at all — a restart request carries no {@code generation}
 * ({@code apply_has_generation}), so there is nothing for a {@code stagedGeneration} to certify. This
 * interface is the operation-typed split the design specifies (§3.4): {@link ApplyResolutionEvidence}
 * for {@code operation = 'apply'}, {@link RestartResolutionEvidence} for {@code operation = 'restart'}.
 * Being {@code sealed} makes the pairing exhaustive at compile time — {@code OutcomeService} and
 * {@link com.solace.sm.dp.apply.persistence.Evidence} both switch/branch on the two permitted types, and
 * a third implementation would fail to compile everywhere that matters rather than fail silently at
 * runtime.
 *
 * <p>Design §3.1's own words for what the CHECK proves, restated per shape: "resolution is EVIDENCE:
 * {@code resolution_outcome = 'applied'} feeds rule 1's monotonicity and must never rest on an
 * unsupported claim… this CHECK is evidence-shaped, deliberately not claimed evidence-complete."
 * Everything the CHECK proves for a given operation, the matching record proves first, in Java, before
 * any SQL is built; and each record proves what the CHECK cannot — that the elements are the right kind
 * of observation and that the JSON has no other keys at all.
 */
public sealed interface ResolutionEvidence permits ApplyResolutionEvidence, RestartResolutionEvidence {
}
