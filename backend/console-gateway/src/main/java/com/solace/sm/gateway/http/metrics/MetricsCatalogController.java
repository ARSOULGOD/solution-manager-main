package com.solace.sm.gateway.http.metrics;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.gateway.http.ConsoleOperation;
import com.solace.sm.gateway.http.ErrorCode;
import com.solace.sm.gateway.http.GatewayRejectionException;
import com.solace.sm.gateway.http.SessionOrganizationResourceResolver;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The discovery reads and the one contracted refusal (rows 54-57 and 61). Rows 54-57 are {@code x-sm-scope: reach}: the
 * interceptor has required {@code metrics.query} held at one or more grants, which authorizes NO item, and every item
 * returned here is filtered through {@code ReachAuthorization#authorizedSubset} at its own scope (M-Q17, {@code OR-79}).
 *
 * <p>Row 61 ({@code declareMetricType}) is {@code metric.declare} at the caller's own organization, and in v1 answers
 * {@code 501 NOT_IMPLEMENTED}, {@code reason: metric-declare-deferred}, never {@code 403}, to every caller who holds the
 * verb (M-Q6, {@code OR-75}: quarantine is deferred, undeclared metrics are dropped and counted, never offered for typing).
 */
@RestController
@RequestMapping("/api/console/v1")
public class MetricsCatalogController {

    private final MonitoringInstances instances;
    private final CatalogComposer catalog;

    public MetricsCatalogController(MonitoringInstances instances, CatalogComposer catalog) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** Row 54: the entity types of the instances the caller reaches with {@code metrics.query}. */
    @GetMapping("/entity-types")
    @ConsoleOperation("listEntityTypes")
    public ResponseEntity<List<MetricsWire.EntityType>> listEntityTypes(Session session) {
        MonitoringInstances.PackageReads reads = instances.packages(session.organizationId());
        TreeMap<String, MetricsWire.EntityType> types = new TreeMap<>();
        for (InstanceView instance : instances.reachableActive(session, Permission.METRICS_QUERY)) {
            for (String type : reads.entityTypes(instance)) {
                types.putIfAbsent(type, new MetricsWire.EntityType(type, type));
            }
        }
        return ResponseEntity.ok(List.copyOf(types.values()));
    }

    /**
     * Row 55, TWO verbs: {@code metrics.query} AND {@code instance.view}. The reach classification carries one, so the
     * handler returns the intersection of what the caller reaches with each; a caller holding the first but not the second
     * sees none. An entity type nobody can see is an empty list, never a {@code 404}.
     */
    @GetMapping("/entity-types/{entityType}/instances")
    @ConsoleOperation("listMetricInstances")
    public ResponseEntity<MetricsWire.MetricInstanceList> listMetricInstances(@PathVariable String entityType,
            @RequestParam(value = "search", required = false) String search, Session session) {
        requireBounded(entityType, 128);
        if (search != null && search.length() > 128) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        String needle = search == null ? null : search.toLowerCase(Locale.ROOT);
        MonitoringInstances.PackageReads reads = instances.packages(session.organizationId());
        List<MetricsWire.MetricInstance> items = instances
                .reachableActive(session, Permission.METRICS_QUERY, Permission.INSTANCE_VIEW).stream()
                .filter(instance -> reads.entityTypes(instance).contains(entityType))
                .filter(instance -> needle == null || instance.name().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(InstanceView::name).thenComparing(InstanceView::instanceId))
                .map(instance -> new MetricsWire.MetricInstance(instance.instanceId(), instance.name(),
                        Math.max(1, instance.placements().size())))
                .toList();
        return ResponseEntity.ok(new MetricsWire.MetricInstanceList(items, items.size()));
    }

    /** Row 56: declared (control plane) composed with observed (data plane), concurrently; see {@link CatalogComposer}. */
    @GetMapping("/metrics/catalog")
    @ConsoleOperation("getMetricsCatalog")
    public ResponseEntity<List<MetricsWire.MetricCatalogEntry>> getMetricsCatalog(
            @RequestParam(value = "entityType", required = false) String entityType, Session session) {
        if (entityType != null) {
            requireBounded(entityType, 128);
        }
        return ResponseEntity.ok(catalog.catalog(session, entityType));
    }

    /**
     * Row 57. The v1 label allowlist is EMPTY (discovery default D-10, accepted by {@code OR-92} OQ-22), so every key
     * answers {@code []}: no label key has a value to list. The key and the entity type are still bounded, so an
     * oversized one is the contract's {@code 400} rather than a quiet {@code []}.
     */
    @GetMapping("/labels/{labelKey}/values")
    @ConsoleOperation("getLabelValues")
    public ResponseEntity<List<String>> getLabelValues(@PathVariable String labelKey,
            @RequestParam(value = "entityType", required = false) String entityType, Session session) {
        requireBounded(labelKey, 128);
        if (entityType != null) {
            requireBounded(entityType, 128);
        }
        return ResponseEntity.ok(List.of());
    }

    /**
     * Row 61: validate the shape (a malformed request is {@code 400}), then refuse. {@code metric.declare} at the caller's
     * own organization has passed in the interceptor ({@link SessionOrganizationResourceResolver}).
     */
    @PostMapping("/metrics/catalog/{name}/type")
    @ConsoleOperation(value = "declareMetricType", resource = SessionOrganizationResourceResolver.class)
    public ResponseEntity<Void> declareMetricType(@PathVariable String name, @RequestBody DeclareMetricTypeRequest body,
            Session session) {
        if (!MetricQueryRequest.METRIC_NAME.matcher(name).matches()) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        throw new MetricsRejectionException(ErrorCode.NOT_IMPLEMENTED, MetricsRejectionException.METRIC_DECLARE_DEFERRED,
                null);
    }

    private static void requireBounded(String value, int max) {
        if (value.isEmpty() || value.length() > max) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
    }
}
