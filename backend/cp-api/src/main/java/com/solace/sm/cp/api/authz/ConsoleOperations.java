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
 * <p>⚠️ <b>Seven operations are absent on purpose.</b> Login establishes the session;
 * logout, identity and credential rotation use {@link LocalOperation}; and the three hierarchy
 * reads derive their result from the authenticated principal's bindings. The mirror test pins
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
            .register("listTargets", Permission.TARGET_VIEW)
            // ── data plane: inventory ────────────────────────────────────────
            .register("listInstances", Permission.INSTANCE_VIEW)
            .register("declareInstance", Permission.INSTANCE_CREATE)
            .register("getInstance", Permission.INSTANCE_VIEW)
            // ── data plane: designer ─────────────────────────────────────────
            .register("getDesigner", Permission.CONFIG_VIEW)
            .register("stageConfig", Permission.CONFIG_EDIT)
            .register("getConfigHistory", Permission.CONFIG_VIEW)
            // ── data plane: apply ────────────────────────────────────────────
            .register("submitApply", Permission.CONFIG_APPLY)
            .register("observeApply", Permission.CONFIG_VIEW)
            .register("rollbackApply", Permission.CONFIG_ROLLBACK)
            .register("stopInstance", Permission.INSTANCE_STOP)
            // ── data plane: governance ───────────────────────────────────────
            .register("getHealth", Permission.HEALTH_VIEW)
            .register("getConfigLadder", Permission.CONFIG_VIEW)
            .register("readLogs", Permission.LOGS_READ)
            .register("getLogLevel", Permission.LOGS_READ)
            .register("setLogLevel", Permission.LOGS_SET_LEVEL)
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
