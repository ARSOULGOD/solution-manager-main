package com.solace.sm.dp.metrics.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.RestartSignalSpec;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.inventory.InstanceRecord;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRecord;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.metrics.collect.CollectionPlan.Metric;
import com.solace.sm.dp.metrics.collect.CollectionPlan.Source;
import com.solace.sm.dp.metrics.collect.CollectionPlan.Unit;
import com.solace.sm.dp.metrics.collect.fetch.FetchRequest;
import com.solace.sm.dp.metrics.collect.fetch.MetricFetcher.RestartRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Decides what one tenant's collector reads this tick (plan 011 P11-4.3), from DP rows only: "the DP reads no package"
 * (plan 010 T7.2). Pure of I/O beyond its tenant-scoped read ports; the plan it returns is a value.
 *
 * <p>Which placements: the tenant's {@code active} instances' <b>live</b> placements. A {@code pending} instance (between
 * {@code reserve} and {@code activate}, BE-D32), an {@code abandoned} one and a retired placement are never planned (S-K);
 * their series stay and remain queryable until retention ages the partitions out.
 *
 * <p>Which metrics: the version's definitions, intersected with <b>the active list of the newest generation that reached
 * {@code restart-observed}</b>, or generation 0's (the declare-time set) when none has (M-Q15). A generation with no
 * recorded list collects nothing: every generation is born with one, so its absence is a defect and a defect must not
 * guess. {@code histogram} metrics, sources that are not {@code http_json}, an {@code http_json} source with no
 * {@code port} (OQ-24 (b): admitted at load, never collected, shown {@code unsupported}) and a port nobody mapped to a
 * host port are excluded.
 *
 * <p>The address is NOT here. In {@code direct-http} mode the fetcher reads the target's registered address fresh from
 * the target named by each request on every fetch (OR-82), so a re-target changes the plan's {@code targetId} and
 * nothing is carried forward. The host port is the placement's mapped one (M-Q14), never the package's container port.
 */
public final class CollectionPlanBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final InstanceRows instances;
    private final PlacementRows placements;
    private final CollectionRows rows;
    private final RestartObservedGenerations generations;
    private final int defaultIntervalSeconds;
    private final PlacementTransports transports;

    /**
     * @param defaultIntervalSeconds the interval a polled metric that declares none is read at (OQ-9 (a)); the ingest
     *                               path's own default, so the segment's interval and the cadence cannot diverge
     */
    public CollectionPlanBuilder(InstanceRows instances, PlacementRows placements, CollectionRows rows,
            RestartObservedGenerations generations, int defaultIntervalSeconds) {
        this(instances, placements, rows, generations, defaultIntervalSeconds, PlacementTransports.NONE);
    }

    /**
     * @param transports says which targets are agent targets, whose placements are never planned (plan 015 T3.1): the agent
     *                   delivers its own telemetry (T3.4), and a collector fetch to an agent target has no address to reach
     */
    public CollectionPlanBuilder(InstanceRows instances, PlacementRows placements, CollectionRows rows,
            RestartObservedGenerations generations, int defaultIntervalSeconds, PlacementTransports transports) {
        this.transports = Objects.requireNonNull(transports, "transports");
        this.instances = Objects.requireNonNull(instances, "instances");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.rows = Objects.requireNonNull(rows, "rows");
        this.generations = Objects.requireNonNull(generations, "generations");
        if (defaultIntervalSeconds < 1) {
            throw new IllegalArgumentException("the default interval must be at least one second");
        }
        this.defaultIntervalSeconds = defaultIntervalSeconds;
    }

    private record Version(UUID solution, String version) {
    }

    private record Definition(MetricDefinitionSpec spec, JsonNode source) {
    }

    public CollectionPlan build(TenantId tenant) {
        Map<Version, List<Definition>> definitions = new HashMap<>();
        Map<Version, Map<String, JsonNode>> signals = new HashMap<>();
        List<Unit> units = new ArrayList<>();
        for (InstanceRecord instance : instances.list(tenant.value(), InstanceLifecycle.ACTIVE, Optional.empty())) {
            Set<String> active = activeNames(tenant, instance);
            if (active.isEmpty()) {
                continue;
            }
            Version version = new Version(instance.catalogSolutionId(), instance.declaredPackageVersion());
            List<Definition> defs = definitions.computeIfAbsent(version, v -> collectable(
                    rows.definitions(tenant.value(), v.solution(), v.version())));
            Map<String, JsonNode> signalsByComponent = signals.computeIfAbsent(version,
                    v -> signalSources(rows.restartSignals(tenant.value(), v.solution(), v.version())));
            for (PlacementRecord placement : placements.live(tenant.value(), instance.instanceId())) {
                if (transports.isAgentTarget(tenant.value(), placement.targetId())) {
                    continue;                                   // an agent placement delivers its own telemetry (T3.4)
                }
                units.addAll(unitsOf(tenant, instance, placement, active, defs, signalsByComponent));
            }
        }
        return new CollectionPlan(tenant, units);
    }

    /** The names active under the generation in force; empty when none was recorded (collect nothing). */
    private Set<String> activeNames(TenantId tenant, InstanceRecord instance) {
        long generation = generations.latestRestartObservedGeneration(tenant, new InstanceId(instance.instanceId()))
                .orElse(0);
        return rows.activeMetrics(tenant.value(), instance.instanceId(), generation).map(Set::copyOf)
                .orElse(Set.of());
    }

    private List<Unit> unitsOf(TenantId tenant, InstanceRecord instance, PlacementRecord placement, Set<String> active,
            List<Definition> defs, Map<String, JsonNode> signalSources) {
        Map<Integer, Integer> hostPorts = rows.endpoints(tenant.value(), placement.placementId());
        PlacementRef ref = new PlacementRef(tenant, new InstanceId(instance.instanceId()),
                new PlacementId(placement.placementId()));
        // interval -> request -> metrics: metrics sharing a URL (and an interval) share one fetch
        Map<Integer, Map<FetchRequest, List<Metric>>> byInterval = new LinkedHashMap<>();
        Set<String> components = new HashSet<>();
        for (Definition def : defs) {
            MetricDefinitionSpec spec = def.spec();
            if (!active.contains(spec.metricName())) {
                continue;
            }
            Integer hostPort = hostPorts.get(def.source().path("port").asInt(-1));
            if (hostPort == null) {
                continue;                                   // portless (OQ-24 (b)) or never mapped: not collectable
            }
            int interval = spec.intervalSeconds() != null ? spec.intervalSeconds() : defaultIntervalSeconds;
            FetchRequest request = new FetchRequest(ref, placement.targetId(), hostPort,
                    def.source().path("path").asText(), queryParams(def.source().path("queryParams")));
            String pointer = def.source().hasNonNull("pointer") ? def.source().get("pointer").asText() : null;
            byInterval.computeIfAbsent(interval, i -> new LinkedHashMap<>())
                    .computeIfAbsent(request, r -> new ArrayList<>())
                    .add(new Metric(spec.metricName(), spec.componentType(), pointer));
            components.add(spec.componentType());
        }
        Map<String, RestartRequest.Signal> signals = instance.acquisition() == AcquisitionMode.DIRECT_HTTP
                ? signalsFor(components, signalSources, hostPorts) : Map.of();
        List<Unit> units = new ArrayList<>();
        byInterval.forEach((interval, requests) -> {
            List<Source> sources = new ArrayList<>();
            requests.forEach((request, metrics) -> sources.add(new Source(request, metrics)));
            units.add(new Unit(ref, placement.targetId(), instance.acquisition(), interval, sources, signals));
        });
        return units;
    }

    private static Map<String, RestartRequest.Signal> signalsFor(Set<String> components, Map<String, JsonNode> sources,
            Map<Integer, Integer> hostPorts) {
        Map<String, RestartRequest.Signal> signals = new HashMap<>();
        for (String component : components) {
            JsonNode source = sources.get(component);
            Integer hostPort = source == null ? null : hostPorts.get(source.path("port").asInt(-1));
            if (hostPort != null && source.hasNonNull("path")) {
                signals.put(component, new RestartRequest.Signal(hostPort, source.get("path").asText(),
                        source.hasNonNull("pointer") ? source.get("pointer").asText() : null));
            }
        }
        return signals;
    }

    /** Counter and gauge metrics whose source is an {@code http_json} one with a port, parsed once per version. */
    private static List<Definition> collectable(List<MetricDefinitionSpec> specs) {
        List<Definition> out = new ArrayList<>();
        for (MetricDefinitionSpec spec : specs) {
            boolean collectedType = "counter".equals(spec.metricType()) || "gauge".equals(spec.metricType());
            if (!collectedType || !"http_json".equals(spec.sourceKind())) {
                continue;
            }
            JsonNode source = parse(spec.sourceJson());
            if (source != null && source.has("port") && source.hasNonNull("path")) {
                out.add(new Definition(spec, source));
            }
        }
        return out;
    }

    private static Map<String, JsonNode> signalSources(List<RestartSignalSpec> specs) {
        Map<String, JsonNode> out = new HashMap<>();
        for (RestartSignalSpec spec : specs) {
            JsonNode source = parse(spec.sourceJson());
            if (source != null) {
                out.put(spec.componentType(), source);
            }
        }
        return out;
    }

    private static Map<String, String> queryParams(JsonNode node) {
        Map<String, String> params = new TreeMap<>();
        node.fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
        return params;
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            return null;
        }
    }
}
