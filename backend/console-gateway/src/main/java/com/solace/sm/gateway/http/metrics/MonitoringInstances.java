package com.solace.sm.gateway.http.metrics;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.catalog.CatalogEntry;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.dashboard.DefaultLayerInput;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.InstanceInventoryService;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.gateway.http.InstanceController;
import com.solace.sm.gateway.http.MetricsDeclarationBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * What every monitoring route needs to know about the caller's instances, composed here because the gateway is the one
 * module permitted both {@code -api}s ({@code I7}): which instances exist is the data plane's, what a version declares is
 * the control plane's, and neither can see what the caller may.
 *
 * <p><b>Reach, not a resource (M-Q17, {@code OR-79}).</b> {@link #reachable} filters ids through
 * {@link ReachAuthorization#authorizedSubset} at each instance's OWN scope. An id the caller cannot see is simply absent,
 * exactly like one that does not exist; nothing here ever says an id was refused.
 *
 * <p>{@code I5}: the organization is {@code Session.organizationId()} on every call; never a request field.
 */
@Component
public class MonitoringInstances {

    private final SolutionCatalog catalog;
    private final InstanceInventoryService inventory;
    private final ReachAuthorization reach;
    private final DesignerCodec codec;

    public MonitoringInstances(SolutionCatalog catalog, InstanceInventoryService inventory, ReachAuthorization reach,
            DesignerCodec codec) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.reach = Objects.requireNonNull(reach, "reach");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** Every ACTIVE instance of the organization, across its catalog entries. */
    public List<InstanceView> active(UUID organization) {
        List<InstanceView> out = new ArrayList<>();
        for (CatalogEntry entry : catalog.list(organization)) {
            out.addAll(inventory.listActive(organization, entry.solutionId()));
        }
        return out;
    }

    /**
     * The active instances the caller holds EVERY one of {@code verbs} at, each checked at the instance's own scope. Row 55
     * passes {@code metrics.query} and {@code instance.view}: the intersection, in the data plane's order.
     */
    public List<InstanceView> reachableActive(Session session, Permission... verbs) {
        List<InstanceView> all = active(session.organizationId());
        if (all.isEmpty()) {
            return all;
        }
        List<UUID> ids = all.stream().map(InstanceView::instanceId).toList();
        Set<UUID> allowed = new LinkedHashSet<>(ids);
        for (Permission verb : verbs) {
            allowed.retainAll(reachable(session, verb, ids));
        }
        return all.stream().filter(view -> allowed.contains(view.instanceId())).toList();
    }

    /** The members of {@code ids} the caller holds {@code verb} at, silently: absent is never "refused". */
    public Set<UUID> reachable(Session session, Permission verb, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return reach.authorizedSubset(session.principal(), verb, ResourceKind.INSTANCE, ids);
    }

    /** A per-request memo of the package versions read: one read per distinct (catalog entry, version). */
    public PackageReads packages(UUID organization) {
        return new PackageReads(organization);
    }

    /** Reads what package versions declare. Thread-safe: the catalog composition reads it from two threads. */
    public final class PackageReads {

        private final UUID organization;
        private final Map<String, Optional<PackageModel.SolutionPackage>> packages = new ConcurrentHashMap<>();

        private PackageReads(UUID organization) {
            this.organization = organization;
        }

        /** The metrics the instance's package version declares, per component that applies to that version, in declared order. */
        public List<DeclaredMetric> declared(InstanceView instance) {
            Object version = InstanceController.packageVersionOnTheWire(instance.declaredPackageVersion());
            return packageOf(instance).map(pkg -> declaredBy(pkg, version)).orElse(List.of());
        }

        /** The component types of the instance's version that declare at least one metric: its entity types. */
        public List<String> entityTypes(InstanceView instance) {
            return declared(instance).stream().map(DeclaredMetric::componentType).distinct().toList();
        }

        /**
         * What a dashboard needs to derive its default layer and to validate a widget (plan 011 P11-7.1's
         * {@link DefaultLayerInput}): the instance's entity type and the metrics it declares, from the control plane's own
         * package document; {@code activeMetrics} from the data plane, passed in.
         *
         * <p>⚠️ <b>One entity type per dashboard.</b> {@code DefaultLayerInput} and a widget's selector carry one. When a
         * package version declares metrics on more than one component, the dashboard is of the FIRST such component in
         * declared order and the others' metrics get no default widget and cannot be saved to it. No package in the
         * repository declares metrics on two components today (the Cache has one, the File Mesh fixture one); the day one
         * does, the contract's one-selector dashboard needs a ruling.
         */
        public DefaultLayerInput dashboardInput(InstanceView instance, Collection<String> activeMetrics) {
            List<DeclaredMetric> declared = declared(instance);
            Object version = InstanceController.packageVersionOnTheWire(instance.declaredPackageVersion());
            String entityType = declared.isEmpty()
                    ? packageOf(instance).flatMap(pkg -> MetricsDeclarationBuilder.componentsIn(pkg, version).stream()
                            .map(PackageModel.PackageComponent::type).findFirst()).orElse("instance")
                    : declared.get(0).componentType();
            List<DefaultLayerInput.DeclaredMetric> metrics = declared.stream()
                    .filter(metric -> metric.componentType().equals(entityType))
                    .map(metric -> new DefaultLayerInput.DeclaredMetric(metric.name(), metric.metric().metricType(),
                            metric.metric().unit(), metric.defaultChart()))
                    .toList();
            return new DefaultLayerInput(entityType, metrics, Set.copyOf(activeMetrics));
        }

        private Optional<PackageModel.SolutionPackage> packageOf(InstanceView instance) {
            return packages.computeIfAbsent(instance.catalogSolutionId() + "@" + instance.declaredPackageVersion(), key ->
                    catalog.documentOf(organization, instance.catalogSolutionId(), instance.declaredPackageVersion())
                            .flatMap(document -> {
                                try {
                                    return Optional.of(codec.readPackage(document));
                                } catch (IllegalArgumentException unreadable) {
                                    // the message would quote the document (I6); an unreadable package declares nothing here
                                    return Optional.empty();
                                }
                            }));
        }

        private static List<DeclaredMetric> declaredBy(PackageModel.SolutionPackage pkg, Object version) {
            List<DeclaredMetric> out = new ArrayList<>();
            for (PackageModel.PackageComponent component : MetricsDeclarationBuilder.componentsIn(pkg, version)) {
                if (component.metrics() == null) {
                    continue;
                }
                for (PackageModel.PackageMetric metric : component.metrics()) {
                    out.add(new DeclaredMetric(component.type(), metric, component.restartSignal() != null));
                }
            }
            return out;
        }
    }
}
