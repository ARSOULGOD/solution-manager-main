package com.solace.sm.cp.api.designer;

import java.util.List;
import java.util.Map;

/**
 * The Designer view-model projector port ({@code OR-34}): "A Java {@code project()} behind
 * {@code cp-api}, locked to the 33 shared fixtures." Satisfies {@code D4} 🔒; amends {@code
 * FE-D17} (PROPOSED).
 *
 * <p>Pure: data in, data out. No DOM, no HTTP, no RBAC (FE-D22 overlays after), no secret
 * resolution — identical contract to {@code contracts/view-models/src/project.ts}'s {@code
 * project()}, which this port must match byte-for-byte on the 33 shared fixtures under
 * {@code contracts/view-models/fixtures/viewmodel/}.
 */
public interface DesignerProjector {

    /**
     * @param pkg the resolved Solution Package, or {@code null} if none could be resolved for
     *     the declared version (the caller has already tried and failed — this port does not
     *     resolve packages itself)
     * @param selection which component instances to project
     * @param draft draft values, namespaced per component instance: instanceKey -&gt; (fieldKey
     *     -&gt; rawValue)
     * @param observed the runtime-observed state (version, config, replicas, restart-observed
     *     values)
     * @param catalog options resolved from an SM-owned catalog, keyed by entity (UI-D22); empty
     *     map if none supplied
     * @param requestedPackageVersion the package version to project against, or {@code null} to
     *     use the package's latest declared version
     */
    ViewModel project(
            PackageModel.SolutionPackage pkg,
            StateModel.ComponentSelection selection,
            Map<String, Map<String, Object>> draft,
            StateModel.ObservedState observed,
            Map<String, List<Object>> catalog,
            Object requestedPackageVersion);
}
