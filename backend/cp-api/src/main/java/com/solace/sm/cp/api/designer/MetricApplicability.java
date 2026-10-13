package com.solace.sm.cp.api.designer;

import java.util.List;
import java.util.Map;

/**
 * Which metrics a configuration makes collectable (plan 011 M-Q15, {@code OR-74}).
 *
 * <p>The control plane's own predicate grammar ({@code UI-D2}: equality, implicitly AND-ed) evaluated over the
 * defaults-applied, normalized DRAFT values: exactly the evaluation {@code project()} makes for a field's visibility,
 * extracted and shared, never re-implemented. It is the draft and not the running values: a generation's list is the
 * set the collector reads once that generation has been restarted into, so it describes what the draft will make true.
 * (Deriving the list from {@code project()}'s metric {@code state} is wrong: that state compares running with draft, and
 * {@code pending-config-change} means either direction.)
 *
 * <p>Pure, stateless and thread-safe. The data plane never evaluates a predicate: it stores the list this returns.
 */
public interface MetricApplicability {

    /**
     * The names of the metrics active for the draft, each once, in ordinal order.
     *
     * <p>A component instance contributes the metrics of its component type whose {@code when} holds over THAT
     * instance's values (a metric with no {@code when} always holds). An instance whose type the package does not
     * declare for {@code packageVersion} contributes nothing; the selection is not validated here.
     *
     * @param packageVersion the declared package version, as {@code project()} takes it ({@code 1}, {@code "v2"}), which
     *                       scopes components and fields by their {@code version}; {@code null} means the package's last
     *                       declared version, exactly as {@code project()} reads it
     * @param draft          instance key to field key to the raw draft value; may be empty (package defaults only)
     */
    List<String> activeMetricNames(PackageModel.SolutionPackage pkg, Object packageVersion,
            StateModel.ComponentSelection selection, Map<String, Map<String, Object>> draft);
}
