package com.solace.sm.dp.api.inventory;

import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 1 of declare (OR-27). Acquisition is per instance (OR-71) and the mode only (OR-82).
 *
 * <p><b>The host binding is exactly one of two</b> (plan 014): a legacy {@code targetId}, or a remote-server
 * {@code connectionId}. The connection id is a control-plane id the gateway has already validated; the data plane stores
 * it as an opaque value and resolves it only through its own {@code connection_runtime} projection ({@code I7}).
 *
 * @param metrics what the gateway resolved from the package for this version (plan 011 M-Q5, M-Q14, M-Q15), written in
 *                {@code reserve}'s own transaction with the placement
 */
public record DeclareInstanceCommand(UUID environmentId, UUID catalogSolutionId, String solution,
        String declaredPackageVersion, String name, UUID targetId, AcquisitionMode acquisition,
        PlacementSpec placement, UUID requestedBy, MetricsDeclaration metrics, UUID connectionId,
        DeploymentMode deploymentMode, String role) {

    /**
     * Plan 010's arity: a declare that carries no metrics declaration. It exists so plan 010's tests stay byte-unchanged.
     * ⛔ No {@code src/main} class may call it ({@code MetricsDeclarationConstructorTest}): a production declare through it
     * would silently declare no metrics.
     */
    public DeclareInstanceCommand(UUID environmentId, UUID catalogSolutionId, String solution,
            String declaredPackageVersion, String name, UUID targetId, AcquisitionMode acquisition,
            PlacementSpec placement, UUID requestedBy) {
        this(environmentId, catalogSolutionId, solution, declaredPackageVersion, name, targetId, acquisition, placement,
                requestedBy, MetricsDeclaration.none(), null, DeploymentMode.DP_REMOTE, null);
    }

    /**
     * Plan 011's arity: a target-backed {@code dp-remote} declare. It exists so plan 011's tests stay byte-unchanged.
     * ⛔ No {@code src/main} class may call it ({@code MetricsDeclarationConstructorTest}): production states its binding.
     */
    public DeclareInstanceCommand(UUID environmentId, UUID catalogSolutionId, String solution,
            String declaredPackageVersion, String name, UUID targetId, AcquisitionMode acquisition,
            PlacementSpec placement, UUID requestedBy, MetricsDeclaration metrics) {
        this(environmentId, catalogSolutionId, solution, declaredPackageVersion, name, targetId, acquisition, placement,
                requestedBy, metrics, null, DeploymentMode.DP_REMOTE, null);
    }

    public DeclareInstanceCommand(UUID environmentId, UUID catalogSolutionId, String solution,
            String declaredPackageVersion, String name, UUID targetId, AcquisitionMode acquisition,
            PlacementSpec placement, UUID requestedBy, MetricsDeclaration metrics, UUID connectionId,
            DeploymentMode deploymentMode) {
        this(environmentId, catalogSolutionId, solution, declaredPackageVersion, name, targetId, acquisition,
                placement, requestedBy, metrics, connectionId, deploymentMode, null);
    }

    public DeclareInstanceCommand {
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(catalogSolutionId, "catalogSolutionId");
        Objects.requireNonNull(solution, "solution");
        Objects.requireNonNull(declaredPackageVersion, "declaredPackageVersion");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(acquisition, "acquisition");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(deploymentMode, "deploymentMode");
        if ((targetId == null) == (connectionId == null)) {
            throw new IllegalArgumentException("exactly one of targetId and connectionId is required");
        }
        if (deploymentMode != DeploymentMode.DP_REMOTE) {
            throw new IllegalArgumentException("only the dp-remote deployment mode is supported");
        }
    }
}
