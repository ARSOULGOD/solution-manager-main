package com.solace.sm.cp.api.authz;

/**
 * Every kind of thing that can be authorized — one constant per row of
 * {@code D11-AMENDMENT-PROPOSAL.md} §3.2's {@code scopePathOf} table, <b>as superseded by
 * owner rulings</b>.
 *
 * <p><b>Totality is the point.</b> An algorithm is only as total as this enum: anything absent
 * ships outside the check. {@code ScopeResolution} must return a path for every constant here.
 *
 * <p>⚠️ <b>Two rulings changed this table, and both change code:</b>
 * <ul>
 *   <li>{@code OR-16} <b>adds {@link #SOLUTION}</b>, which §3.2 has no row for, and makes an
 *       instance's path five elements rather than four.</li>
 *   <li>{@code OR-14} moves {@link #SOLUTION_PACKAGE} from §3.2's install-wide {@code []} to
 *       <b>organization</b>. §3.2's own text — <i>"only a {@code []} binding reaches
 *       package.load"</i> — is stale; the ruling names the verb and its scope, so under
 *       {@code I4} the ruling controls.</li>
 * </ul>
 */
public enum ResourceKind {

    ORGANIZATION,
    SUB_ORGANIZATION,
    ENVIRONMENT,

    /** ⚠️ Added by ruling {@code OR-16}. §3.2 has no row for it. */
    SOLUTION,

    INSTANCE,

    /**
     * ⛔ <b>Authorized through its owning instance, never on its own.</b> Present here because
     * a placement is a thing operations target; absent from {@link ScopePath} because
     * {@code OR-16} makes it a non-grantable level.
     */
    PLACEMENT,

    /** Carries its own {@code ownership_scope} path — Targets are outside the ownership tree ({@code SM-D23}). */
    DEPLOYMENT_TARGET,

    /** Carries its own {@code ownership_scope} path, shaped exactly like {@link #DEPLOYMENT_TARGET} ({@code BE-D66}, {@code OR-130}). */
    CONNECTION,

    /** ⚠️ Organization-scoped by ruling {@code OR-14}, not §3.2's {@code []}. */
    SOLUTION_PACKAGE,

    /** Install-wide in v1: {@code []}. Built-in roles only. */
    ROLE_DEFINITION,

    /** Organization-level: administering a principal is an organization act. */
    PRINCIPAL,

    /** Inherits the path of the resource it concerns, so audit visibility follows the thing audited. */
    AUDIT_RECORD
}
