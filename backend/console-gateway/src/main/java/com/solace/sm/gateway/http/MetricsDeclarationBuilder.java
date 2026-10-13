package com.solace.sm.gateway.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.designer.StateModel;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.api.metrics.declare.RestartSignalSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What the gateway resolves from the control plane's package at declare (plan 011 P11-4.1; M-Q5 (a'), M-Q14 (a), M-Q15):
 * the version's metric definitions and restart signals, the placement's endpoint map and the generation-0 list. The data
 * plane reads no package: it stores what this returns, in {@code reserve}'s own transaction.
 *
 * <p><b>Definitions.</b> One per metric of every component that applies to the declared version (scoped by the
 * component's {@code version}, exactly as {@code project()} scopes it), re-checked against the same grammar the data
 * plane's CHECKs enforce, so a package stored before that grammar existed is a {@code 422} naming
 * {@code declaredPackageVersion} and never a constraint violation surfacing as a {@code 500}. ⛔ No branch on the
 * solution, the component type or a metric name.
 *
 * <p><b>Endpoints (M-Q14).</b> A row for every distinct port the collector will call: the union of the
 * {@code http_json} source ports and the components' {@code restartSignal.port}s, each defaulting to itself. A restart
 * signal's port with no metric source still gets a row: without one a direct-mode fetch of the signal has no host port
 * and a component declared {@code DECLARED} would always read as a failed signal. A body key that names a port no source
 * uses is refused ({@code 422 placement.metricsPorts}): kept, it would sit unused while the port it was meant to remap
 * fell back to its default, and the typo would show only as a metric that never arrives.
 *
 * <p><b>The digest</b> is the control plane's stored one, passed through. This class never computes one.
 */
public final class MetricsDeclarationBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The sm_data CHECKs, restated: V016's {@code metric_name_grammar}, {@code metric_type_known}, {@code metric_unit_length}. */
    private static final Pattern METRIC_NAME = Pattern.compile("[a-z][a-z0-9_.]{0,127}");
    private static final Set<String> METRIC_TYPES = Set.of("counter", "gauge", "histogram");
    private static final Set<String> SOURCE_KINDS = Set.of("http_json", "solace_event", "prometheus", "exec", "jdbc_query");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

    private static final String HTTP_JSON = "http_json";

    private MetricsDeclarationBuilder() {
    }

    /**
     * @param packageDigest      {@code LoadedPackage.digest()}, the control plane's stored digest of this version
     * @param declaredVersion    the declared package version as {@code project()} takes it ({@code 1}, {@code "v2"})
     * @param bodyMetricsPorts   {@code placement.metricsPorts}, source port (as the wire's string key) to host port;
     *                           {@code null} or empty means every port is its own
     * @param activeMetricNames  the generation-0 list, from {@code MetricApplicability}
     * @throws DeclareValidationException a metric, a signal or a body port that cannot be declared (a {@code 422})
     * @throws IllegalStateException      the digest is not the stored shape: a control-plane defect, not the caller's
     */
    static MetricsDeclaration build(String packageDigest, PackageModel.SolutionPackage pkg, Object declaredVersion,
            Map<String, Integer> bodyMetricsPorts, List<String> activeMetricNames) {
        if (packageDigest == null || !DIGEST.matcher(packageDigest).matches()) {
            throw new IllegalStateException("the catalog returned a package digest that is not sha256:<64 hex>");
        }
        List<MetricDefinitionSpec> definitions = new ArrayList<>();
        List<RestartSignalSpec> signals = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<Integer> calledPorts = new TreeSet<>();

        for (PackageModel.PackageComponent component : componentsIn(pkg, declaredVersion)) {
            if (component.metrics() != null) {
                for (PackageModel.PackageMetric metric : component.metrics()) {
                    MetricDefinitionSpec spec = definition(component.type(), metric);
                    if (!names.add(spec.metricName())) {
                        throw invalidVersion();   // a version's metric names are unique (V016's key)
                    }
                    definitions.add(spec);
                    if (HTTP_JSON.equals(spec.sourceKind())) {
                        portOf(metric.source()).ifPresent(calledPorts::add);
                    }
                }
            }
            if (component.restartSignal() != null) {
                Map<String, Object> signal = component.restartSignal();
                if (!HTTP_JSON.equals(signal.get("kind")) || (signal.get("port") != null && portOf(signal).isEmpty())) {
                    throw invalidVersion();   // the package contract admits one kind of restart signal, with a legal port
                }
                portOf(signal).ifPresent(calledPorts::add);
                signals.add(new RestartSignalSpec(component.type(), json(signal)));
            }
        }
        return new MetricsDeclaration(packageDigest, definitions, signals, endpoints(calledPorts, bodyMetricsPorts),
                activeMetricNames);
    }

    // ---- components ----------------------------------------------------------------------------------------------

    /**
     * The selection a declare evaluates the generation-0 list over: there is no operator selection yet, so it is one
     * instance of every component the package says an instance always has ({@code cardinality: exactly-one}) that applies
     * to the declared version, keyed by its type. A {@code one-of-many} or {@code zero-or-more} component is a choice the
     * operator has not made, so it contributes nothing until a stage selects it.
     */
    static StateModel.ComponentSelection declareTimeSelection(PackageModel.SolutionPackage pkg, Object declaredVersion) {
        return declareTimeSelection(pkg, declaredVersion, null);
    }

    static StateModel.ComponentSelection declareTimeSelection(PackageModel.SolutionPackage pkg, Object declaredVersion,
            String role) {
        List<StateModel.ComponentInstance> instances = new ArrayList<>();
        for (PackageModel.PackageComponent component : componentsIn(pkg, declaredVersion)) {
            String componentRole = component.role();
            boolean shared = componentRole == null || componentRole.isBlank() || "none".equals(componentRole);
            if ((role == null && "exactly-one".equals(component.cardinality()))
                    || (role != null && (shared || role.equals(componentRole)))) {
                instances.add(new StateModel.ComponentInstance(component.type(), component.type(), null));
            }
        }
        return new StateModel.ComponentSelection(List.copyOf(instances));
    }

    /** The components that apply to {@code declaredVersion}, the last declaration of a type winning, as {@code project()}. */
    public static List<PackageModel.PackageComponent> componentsIn(PackageModel.SolutionPackage pkg,
            Object declaredVersion) {
        Map<String, PackageModel.PackageComponent> byType = new LinkedHashMap<>();
        for (PackageModel.PackageComponent component : pkg.components()) {
            if (appliesTo(component.version(), declaredVersion)) {
                byType.put(component.type(), component);
            }
        }
        return List.copyOf(byType.values());
    }

    /** A missing scope means every version; numbers compare as JS numbers do ({@code 1} equals {@code 1L}). */
    private static boolean appliesTo(List<Object> declared, Object version) {
        if (declared == null) {
            return true;
        }
        for (Object candidate : declared) {
            boolean same = candidate instanceof Number a && version instanceof Number b
                    ? a.doubleValue() == b.doubleValue() : Objects.equals(candidate, version);
            if (same) {
                return true;
            }
        }
        return false;
    }

    // ---- one metric ----------------------------------------------------------------------------------------------

    private static MetricDefinitionSpec definition(String componentType, PackageModel.PackageMetric metric) {
        Map<String, Object> source = metric.source();
        if (metric.name() == null || !METRIC_NAME.matcher(metric.name()).matches()
                || metric.metricType() == null || !METRIC_TYPES.contains(metric.metricType())
                || metric.unit() == null || metric.unit().isEmpty() || metric.unit().length() > 64
                || source == null || !(source.get("kind") instanceof String kind) || !SOURCE_KINDS.contains(kind)) {
            throw invalidVersion();
        }
        Integer interval = null;
        if (source.get("intervalSeconds") != null) {
            if (!(source.get("intervalSeconds") instanceof Integer seconds) || seconds < 10 || seconds > 3600) {
                throw invalidVersion();
            }
            interval = seconds;
        }
        if (source.get("port") != null && portOf(source).isEmpty()) {
            throw invalidVersion();
        }
        // An absent defaultChart means true (OQ-7 (b)); only an explicit false opts the metric out.
        boolean defaultChart = !Boolean.FALSE.equals(metric.defaultChart());
        return new MetricDefinitionSpec(metric.name(), componentType, metric.metricType(), metric.unit(), kind,
                json(source), interval, defaultChart);
    }

    /** The container port a source or a signal names, when it names a legal one; empty when it names none. */
    private static Optional<Integer> portOf(Map<String, Object> source) {
        if (source == null || !(source.get("port") instanceof Integer port)) {
            return Optional.empty();
        }
        return port >= 1 && port <= 65_535 ? Optional.of(port) : Optional.empty();
    }

    // ---- endpoints -----------------------------------------------------------------------------------------------

    private static Map<Integer, Integer> endpoints(Set<Integer> calledPorts, Map<String, Integer> body) {
        Map<String, Integer> mapped = body == null ? Map.of() : body;
        for (String key : mapped.keySet()) {
            // The key's grammar was checked at the wire (400); here it is only whether a source calls that port.
            if (!calledPorts.contains(Integer.valueOf(key))) {
                throw new DeclareValidationException("placement.metricsPorts");
            }
        }
        Map<Integer, Integer> out = new TreeMap<>();
        for (int port : calledPorts) {
            out.put(port, mapped.getOrDefault(String.valueOf(port), port));
        }
        return out;
    }

    private static String json(Map<String, Object> source) {
        try {
            return JSON.writeValueAsString(source);
        } catch (JsonProcessingException e) {
            throw invalidVersion();   // a source that cannot be written as JSON is not a package this plane can record
        }
    }

    private static DeclareValidationException invalidVersion() {
        return new DeclareValidationException("declaredPackageVersion");
    }
}
