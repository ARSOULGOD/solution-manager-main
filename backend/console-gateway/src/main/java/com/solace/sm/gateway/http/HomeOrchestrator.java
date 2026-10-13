package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.catalog.CatalogEntry;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.health.HealthState;
import com.solace.sm.dp.api.home.AttentionCandidate;
import com.solace.sm.dp.api.home.AttentionReason;
import com.solace.sm.dp.api.home.AttentionService;
import com.solace.sm.dp.api.home.InstanceHealthSummary;
import com.solace.sm.dp.api.home.InventorySummaryService;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code getHomeSummary} and {@code getHomeAttention} (plan 010 T10.5, {@code OR-45}), composed here because the
 * gateway is the one module permitted both {@code -api}s ({@code I7}): the catalog is the control plane's, the
 * instances and their health the data plane's, and neither plane can see what the caller may.
 *
 * <p><b>Reach, not a resource.</b> Both operations are {@code x-sm-scope: reach}: the interceptor has required the
 * operation's own verb at one or more grants, which authorizes NO item. Every instance is then filtered through
 * {@link ReachAuthorization#authorizedSubset} at its own scope, so the summary never counts an instance the caller
 * cannot see, and an operator bound to one environment gets that environment's counts and not a {@code 403}.
 * The filters ({@code OR-61}): {@code instanceCount} by {@code instance.view}; {@code byHealth} by {@code health.view}
 * as well; an attention item by {@code instance.view} and the verb guarding its source ({@code health} by
 * {@code health.view}, {@code apply-indeterminate} and {@code config-diverged} by {@code config.view}).
 *
 * <p><b>Two planes, read together, degrading apart ({@code OR-10}).</b> The summary reads the catalog and the data
 * plane concurrently, and a data-plane failure degrades the counts - declared in {@code availability}, never an empty
 * list passed off as live, never an exception - while the catalog rows still come back. Only the failure's class is
 * logged: a driver message can carry a host, a role or a path.
 *
 * <p>These are pure reads of persisted rows: unlike {@code listInstances} they do not run the declare sweep, so a
 * registered-but-still-pending instance appears here once the sweep (or the declare itself) activates it.
 *
 * <p>{@code I5}: the organization is {@code Session.organizationId()} on every call on both planes.
 */
@Component
public class HomeOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(HomeOrchestrator.class);

    /** The sub-states and keys, in the order the contract lists them. */
    private static final List<HealthState> BY_HEALTH_ORDER = List.of(HealthState.values());

    private final SolutionCatalog catalog;
    private final InventorySummaryService summaries;
    private final AttentionService attention;
    private final ReachAuthorization reach;
    private final ThreadPoolExecutor dataPlaneReads;

    public HomeOrchestrator(SolutionCatalog catalog, InventorySummaryService summaries, AttentionService attention,
            ReachAuthorization reach) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.attention = Objects.requireNonNull(attention, "attention");
        this.reach = Objects.requireNonNull(reach, "reach");
        // Bounded, and when full the request thread runs the read itself: the planes then serialize, which is slower
        // and never a refusal.
        this.dataPlaneReads = new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "sm-home-dp-read");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    void shutdown() {
        dataPlaneReads.shutdownNow();
    }

    // ---- getHomeSummary ------------------------------------------------------------------------------------

    public HomeSummary summary(Session session) {
        UUID organization = session.organizationId();
        PrincipalRef principal = session.principal();

        // The data-plane read starts first and runs beside the catalog read on this thread.
        CompletableFuture<List<InstanceHealthSummary>> dataPlane = CompletableFuture.supplyAsync(
                () -> summaries.summarize(organization), dataPlaneReads);
        List<CatalogEntry> entries = catalog.list(organization);

        List<InstanceHealthSummary> instances;
        try {
            instances = dataPlane.join();
        } catch (CompletionException failed) {
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            log.warn("home summary: the data plane could not be read ({})", failed.getCause().getClass().getSimpleName());
            return new HomeSummary(new Availability(AvailabilityState.DEGRADED, null,
                    "the data plane could not be read, so instance counts and health are unavailable", null),
                    entries.stream().map(e -> new HomeSummary.SolutionRow(e.solutionId(), e.name(), 0, null,
                            e.stateVersion())).toList());
        }

        Set<UUID> viewable = subset(principal, Permission.INSTANCE_VIEW,
                instances.stream().map(InstanceHealthSummary::instanceId).toList());
        // health.view is asked only about instances the caller can already see: byHealth is a subset of instanceCount.
        Set<UUID> healthViewable = subset(principal, Permission.HEALTH_VIEW, viewable);

        Map<UUID, Integer> counts = new LinkedHashMap<>();
        Map<UUID, Map<HealthState, Integer>> buckets = new LinkedHashMap<>();
        int unpolled = 0;
        for (InstanceHealthSummary instance : instances) {
            if (!viewable.contains(instance.instanceId())) {
                continue;
            }
            counts.merge(instance.catalogSolutionId(), 1, Integer::sum);
            if (!healthViewable.contains(instance.instanceId())) {
                continue;
            }
            if (instance.rollup().isEmpty()) {
                unpolled++;
                continue;
            }
            buckets.computeIfAbsent(instance.catalogSolutionId(), k -> new LinkedHashMap<>())
                    .merge(instance.rollup().get(), 1, Integer::sum);
        }

        List<HomeSummary.SolutionRow> rows = new ArrayList<>();
        for (CatalogEntry entry : entries) {
            rows.add(new HomeSummary.SolutionRow(entry.solutionId(), entry.name(),
                    counts.getOrDefault(entry.solutionId(), 0), byHealth(buckets.get(entry.solutionId())),
                    entry.stateVersion()));
        }
        Availability availability = unpolled == 0 ? null
                : new Availability(AvailabilityState.DEGRADED, AvailabilityMode.POLLING,
                        unpolled + " instance(s) have not been polled yet, so they are in no health bucket", null);
        return new HomeSummary(availability, rows);
    }

    /** The nine-key vocabulary spelt as the contract spells it; absent (never zeroed) when nothing qualifies. */
    private static Map<String, Integer> byHealth(Map<HealthState, Integer> bucket) {
        if (bucket == null || bucket.isEmpty()) {
            return null;
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (HealthState state : BY_HEALTH_ORDER) {
            Integer count = bucket.get(state);
            if (count != null) {
                out.put(state.wire(), count);
            }
        }
        return out;
    }

    // ---- getHomeAttention ----------------------------------------------------------------------------------

    public HomeAttention attention(Session session) {
        UUID organization = session.organizationId();
        PrincipalRef principal = session.principal();

        List<AttentionCandidate> candidates;
        try {
            candidates = attention.attention(organization);
        } catch (RuntimeException failed) {
            log.warn("home attention: the data plane could not be read ({})", failed.getClass().getSimpleName());
            return new HomeAttention(new Availability(AvailabilityState.UNAVAILABLE, null,
                    "the data plane could not be read, so nothing can be said about what needs attention", null),
                    List.of());
        }

        Set<UUID> viewable = subset(principal, Permission.INSTANCE_VIEW,
                candidates.stream().map(AttentionCandidate::instanceId).distinct().toList());
        // Each reason is filtered by the verb that guards its SOURCE (OR-61, Q-C2), over what the caller can view.
        Set<UUID> healthViewable = subset(principal, Permission.HEALTH_VIEW,
                idsOf(candidates, viewable, AttentionReason.HEALTH));
        Set<UUID> configViewable = subset(principal, Permission.CONFIG_VIEW, idsOf(candidates, viewable,
                AttentionReason.APPLY_INDETERMINATE, AttentionReason.CONFIG_DIVERGED));

        List<HomeAttention.Item> items = candidates.stream()
                .filter(c -> viewable.contains(c.instanceId()))
                .filter(c -> (c.reason() == AttentionReason.HEALTH ? healthViewable : configViewable)
                        .contains(c.instanceId()))
                .sorted(Comparator.comparing((AttentionCandidate c) -> c.reason().ordinal())
                        .thenComparing(AttentionCandidate::instanceId)
                        .thenComparing(c -> c.placementId().orElse(new UUID(0L, 0L))))
                .map(c -> new HomeAttention.Item(c.instanceId(), c.placementId().orElse(null), c.reason().wire(),
                        c.state().map(HealthState::wire).orElse(null), c.stateVersion()))
                .toList();
        return new HomeAttention(null, items);
    }

    private static Set<UUID> idsOf(List<AttentionCandidate> candidates, Set<UUID> viewable,
            AttentionReason... reasons) {
        Set<AttentionReason> wanted = Set.of(reasons);
        return candidates.stream().filter(c -> wanted.contains(c.reason()))
                .map(AttentionCandidate::instanceId).filter(viewable::contains)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** {@code authorizedSubset} over instances; an empty question is not sent. */
    private Set<UUID> subset(PrincipalRef principal, Permission permission, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return reach.authorizedSubset(principal, permission, ResourceKind.INSTANCE, ids);
    }
}
