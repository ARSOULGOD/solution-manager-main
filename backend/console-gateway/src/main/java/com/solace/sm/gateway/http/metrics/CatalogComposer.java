package com.solace.sm.gateway.http.metrics;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.metrics.query.MetricsQueryService;
import com.solace.sm.dp.api.metrics.query.ObservedMetric;
import com.solace.sm.gateway.http.Availability;
import com.solace.sm.gateway.http.AvailabilityState;
import com.solace.sm.gateway.http.ErrorCode;
import com.solace.sm.gateway.http.GatewayRejectionException;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code getMetricsCatalog} (row 56): the control plane's DECLARED metadata for the package versions in use by the
 * instances the caller reaches, composed with what the data plane OBSERVES, the two read CONCURRENTLY (plan 011 P11-7.2,
 * {@code OR-10}).
 *
 * <p><b>A plane that cannot answer is never an empty list that looks like data.</b> The control-plane half failing is
 * {@code 503}. The data-plane half failing leaves each entry's observed fields ({@code lastSeen}, {@code collector})
 * absent AND sets that entry's required {@code observedAvailability} to {@code unavailable} with a reason (W2-06); a
 * metric nobody has ever scraped has the same absent fields with {@code live}. The array stays bare, so the surface's
 * availability travels on each entry.
 *
 * <p>⚠️ <b>One entry per metric NAME across every reachable instance</b>, from the first instance (in the data plane's
 * order) that declares it. Where instances disagree on a fact that is per instance, the entry reports the more
 * cautious value and never the more flattering: {@code durability} is {@code best-effort} if any declaring instance's
 * source is, and {@code restartDetection} is {@code inferred} unless every declaring instance has a signal of the same
 * kind. A package version in use by one instance and not another therefore never makes the catalog claim more than any
 * instance can deliver.
 */
@Component
public class CatalogComposer {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogComposer.class);
    private static final String BROKER_PUSH_SOURCE = "solace_event";

    private final MonitoringInstances instances;
    private final MetricsQueryService query;
    private final ThreadPoolExecutor dataPlaneReads;

    public CatalogComposer(MonitoringInstances instances, MetricsQueryService query) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.query = Objects.requireNonNull(query, "query");
        // Bounded, and when full the request thread runs the read itself: the planes then serialize, which is slower and
        // never a refusal (the same shape as HomeOrchestrator's).
        this.dataPlaneReads = new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "sm-metrics-dp-read");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    void shutdown() {
        dataPlaneReads.shutdownNow();
    }

    /** @param entityType the declaring component type to restrict to, or null for every type */
    public List<MetricsWire.MetricCatalogEntry> catalog(Session session, String entityType) {
        UUID organization = session.organizationId();
        List<InstanceView> reachable = instances.reachableActive(session, Permission.METRICS_QUERY);
        if (reachable.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = reachable.stream().map(InstanceView::instanceId).collect(Collectors.toSet());

        // The data plane's half starts first and runs beside the control plane's on this thread.
        CompletableFuture<Optional<List<ObservedMetric>>> dataPlane = CompletableFuture.supplyAsync(() -> {
            try {
                return Optional.of(query.observed(organization, entityType, ids));
            } catch (RuntimeException failed) {
                // Only the class: a driver message can carry a host, a role or a path.
                LOG.warn("metrics catalog: the data plane could not report what is observed ({})",
                        failed.getClass().getSimpleName());
                return Optional.empty();
            }
        }, dataPlaneReads);

        Map<String, Declaration> declared;
        try {
            declared = declarations(organization, reachable, entityType);
        } catch (RuntimeException failed) {
            if (failed instanceof GatewayRejectionException) {
                throw failed;
            }
            LOG.warn("metrics catalog: the control plane could not be read ({})", failed.getClass().getSimpleName());
            throw new GatewayRejectionException(ErrorCode.SERVICE_UNAVAILABLE);
        }

        Optional<List<ObservedMetric>> observed;
        try {
            observed = dataPlane.join();
        } catch (CompletionException failed) {
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            observed = Optional.empty();
        }
        Map<String, ObservedMetric> observedByName = new LinkedHashMap<>();
        observed.ifPresent(list -> list.forEach(o -> observedByName.putIfAbsent(o.name(), o)));
        Availability availability = observed.isPresent()
                ? new Availability(AvailabilityState.LIVE, null, null, null)
                : new Availability(AvailabilityState.UNAVAILABLE, null,
                        "the data plane could not report what has been observed", null);

        List<MetricsWire.MetricCatalogEntry> out = new ArrayList<>();
        declared.forEach((name, declaration) -> out.add(entry(declaration, observedByName.get(name), availability)));
        return out;
    }

    // ---- the control plane's half --------------------------------------------------------------------------------

    private Map<String, Declaration> declarations(UUID organization, List<InstanceView> reachable, String entityType) {
        MonitoringInstances.PackageReads reads = instances.packages(organization);
        Map<String, Declaration> byName = new LinkedHashMap<>();
        for (InstanceView instance : reachable) {
            for (DeclaredMetric metric : reads.declared(instance)) {
                if (entityType != null && !entityType.equals(metric.componentType())) {
                    continue;
                }
                byName.computeIfAbsent(metric.name(), name -> new Declaration(metric)).merge(instance, metric);
            }
        }
        return byName;
    }

    /** What every reachable instance's declaration of one metric name says, merged cautiously. */
    private static final class Declaration {
        final DeclaredMetric first;
        boolean bestEffort;
        String restart;   // declared | docker | inferred; null until the first merge
        boolean restartMixed;

        Declaration(DeclaredMetric first) {
            this.first = first;
        }

        void merge(InstanceView instance, DeclaredMetric metric) {
            bestEffort |= BROKER_PUSH_SOURCE.equals(metric.sourceKind());
            String detection = detectionOf(instance, metric);
            if (restart == null) {
                restart = detection;
            } else if (!restart.equals(detection)) {
                restartMixed = true;
            }
        }

        String restartDetection() {
            return restartMixed || restart == null ? "inferred" : restart;
        }
    }

    /**
     * M-Q9 (d'), RestartDetection's own rule restated for the declaring side (the data plane derives the same value from
     * its rows): a polled source over {@code via-ssh} is {@code docker}; over {@code direct-http} with a restart signal
     * declared for the component it is {@code declared}; everything else, a broker-pushed source included, is
     * {@code inferred}.
     */
    static String detectionOf(InstanceView instance, DeclaredMetric metric) {
        if (BROKER_PUSH_SOURCE.equals(metric.sourceKind())) {
            return "inferred";
        }
        if (instance.acquisition() == AcquisitionMode.VIA_SSH) {
            return "docker";
        }
        return metric.restartSignalDeclared() ? "declared" : "inferred";
    }

    // ---- one entry -----------------------------------------------------------------------------------------------

    private static MetricsWire.MetricCatalogEntry entry(Declaration declaration, ObservedMetric observed,
            Availability availability) {
        DeclaredMetric first = declaration.first;
        String type = first.metric().metricType();
        boolean histogram = "histogram".equals(type);
        boolean noPort = "http_json".equals(first.sourceKind()) && !first.declaresPort();
        String unsupportedReason = histogram ? "histogram" : noPort ? "no-port-declared" : null;
        Long lastSeen = observed == null || observed.lastSeen() == null ? null : observed.lastSeen().toEpochMilli();
        MetricsWire.EntryCollector collector = observed == null ? null
                : new MetricsWire.EntryCollector("placements-serving-this-metric", observed.reasons().stream()
                        .map(r -> new MetricsWire.CollectorReasonCount(r.reason().wire(), r.last24h())).toList());
        return new MetricsWire.MetricCatalogEntry(first.name(), type, first.metric().unit(), "package-baseline",
                unsupportedReason == null ? "plottable" : "unsupported", unsupportedReason, lastSeen, List.of(),
                declaration.bestEffort ? "best-effort" : "durable", first.onUnavailable(),
                "counter".equals(type) ? declaration.restartDetection() : null, collector, availability);
    }
}
