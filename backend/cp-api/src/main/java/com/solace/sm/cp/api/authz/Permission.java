package com.solace.sm.cp.api.authz;

/**
 * The permission catalog — <b>exactly the 47 verbs</b> the ratified {@code D10} amendment
 * approved, and nothing else.
 *
 * <p><b>Authority:</b> {@code docs/design/D10-G2-APPROVAL-2026-09-15.md} §1.1 (rows 1–35),
 * §1.7 (rows 36–38, ruling {@code OR-9}), §1.9.1 (rows 39–43, ruling {@code OR-121}, confirmed by
 * name in {@code OR-122}), §1.10.1 (rows 44–47, ruling {@code OR-130}) and §1.11.1 (rows 48–51, ruling {@code OR-126} A8,
 * level and scope {@code OR-128} (4); numbered 44–47 by plan 015 and renumbered by the {@code OR-147} reconciliation).
 * {@code PermissionCatalogMirrorTest}
 * reads that document at test time and fails if this enum and the table disagree, so the two cannot drift.
 *
 * <p>⛔ <b>Adding a member is not a code change.</b> The catalog is closed: an operation that
 * needs a verb which is not here answers {@code 501}, never {@code 403}
 * ({@code console-integration.md} §5.6 rule 5). Widening it requires an owner ruling, and
 * without one a new constant would grant something nobody approved. Open item 39 tracks the
 * proposals still outstanding — {@code group.*}, package actions, and
 * redeclare/archive (its letters (c), (e) and (i)); letters (b) and (d) were answered by §1.9 and letter (h), the agent verbs, by {@code OR-126} A8 / {@code OR-128} (4), §1.10.
 *
 * <p>⚠️ Three other vocabularies exist in this tree and none of them is this one: the
 * frontend's {@code catalog.ts} has a different 35, the demo launcher has four hard-coded
 * strings, and {@code dev/frontend}'s {@code D18} records a third. Cite this enum.
 */
public enum Permission {

    // ── §1.1 rows 1–35 ────────────────────────────────────────────────────────

    INSTANCE_BIND_TARGET("instance.bindTarget", ConsequenceClass.STANDARD),
    TARGET_USE("target.use", ConsequenceClass.STANDARD),
    SOLUTION_VIEW("solution.view", ConsequenceClass.STANDARD),
    /** ⚠️ Organization-scoped, not install-scoped — ruling {@code OR-14}. */
    PACKAGE_LOAD("package.load", ConsequenceClass.HIGH),
    INSTANCE_VIEW("instance.view", ConsequenceClass.STANDARD),
    INSTANCE_CREATE("instance.create", ConsequenceClass.STANDARD),
    CONFIG_VIEW("config.view", ConsequenceClass.STANDARD),
    CONFIG_EDIT("config.edit", ConsequenceClass.STANDARD),
    CONFIG_APPLY("config.apply", ConsequenceClass.HIGH),
    CONFIG_ROLLBACK("config.rollback", ConsequenceClass.HIGH),
    CONFIG_OVERRIDE_WARNING("config.overrideWarning", ConsequenceClass.HIGH),
    INSTANCE_RESTART("instance.restart", ConsequenceClass.HIGH),
    HEALTH_VIEW("health.view", ConsequenceClass.STANDARD),
    LOGS_READ("logs.read", ConsequenceClass.STANDARD),
    LOGS_SET_LEVEL("logs.setLevel", ConsequenceClass.HIGH),
    METRICS_QUERY("metrics.query", ConsequenceClass.STANDARD),
    METRICS_EXPORT("metrics.export", ConsequenceClass.HIGH),
    METRICS_FORCE_RAW("metrics.forceRaw", ConsequenceClass.HIGH),
    METRIC_DECLARE("metric.declare", ConsequenceClass.STANDARD),
    DASHBOARD_VIEW("dashboard.view", ConsequenceClass.STANDARD),
    DASHBOARD_EDIT("dashboard.edit", ConsequenceClass.STANDARD),
    DASHBOARD_RESET("dashboard.reset", ConsequenceClass.STANDARD),
    TARGET_VIEW("target.view", ConsequenceClass.STANDARD),
    TARGET_CREATE("target.create", ConsequenceClass.HIGH),
    ROLE_VIEW("role.view", ConsequenceClass.STANDARD),
    ROLE_EDIT("role.edit", ConsequenceClass.HIGH),
    ROLE_ASSIGN("role.assign", ConsequenceClass.HIGH),
    USER_VIEW("user.view", ConsequenceClass.STANDARD),
    USER_CREATE("user.create", ConsequenceClass.HIGH),
    /** ⚠️ {@code HIGH} by ruling {@code OR-13} — neither the mockup nor §16 had marked it. */
    USER_DEACTIVATE("user.deactivate", ConsequenceClass.HIGH),
    MESH_TICKET_MINT("mesh.ticket.mint", ConsequenceClass.STANDARD),
    MESH_STREAM_GRANT("mesh.stream.grant", ConsequenceClass.HIGH),
    MESH_STREAM_REVOKE("mesh.stream.revoke", ConsequenceClass.HIGH),
    /** Governs the Admin access-review screen, <b>not</b> {@code GET /cp/auth/identity} ({@code OR-15}). */
    AUTHZ_INTROSPECT_SELF("authz.introspect.self", ConsequenceClass.STANDARD),
    AUTHZ_INTROSPECT_ANY("authz.introspect.any", ConsequenceClass.HIGH),

    // ── §1.7 rows 36–38, approved 2026-09-16 by ruling OR-9 ───────────────────

    /**
     * ⚠️ The <i>operation</i> targets an instance or one placement; the <i>binding</i> is at the
     * instance ({@code OR-16}). Audited and idempotent: stopping a stopped instance succeeds.
     */
    INSTANCE_STOP("instance.stop", ConsequenceClass.HIGH),
    /** Scope-filtered and server-redacted. Administrator-only in v1. */
    AUDIT_VIEW("audit.view", ConsequenceClass.STANDARD),
    /** Profile and login fields only — status changes stay {@link #USER_DEACTIVATE}. */
    USER_EDIT("user.edit", ConsequenceClass.HIGH),

    // ── §1.9.1 rows 39–43, ruled 2026-10-07 by OR-121 and confirmed by name in OR-122 ───────

    /**
     * Instance scope. Removes an instance from SM's inventory and management; <b>never touches the
     * workload</b> ({@code BE-D49}, {@code OPS-Q2} U-C). Held by the Administrator and the Operator.
     */
    INSTANCE_UNDECLARE("instance.undeclare", ConsequenceClass.HIGH),
    /**
     * Instance scope. Required <i>in addition to</i> {@code config.apply}/{@code config.rollback} and
     * {@code instance.restart} to select {@code rolling-no-validation} or {@code restart-all}
     * ({@code BE-D51}). Held by the Administrator and the Operator.
     */
    INSTANCE_RESTART_OVERRIDE_SAFETY("instance.restart.overrideSafety", ConsequenceClass.HIGH),
    /** The target's ownership scope. Read/edit/re-pin/rotate/retire a target's connection ({@code BE-D54}). Administrator-only. */
    TARGET_EDIT("target.edit", ConsequenceClass.HIGH),
    /** Create sub-organizations and environments; rename a node ({@code BE-D57}). Administrator-only. STANDARD. */
    HIERARCHY_EDIT("hierarchy.edit", ConsequenceClass.STANDARD),
    /** <b>Install</b> scope: an organization-bound Administrator cannot raise its own quota. Administrator-only. */
    ENTITLEMENT_SET("entitlement.set", ConsequenceClass.HIGH),

    // ── §1.10.1 rows 44–47, ruled 2026-10-09 by OR-130 ──────────────────────

    /** Reach scope, shaped exactly like {@code target.view}. Held by Viewer, Operator and Administrator. */
    CONNECTION_VIEW("connection.view", ConsequenceClass.STANDARD),
    /** Named scope (the body's {@code ownershipScope}), shaped exactly like {@code target.create}. Administrator-only. */
    CONNECTION_CREATE("connection.create", ConsequenceClass.HIGH),
    /** Named scope (the connection's own), shaped exactly like {@code target.edit}. Administrator-only. */
    CONNECTION_EDIT("connection.edit", ConsequenceClass.HIGH),
    /** Named scope. No Target equivalent — a Target is retired, never deleted. Administrator-only. */
    CONNECTION_DELETE("connection.delete", ConsequenceClass.HIGH),

    // ── §1.11.1 rows 48–51 (44–47 before the OR-147 reconciliation), ruled 2026-10-08 by OR-126 (A8); level and scope by OR-128 (4) ──

    /**
     * Environment scope. Issue an enrolment token for an agent; the token creates the agent's target, so issuing also
     * requires {@code target.create} at the environment. Administrator-only. An agent itself holds no verb
     * ({@code AGW-D13}).
     */
    AGENT_ENROL("agent.enrol", ConsequenceClass.HIGH),
    /** Environment scope. Revoke an agent identity: sticky, ends its session and every stream ({@code AGW-D19}). Administrator-only. */
    AGENT_REVOKE("agent.revoke", ConsequenceClass.HIGH),
    /** Environment scope. Ask a connected agent to drain and reconnect later ({@code AGW-D18}). Administrator and Operator. */
    AGENT_DRAIN("agent.drain", ConsequenceClass.STANDARD),
    /** Environment scope. List and read agents, their session and lease state. Read-only: Administrator, Operator and Viewer. */
    AGENT_VIEW("agent.view", ConsequenceClass.STANDARD);

    private final String id;
    private final ConsequenceClass consequence;

    Permission(String id, ConsequenceClass consequence) {
        this.id = id;
        this.consequence = consequence;
    }

    /** The wire identifier, as the amendment spells it. Never derive this from {@link #name()}. */
    public String id() {
        return id;
    }

    public ConsequenceClass consequence() {
        return consequence;
    }

    /**
     * Looks a verb up by its wire id.
     *
     * @throws UnknownPermissionException if no catalog member has that id — ⛔ <b>never</b>
     *         returns null and never invents a member, because a lookup miss here means the
     *         caller is naming a verb the owner did not approve.
     */
    public static Permission byId(String id) {
        for (Permission p : values()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        throw new UnknownPermissionException(id);
    }
}
