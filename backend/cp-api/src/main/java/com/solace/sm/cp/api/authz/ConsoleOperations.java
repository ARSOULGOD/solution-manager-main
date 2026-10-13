package com.solace.sm.cp.api.authz;

import java.util.Optional;

/**
 * The console's operation → verb map, mirroring {@code contracts/openapi/console.yaml}.
 *
 * <p><b>Why this exists as code and not only as YAML.</b> The OpenAPI document is checked
 * against the catalog by {@code run-checks.sh} check 11, which stops a contract naming a verb
 * nobody approved. That check cannot see the <i>server</i>: an endpoint whose handler forgets
 * to call {@link AuthorizationService#require} is invisible to it. This registry is the
 * server-side half, and {@code ConsoleOperationsMirrorTest} asserts the two halves agree
 * operation-for-operation, so neither can drift from the other or from the amendment.
 *
 * <p>⚠️ <b>Eight operations are absent on purpose.</b> Login establishes the session;
 * logout, identity and credential rotation use {@link LocalOperation}; the three hierarchy
 * reads derive their result from the authenticated principal's bindings; and
 * {@code getCapabilities} (console-integration.md §5.4 row 53) is authentication only, readable
 * by every authenticated principal of every organization. The mirror test pins
 * each operation to its exact non-catalog marker, so "it has no verb" cannot become a way to
 * skip a check.
 *
 * <p>⛔ <b>An operation that is not registered here answers {@code 501}, never {@code 403}.</b>
 */
public final class ConsoleOperations {

    private static final OperationRegistry REGISTRY = OperationRegistry.builder()
            // ── control plane ────────────────────────────────────────────────
            .register("listSolutions", Permission.SOLUTION_VIEW)
            .register("loadPackage", Permission.PACKAGE_LOAD)
            // Read-only: reuses solution.view (D-4) rather than minting a new verb against the ratified 38-verb catalog.
            .register("getSolutionPackage", Permission.SOLUTION_VIEW)
            .register("listTargets", Permission.TARGET_VIEW)
            // ── registration through the API (plan 013 T1.4; D-REG section 4): scope `named`. A scan and a registration
            // are target.create at the body's ownershipScope; the connection record is read under target.edit (OQ-13 (a)),
            // NOT target.view, so Viewer and Operator keep seeing only the envelope-only listTargets.
            .register("scanHostKeys", Permission.TARGET_CREATE)
            .register("registerTarget", Permission.TARGET_CREATE)
            .register("editTarget", Permission.TARGET_EDIT)
            .register("getTargetConnection", Permission.TARGET_EDIT)
            // ── plan 013 T1.5: the operations on an existing target. The scope is the TARGET's own (the path names it;
            // never a body), so editing, rotating and retiring are target.edit, and the connection test is target.use.
            .register("editTargetConnection", Permission.TARGET_EDIT)
            .register("rotateTargetCredential", Permission.TARGET_EDIT)
            .register("testTargetConnection", Permission.TARGET_USE)
            .register("retireTarget", Permission.TARGET_EDIT)
            // ── control plane: home (OR-45) ──────────────────────────────────
            .register("getHomeSummary", Permission.SOLUTION_VIEW)
            .register("getHomeAttention", Permission.HEALTH_VIEW)
            // ── data plane: inventory ────────────────────────────────────────
            .register("listInstances", Permission.INSTANCE_VIEW)
            .register("declareInstance", Permission.INSTANCE_CREATE)
            .register("getInstance", Permission.INSTANCE_VIEW)
            // ── data plane: placements of an existing instance (plan 008 S3, BE-D47/BE-D53). One verb adds and removes:
            // the second verb of addPlacement (target.use, at the body's target) is x-sm-permission-also and the handler's.
            .register("addPlacement", Permission.INSTANCE_BIND_TARGET)
            .register("retirePlacement", Permission.INSTANCE_BIND_TARGET)
            // ── data plane: undeclare (plan 012 T5, OPS-Q2 U-C, OPS-Q3 P-1): a verb of its own, at the instance
            .register("undeclareInstance", Permission.INSTANCE_UNDECLARE)
            // ── data plane: designer ─────────────────────────────────────────
            .register("getDesigner", Permission.CONFIG_VIEW)
            .register("stageConfig", Permission.CONFIG_EDIT)
            .register("getConfigHistory", Permission.CONFIG_VIEW)
            // ── data plane: designer — runtime drift (OR-45) ─────────────────
            .register("setConfigSecret", Permission.CONFIG_EDIT)
            .register("acceptRuntimeConfig", Permission.CONFIG_EDIT)
            .register("restoreRuntimeConfig", Permission.CONFIG_APPLY)
            // ── data plane: apply ────────────────────────────────────────────
            .register("submitApply", Permission.CONFIG_APPLY)
            .register("observeApply", Permission.CONFIG_VIEW)
            .register("rollbackApply", Permission.CONFIG_ROLLBACK)
            .register("stopInstance", Permission.INSTANCE_STOP)
            // ── data plane: scheduled applies (plan 012 T7, OPS-Q9 Sch-2). Creating a schedule is submitApply/rollbackApply with a
            // `schedule` field, authorized by THEIR verbs; listing needs config.view, cancelling any config.apply holder.
            .register("listApplySchedules", Permission.CONFIG_VIEW)
            .register("cancelApplySchedule", Permission.CONFIG_APPLY)
            // ── data plane: governance ───────────────────────────────────────
            .register("getHealth", Permission.HEALTH_VIEW)
            .register("getConfigLadder", Permission.CONFIG_VIEW)
            .register("readLogs", Permission.LOGS_READ)
            .register("getLogLevel", Permission.LOGS_READ)
            .register("setLogLevel", Permission.LOGS_SET_LEVEL)
            // ── data plane: fleet apply (REQ-013, OR-47, OR-57) ──────────────
            .register("submitFleetApply", Permission.CONFIG_APPLY)
            .register("observeFleetApply", Permission.CONFIG_VIEW)
            // ── console composition: metrics and dashboards (plan 011 P11-1.2; console-integration.md §5.4 rows 54-66).
            // Row 53 (getCapabilities) is authentication-only and is deliberately NOT registered, like the hierarchy reads.
            .register("listEntityTypes", Permission.METRICS_QUERY)
            .register("listMetricInstances", Permission.METRICS_QUERY)
            .register("getMetricsCatalog", Permission.METRICS_QUERY)
            .register("getLabelValues", Permission.METRICS_QUERY)
            .register("queryRange", Permission.METRICS_QUERY)
            .register("queryInstant", Permission.METRICS_QUERY)
            .register("exportRange", Permission.METRICS_EXPORT)
            .register("declareMetricType", Permission.METRIC_DECLARE)
            .register("getDashboard", Permission.DASHBOARD_VIEW)
            .register("saveWidget", Permission.DASHBOARD_EDIT)
            .register("setDashboardLayout", Permission.DASHBOARD_EDIT)
            .register("setWidgetHidden", Permission.DASHBOARD_EDIT)
            .register("resetDashboard", Permission.DASHBOARD_RESET)
            // ── hierarchy and entitlement administration (plan 013 T2.3; D-REG sections 5.2 and 5.3). A create is authorized at
            // the PARENT, a rename at the node, except renameOrganization, which is evaluated at INSTALL scope (OR-125 (3):
            // an organization-bound Administrator cannot rename its organization, so the install-wide name 409 is no
            // cross-tenant oracle); setEntitlement is evaluated at INSTALL scope too (an organization-bound Administrator
            // cannot raise its own quota) and listEntitlements is the ordinary solution.view read.
            .register("createSubOrganization", Permission.HIERARCHY_EDIT)
            .register("createEnvironment", Permission.HIERARCHY_EDIT)
            .register("renameOrganization", Permission.HIERARCHY_EDIT)
            .register("renameSubOrganization", Permission.HIERARCHY_EDIT)
            .register("renameEnvironment", Permission.HIERARCHY_EDIT)
            .register("listEntitlements", Permission.SOLUTION_VIEW)
            .register("setEntitlement", Permission.ENTITLEMENT_SET)
            // ── connections (plan 013 T4; BE-D66, OR-130). getConnectionTypes is authentication-only, install
            // scope, identical for every caller — deliberately NOT registered here, like getCapabilities.
            .register("listConnections", Permission.CONNECTION_VIEW)
            .register("createConnection", Permission.CONNECTION_CREATE)
            .register("updateConnection", Permission.CONNECTION_EDIT)
            .register("deactivateConnection", Permission.CONNECTION_EDIT)
            .register("deleteConnection", Permission.CONNECTION_DELETE)
            // ── local users and role assignments (plan 013 T3.2; D-REG sections 4 and 5.4). User administration is an
            // ORGANIZATION act (OR-125 (6)); a grant and a revoke are role.assign at the SCOPE (the granted one, or the stored
            // binding's own), never above it. One verb per operation, so the old PATCH is two: deactivate and reset.
            .register("listUsers", Permission.USER_VIEW)
            .register("createUser", Permission.USER_CREATE)
            .register("deactivateUser", Permission.USER_DEACTIVATE)
            .register("resetUserCredential", Permission.USER_EDIT)
            .register("listRoles", Permission.ROLE_VIEW)
            .register("listAssignments", Permission.ROLE_VIEW)
            .register("grantAssignment", Permission.ROLE_ASSIGN)
            .register("revokeAssignment", Permission.ROLE_ASSIGN)
            // ── host agents (plan 015 T4.3; D10 rows 44-47, OR-126 A8, OR-128 (4)). All environment-scoped. The issue also needs
            // target.create at the environment (the token creates a target): x-sm-permission-also, checked by the enrolment service.
            // listAgents is `reach` (agent.view held anywhere, then filtered per agent); the other three are `named`, at the agent's environment.
            .register("issueAgentEnrolment", Permission.AGENT_ENROL)
            .register("listAgents", Permission.AGENT_VIEW)
            .register("getAgent", Permission.AGENT_VIEW)
            .register("revokeAgent", Permission.AGENT_REVOKE)
            .register("drainAgent", Permission.AGENT_DRAIN)
            .build();

    private ConsoleOperations() {
    }

    public static OperationRegistry registry() {
        return REGISTRY;
    }

    /**
     * The verb that authorizes {@code operationId}, or empty when the operation carries no
     * verb.
     *
     * <p>⚠️ Empty has two causes and the caller must tell them apart: a
     * {@link LocalOperation} (authorized by the session) versus an operation nobody has
     * approved a verb for (answer {@code 501}).
     */
    public static Optional<Permission> permissionFor(String operationId) {
        return REGISTRY.permissionFor(operationId);
    }
}
