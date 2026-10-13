package com.solace.sm.gateway.http.metrics;

import com.solace.sm.cp.api.designer.PackageModel;
import java.util.Map;
import java.util.Objects;

/**
 * One metric a package version declares on one component, with the facts the catalog needs and the package's own
 * words for them. Read from the document the control plane stored, never from the data plane.
 *
 * @param restartSignalDeclared the component declares a {@code restartSignal} (M-Q9 (d'))
 */
record DeclaredMetric(String componentType, PackageModel.PackageMetric metric, boolean restartSignalDeclared) {

    DeclaredMetric {
        Objects.requireNonNull(componentType, "componentType");
        Objects.requireNonNull(metric, "metric");
    }

    String name() {
        return metric.name();
    }

    String sourceKind() {
        Object kind = source().get("kind");
        return kind instanceof String text ? text : "";
    }

    /** The package's {@code onUnavailable}, {@code degrade} when it states none (the schema's default). */
    String onUnavailable() {
        Object value = source().get("onUnavailable");
        return "error".equals(value) ? "error" : "degrade";
    }

    boolean declaresPort() {
        return source().get("port") instanceof Number;
    }

    /** OQ-7 (b): a missing flag means the metric IS in the default layer. */
    boolean defaultChart() {
        return metric.defaultChart() == null || metric.defaultChart();
    }

    private Map<String, Object> source() {
        return metric.source() == null ? Map.of() : metric.source();
    }
}
