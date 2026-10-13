package com.solace.sm.dp.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.AgentTelemetryDeclarationsPort;
import com.solace.sm.dp.api.agw.TelemetryDeclaration;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.apply.events.DeclarationsChangeSink;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentTelemetryWatermarkRepository;
import com.solace.sm.dp.inventory.PlacementRecord;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.metrics.collect.CollectionRows;
import com.solace.sm.dp.metrics.collect.RestartObservedGenerations;
import com.solace.sm.dp.metrics.ingest.IngestRows;
import com.solace.sm.events.publisher.TransactionBoundary;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the agent is told to collect, per placement of its ACTIVE lease (plan 015 T3.4, {@code OR-101}, {@code OR-130} D-4): the
 * package's {@code http_json} counter and gauge metrics, and its declared health probe, read from DP rows only (the DP reads no
 * package). The session is fenced first; a placement the lease names that is no longer live, or whose instance has no recorded
 * active list, declares nothing (it collects nothing, as the pull path's {@code CollectionPlanBuilder} would).
 *
 * <p>The selection mirrors that builder: the version's definitions intersected with the active list of the newest
 * {@code restart-observed} generation (or generation 0's), {@code histogram} metrics and sources that are not {@code http_json}
 * with a {@code port} and a {@code path} excluded. Differences from the pull path, by design: the container port is declared as the
 * package wrote it ({@code TelemetryDeclaration.Metric.containerPort}; the agent maps it to the published host port), and the
 * cadence is the fixed {@code OR-73} 5 seconds, never the package's {@code intervalSeconds} (AGW-D27 item 4).
 *
 * <p>A package {@code queryParams} map is carried as a query string appended to the path (sorted, form-encoded), because the
 * declaration has no field for it and omitting it would fetch a different document.
 */
public final class AgentTelemetryDeclarationsService implements AgentTelemetryDeclarationsPort, DeclarationsChangeSink {

    private static final Logger LOG = LoggerFactory.getLogger(AgentTelemetryDeclarationsService.class);

    /** Runs the action at once: no transaction to wait for (the kits, and a caller outside one). */
    private static final TransactionBoundary IMMEDIATE = new TransactionBoundary() {
        @Override
        public boolean inTransaction() {
            return false;
        }

        @Override
        public void afterCommit(Runnable action) {
            action.run();
        }
    };

    /** OR-73: the agent collects every 5 seconds, pushing a batch each minute. */
    public static final Duration COLLECTION_INTERVAL = Duration.ofSeconds(5);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcAgentTelemetryWatermarkRepository leases;
    private final IngestRows.Placements placements;
    private final CollectionRows rows;
    private final RestartObservedGenerations generations;
    private final PlacementRows placementRows;
    private final TransactionBoundary boundary;
    private final Set<Consumer<UUID>> listeners = new CopyOnWriteArraySet<>();

    public AgentTelemetryDeclarationsService(JdbcAgentTelemetryWatermarkRepository leases, IngestRows.Placements placements,
            CollectionRows rows, RestartObservedGenerations generations, PlacementRows placementRows) {
        this(leases, placements, rows, generations, placementRows, IMMEDIATE);
    }

    /** As above; {@code boundary} defers the signal to the commit of the transaction the change was made in. */
    public AgentTelemetryDeclarationsService(JdbcAgentTelemetryWatermarkRepository leases, IngestRows.Placements placements,
            CollectionRows rows, RestartObservedGenerations generations, PlacementRows placementRows,
            TransactionBoundary boundary) {
        this.boundary = Objects.requireNonNull(boundary, "boundary");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.rows = Objects.requireNonNull(rows, "rows");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.placementRows = Objects.requireNonNull(placementRows, "placementRows");
    }

    @Override
    public void onDeclarationsChanged(Consumer<UUID> affectedAgents) {
        listeners.add(Objects.requireNonNull(affectedAgents, "affectedAgents"));
    }

    /**
     * The data plane changed an instance's declared set, or may have ({@link DeclarationsChangeSink}): after the change COMMITS, tell
     * every subscriber which agents are enrolled on a target the instance has a placement on. The agents are found from the DP's own
     * placement and identity rows (the placement's target, which is an agent target), never from a lease (a lease's placement set is
     * a snapshot) and never from anything an agent sent (I5). The notice is a prompt: the gateway then asks the agent to renew when
     * its lease no longer names the live placement set, and re-sends the declarations the lease already covers. With no subscriber (no gateway) nothing is read. A failure of one
     * subscriber is logged by class and never reaches the committing caller or another subscriber.
     */
    @Override
    public void changed(TenantId tenant, InstanceId instance) {
        if (listeners.isEmpty()) {
            return;
        }
        Runnable notify = () -> {
            try {
                for (UUID agent : leases.agentsOnTargetsOf(tenant.value(), instance.value())) {
                    for (Consumer<UUID> listener : listeners) {
                        try {
                            listener.accept(agent);
                        } catch (RuntimeException e) {
                            LOG.warn("a declarations-change subscriber failed ({})", e.getClass().getSimpleName());
                        }
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("the declarations-change signal could not find the affected agents ({}); the lease renewal is the backstop",
                        e.getClass().getSimpleName());
            }
        };
        if (boundary.inTransaction()) {
            boundary.afterCommit(notify);
        } else {
            notify.run();
        }
    }

    @Override
    public List<TelemetryDeclaration> forAgent(AgentSessionRef ref) {
        JdbcAgentTelemetryWatermarkRepository.Leased leased = leases.leased(ref);
        List<TelemetryDeclaration> out = new ArrayList<>();
        for (UUID placementId : leased.placementIds()) {
            placements.resolve(leased.tenantId(), placementId)
                    .ifPresent(context -> out.add(declare(leased.tenantId(), context)));
        }
        return List.copyOf(out);
    }

    private TelemetryDeclaration declare(UUID tenant, IngestRows.PlacementContext context) {
        long generation = generations.latestRestartObservedGeneration(new TenantId(tenant), new InstanceId(context.instanceId()))
                .orElse(0);
        Optional<List<String>> activeList = rows.activeMetrics(tenant, context.instanceId(), generation);
        Set<String> active = activeList.map(Set::copyOf).orElse(Set.of());
        List<TelemetryDeclaration.Metric> metrics = new ArrayList<>();
        if (!active.isEmpty()) {
            for (MetricDefinitionSpec spec : rows.definitions(tenant, context.catalogSolutionId(), context.packageVersion())) {
                if (active.contains(spec.metricName())) {
                    metric(spec).ifPresent(metrics::add);
                }
            }
        }
        return new TelemetryDeclaration(context.placementId(), COLLECTION_INTERVAL, metrics,
                probe(tenant, context).orElse(null));
    }

    private static Optional<TelemetryDeclaration.Metric> metric(MetricDefinitionSpec spec) {
        boolean collected = "counter".equals(spec.metricType()) || "gauge".equals(spec.metricType());
        if (!collected || !"http_json".equals(spec.sourceKind())) {
            return Optional.empty();
        }
        JsonNode source;
        try {
            source = JSON.readTree(spec.sourceJson());
        } catch (JsonProcessingException e) {
            return Optional.empty();                      // a definition that does not parse is not collectable; nothing is echoed
        }
        if (source == null || !source.path("port").canConvertToInt() || !source.hasNonNull("path")
                || !source.get("path").isTextual()) {
            return Optional.empty();                      // portless (OQ-24 (b)) or malformed: admitted, never collected
        }
        int port = source.get("port").asInt();
        if (port < 1 || port > 65535) {
            return Optional.empty();
        }
        String pointer = source.hasNonNull("pointer") ? source.get("pointer").asText() : null;
        boolean degrade = !"error".equals(source.path("onUnavailable").asText("degrade"));
        return Optional.of(new TelemetryDeclaration.Metric(spec.metricName(), spec.metricType(), spec.unit(), port,
                path(source), pointer, degrade));
    }

    private static String path(JsonNode source) {
        String path = source.get("path").asText();
        JsonNode params = source.path("queryParams");
        if (!params.isObject() || params.isEmpty()) {
            return path;
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        params.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue().asText()));
        StringJoiner query = new StringJoiner("&");
        sorted.forEach((k, v) -> query.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return path + (path.contains("?") ? "&" : "?") + query;
    }

    /** The placement's declared probe, or empty when the package declares none (then nothing is probed, OR-101). */
    private Optional<HealthProbeSpec> probe(UUID tenant, IngestRows.PlacementContext context) {
        return placementRows.findLive(tenant, context.instanceId(), context.placementId())
                .filter(placement -> placement.healthKind().isPresent())
                .map(AgentTelemetryDeclarationsService::probe);
    }

    private static HealthProbeSpec probe(PlacementRecord placement) {
        return new HealthProbeSpec(placement.healthKind().get(), placement.healthPath().orElseThrow(),
                placement.healthPort().orElseThrow(), placement.healthStrength().orElseThrow());
    }
}
