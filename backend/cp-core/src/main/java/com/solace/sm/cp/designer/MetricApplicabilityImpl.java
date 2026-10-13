package com.solace.sm.cp.designer;

import com.solace.sm.cp.api.designer.MetricApplicability;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.designer.StateModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The active metric-name list of a configuration (plan 011 M-Q15, {@code OR-74}), over the same building blocks
 * {@code project()} is made of: {@link DesignerProjectorImpl}'s component and field scoping by version,
 * {@link DraftValueResolver}'s defaults-applied, normalized values, and {@link WhenEvaluator}'s predicate. It owns no
 * rule of its own beyond "a metric is active when its {@code when} holds over its instance's values".
 *
 * <p>⛔ No branch on the solution, the component type or a metric name: whatever a package declares is evaluated alike.
 */
public final class MetricApplicabilityImpl implements MetricApplicability {

    @Override
    public List<String> activeMetricNames(PackageModel.SolutionPackage pkg, Object packageVersion,
            StateModel.ComponentSelection selection, Map<String, Map<String, Object>> draft) {
        Objects.requireNonNull(pkg, "pkg");
        Objects.requireNonNull(selection, "selection");
        Map<String, Map<String, Object>> draftMap = draft == null ? Map.of() : draft;

        // The version project() would read: the one asked for, else the package's last.
        Object version = packageVersion != null ? packageVersion : lastOf(pkg.packageVersion());
        Map<String, PackageModel.PackageComponent> byType = new LinkedHashMap<>();
        for (PackageModel.PackageComponent c : pkg.components()) {
            if (DesignerProjectorImpl.inVersion(c.version(), version)) {
                byType.put(c.type(), c); // last declaration wins, as in project()
            }
        }

        TreeSet<String> active = new TreeSet<>();   // ordinal order, each name once
        for (StateModel.ComponentInstance instance : selection.instances()) {
            PackageModel.PackageComponent component = byType.get(instance.type());
            if (component == null || component.metrics() == null) {
                continue;
            }
            List<PackageModel.PackageField> fields = new ArrayList<>();
            if (component.fields() != null) {
                for (PackageModel.PackageField f : component.fields()) {
                    if (DesignerProjectorImpl.inVersion(f.version(), version)) {
                        fields.add(f);
                    }
                }
            }
            Map<String, Object> values = DraftValueResolver
                    .resolve(fields, draftMap.getOrDefault(instance.instanceKey(), Map.of())).values();
            for (PackageModel.PackageMetric metric : component.metrics()) {
                if (WhenEvaluator.evaluate(metric.when(), values).isEmpty()) {
                    active.add(metric.name());
                }
            }
        }
        return List.copyOf(active);
    }

    private static Object lastOf(List<Object> list) {
        return (list == null || list.isEmpty()) ? null : list.get(list.size() - 1);
    }
}
