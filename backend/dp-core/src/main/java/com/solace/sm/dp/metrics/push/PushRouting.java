package com.solace.sm.dp.metrics.push;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where an event goes, for ONE broker connection (plan 011 P11-5.1): for each distinct push metric, the instances whose
 * configured key it may name. Immutable, built by {@link PushSubscriptionPlan} and swapped in whole, so the receive thread
 * never sees a half-built table. Scoped to one organization and environment by construction: an event arriving on a
 * connection is looked up only here (I5).
 */
public final class PushRouting {

    /** An instance a key names, with its live placements (empty when it has none). */
    public record Target(UUID instanceId, List<UUID> livePlacements) {

        public Target {
            livePlacements = List.copyOf(livePlacements);
        }
    }

    /** One distinct metric and the instances it reads, by configured key value. */
    public record Route(PushMetric metric, Map<String, List<Target>> byKey) {

        public Route {
            byKey = Map.copyOf(byKey);
        }
    }

    public static final PushRouting EMPTY = new PushRouting(List.of());

    private final List<Route> routes;

    public PushRouting(List<Route> routes) {
        this.routes = List.copyOf(routes);
    }

    public List<Route> routes() {
        return routes;
    }

    /** The distinct patterns to subscribe: one per pattern, never one per instance (OR-107). */
    public Set<String> topicPatterns() {
        Set<String> patterns = new LinkedHashSet<>();
        routes.forEach(r -> patterns.add(r.metric().topic().value()));
        return patterns;
    }

    /** Every placement some route may attribute to: the Data Plane keeps a cumulative count only for these. */
    public Set<UUID> placements() {
        Set<UUID> placements = new LinkedHashSet<>();
        routes.forEach(r -> r.byKey().values().forEach(ts -> ts.forEach(t -> placements.addAll(t.livePlacements()))));
        return placements;
    }

    @Override
    public String toString() {
        return "PushRouting[" + routes.size() + " routes]";
    }
}
