package com.solace.sm.dp.metrics.query;

import com.solace.sm.dp.api.metrics.health.DiskAlarmView;
import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.query.CollectorHealthView;
import com.solace.sm.dp.api.metrics.query.MetricsQueryRefusedException;
import com.solace.sm.dp.api.metrics.query.MetricsQueryService;
import com.solace.sm.dp.api.metrics.query.MetricsQueryUnavailableException;
import com.solace.sm.dp.api.metrics.query.ObservedMetric;
import com.solace.sm.dp.api.metrics.query.QueryRefusal;
import com.solace.sm.dp.api.metrics.query.RangeQuery;
import com.solace.sm.dp.api.metrics.query.RangeResult;
import com.solace.sm.dp.api.metrics.query.RangeResult.MetricType;
import com.solace.sm.dp.api.metrics.query.RangeResult.Tier;
import com.solace.sm.dp.api.metrics.query.SeriesView;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.metrics.MetricsBusyException;
import com.solace.sm.dp.metrics.MetricsDbBudget;
import com.solace.sm.dp.metrics.collect.CollectionRows;
import com.solace.sm.dp.metrics.collect.RestartObservedGenerations;
import com.solace.sm.dp.metrics.query.Aggregator.Cell;
import com.solace.sm.dp.metrics.query.MetricsQueryRows.BucketRow;
import com.solace.sm.dp.metrics.query.MetricsQueryRows.Reader;
import com.solace.sm.dp.metrics.query.MetricsQueryRows.SeriesRow;
import com.solace.sm.dp.metrics.rollup.MetricsDiskAlarm;
import com.solace.sm.dp.metrics.rollup.PartitionRunway;
import com.solace.sm.dp.metrics.rollup.RollupProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * The metrics read side of the data plane (plan 011 P11-6.1; M-Q10, M-Q17, M-Q20, M-Q22, M-Q24).
 *
 * <p><b>Order of work.</b> Every refusal that needs no database is decided first. Then ONE permit of the shared
 * {@link MetricsDbBudget} is held for the whole read (a fifth concurrent query waits for it, M-Q22 (6)); the read is one
 * read-only transaction with {@code statement_timeout} (M-Q22 (1)). Inside it: the series, the refusals that need them
 * (a histogram, a gauge that cannot be aggregated, the series cap), the buckets of the served tier, and the lines.
 *
 * <p><b>What a line is.</b> Under {@code seriesBy: INSTANCE} (and no aggregation) an instance is one line, except a gauge
 * reported by more than one overlapping placement, which is one line PER placement (M-Q20: no cross-placement gauge
 * aggregation); counters always add the placements' rates. {@code seriesBy: REPLICA} is a line per placement. With an
 * aggregation across instances the result is one line, and a gauge whose placements overlap cannot be aggregated
 * ({@link QueryRefusal#AGGREGATION_NOT_APPLICABLE}). Placements whose lifetimes do not overlap are one continuous line (a
 * re-target, M-Q20).
 *
 * <p><b>I5.</b> The organization is the tenant and is bound by every statement ({@link MetricsQueryRows}); the instance
 * ids are the gateway's reach-filtered set and are only ever looked up under that tenant.
 */
public final class MetricsQueryServiceImpl implements MetricsQueryService {

    private final MetricsQueryRows rows;
    private final MetricsDbBudget budget;
    private final QueryProperties properties;
    private final TierRouter router;
    private final Clock clock;
    private final RestartObservedGenerations generations;
    private final CollectionRows collectionRows;
    private final PartitionRunway runway;
    private final MetricsDiskAlarm diskAlarm;

    public MetricsQueryServiceImpl(MetricsQueryRows rows, MetricsDbBudget budget, QueryProperties properties,
            RollupProperties rollupProperties, Clock clock, RestartObservedGenerations generations,
            CollectionRows collectionRows, PartitionRunway runway, MetricsDiskAlarm diskAlarm) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.router = new TierRouter(properties, Objects.requireNonNull(rollupProperties, "rollupProperties").lateWindow());
        this.clock = Objects.requireNonNull(clock, "clock");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.collectionRows = Objects.requireNonNull(collectionRows, "collectionRows");
        this.runway = Objects.requireNonNull(runway, "runway");
        this.diskAlarm = Objects.requireNonNull(diskAlarm, "diskAlarm");
    }

    // ---- range and instant ---------------------------------------------------------------------------------------

    @Override
    public RangeResult range(UUID organizationId, RangeQuery query) {
        return run(organizationId, query, false);
    }

    @Override
    public RangeResult instant(UUID organizationId, RangeQuery query) {
        return run(organizationId, query, true);
    }

    private RangeResult run(UUID organization, RangeQuery q, boolean instant) {
        Objects.requireNonNull(organization, "organizationId");
        validate(q, instant);
        Instant now = clock.instant();
        return guarded(reader -> compute(reader, organization, q, instant, now));
    }

    private <T> T guarded(Function<Reader, T> body) {
        try (MetricsDbBudget.Permit permit = budget.acquire(properties.budgetWait())) {
            return rows.read(properties.statementTimeout(), body);
        } catch (MetricsBusyException e) {
            throw new MetricsQueryUnavailableException(MetricsQueryUnavailableException.Reason.BUSY, e);
        } catch (MetricsQueryRows.StatementTimeout e) {
            throw new MetricsQueryUnavailableException(MetricsQueryUnavailableException.Reason.TIMEOUT, e);
        }
    }

    /** The refusals and malformed requests that need no database. */
    private void validate(RangeQuery q, boolean instant) {
        if (q.metric() == null || q.metric().isBlank()) {
            throw new IllegalArgumentException("a metric is required");
        }
        if (q.instanceIds().isEmpty()) {
            throw new IllegalArgumentException("at least one instance is required");
        }
        if (q.from() == null || q.to() == null || !q.from().isBefore(q.to())) {
            throw new IllegalArgumentException("the range must start before it ends");
        }
        if (q.resolution() == null || q.seriesBy() == null || q.acrossInstances() == null) {
            throw new IllegalArgumentException("resolution, seriesBy and acrossInstances are required");
        }
        if (!q.labels().isEmpty()) {
            throw new IllegalArgumentException("no label is allowed in v1 (the allowlist is empty)");
        }
        if (!q.groupBy().isEmpty()) {
            if (q.acrossInstances() == RangeQuery.Across.NONE) {
                throw new MetricsQueryRefusedException(QueryRefusal.GROUP_BY_REQUIRES_AGGREGATION);
            }
            throw new IllegalArgumentException("no groupBy key is allowed in v1 (the allowlist is empty)");
        }
        if (instant && q.acrossInstances() == RangeQuery.Across.NONE && new HashSet<>(q.instanceIds()).size() > 1) {
            throw new MetricsQueryRefusedException(QueryRefusal.STAT_REQUIRES_AGGREGATION);
        }
    }

    /** One placement of one instance for this metric: its series and its lifetime. */
    private record Placement(UUID instanceId, UUID placementId, String instanceName, List<Long> seriesIds,
            Instant createdAt, Instant retiredAt) {
    }

    private RangeResult compute(Reader reader, UUID org, RangeQuery q, boolean instant, Instant now) {
        String entityType = q.entityType() == null || q.entityType().isBlank() ? null : q.entityType();
        List<UUID> requested = new ArrayList<>(new LinkedHashSet<>(q.instanceIds()));
        List<SeriesRow> found = reader.series(org, q.metric(), entityType, requested, q.to());

        MetricType type;
        String unit;
        List<SeriesRow> series;
        if (found.isEmpty()) {
            Optional<MetricsQueryRows.Declared> declared = reader.declared(org, q.metric(), entityType, requested);
            if (declared.isPresent() && "histogram".equals(declared.get().type())) {
                throw new MetricsQueryRefusedException(QueryRefusal.AGGREGATION_NOT_APPLICABLE);
            }
            // W3-07: console.yaml says METRIC_UNTYPED has no v1 trigger (undeclared names are dropped and counted, M-Q6). A
            // metric that no requested instance declares or has a series for (a newer package version no longer declares it,
            // or every id is invisible) is an EMPTY, degraded result naming the authorized instances as not-collecting,
            // never an error: the chart degrades instead of blanking. It is drawn as a gauge with no unit, there being
            // nothing to type it by.
            type = declared.isPresent() && "counter".equals(declared.get().type()) ? MetricType.COUNTER : MetricType.GAUGE;
            unit = declared.map(MetricsQueryRows.Declared::unit).orElse("");
            series = List.of();
        } else {
            // A retype or a unit change mints a NEW series (S-H): the newest series decides what is drawn.
            SeriesRow newest = found.stream().max(Comparator.comparing(SeriesRow::seriesCreatedAt)
                    .thenComparingLong(SeriesRow::seriesId)).orElseThrow();
            type = newest.type();
            unit = newest.unit();
            series = found.stream().filter(s -> s.type() == type && s.unit().equals(unit)).toList();
        }

        // The instances that exist in this tenant: an id of another tenant is absent, exactly like one that never existed.
        Map<UUID, String> names = reader.instanceNames(org, requested);

        // Placements whose lifetime meets the range; a retired one stays queryable over what it has (S-K).
        Map<UUID, List<Placement>> byInstance = placements(series, q.from(), q.to());
        boolean overlapping = type == MetricType.GAUGE && byInstance.values().stream().anyMatch(this::overlap);
        boolean aggregated = q.acrossInstances() != RangeQuery.Across.NONE;

        if (aggregated && overlapping) {
            // M-Q20: no cross-placement gauge aggregation; there is no honest number to put on one line.
            throw new MetricsQueryRefusedException(QueryRefusal.AGGREGATION_NOT_APPLICABLE);
        }
        int projected = project(byInstance, type, q, aggregated);
        if (projected > properties.seriesCap()) {
            throw new MetricsQueryRefusedException(QueryRefusal.SERIES_CAP_EXCEEDED, properties.seriesCap(), projected,
                    q.seriesBy());
        }

        // ---- the served tier ----
        Duration range = Duration.between(q.from(), q.to());
        Tier tier = router.tier(range, q.resolution());
        List<SeriesRow> live = series.stream().filter(s -> byInstance.getOrDefault(s.instanceId(), List.of()).stream()
                .anyMatch(p -> p.placementId().equals(s.placementId()))).toList();
        int maxInterval = live.stream().map(SeriesRow::intervalSeconds).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).max().orElse(0);
        int bucket = router.bucketSeconds(tier, range, maxInterval);
        Instant servedFrom = router.servedFrom(tier, q.from(), now);
        boolean truncated = servedFrom.isAfter(q.from());
        Instant provisional = router.provisionalFrom(tier, now);
        // W3-08: a widened rollup bin is whole. The stitched raw tail starts at a bin edge, not mid-bin, so a bin is built from
        // rollup rows or from raw, never from a mix that would count its expected samples twice.
        boolean widened = tier != Tier.RAW && bucket != (tier == Tier.HOURLY ? 3600 : 86400);
        if (widened && provisional != null) {
            provisional = router.bin(provisional, bucket);
        }

        List<Long> ids = live.stream().map(SeriesRow::seriesId).toList();
        List<BucketRow> bucketRows = new ArrayList<>();
        Instant provisionalFrom = null;
        if (!servedFrom.isBefore(q.to())) {
            // everything asked for is older than retention: nothing to read
        } else if (tier == Tier.RAW) {
            bucketRows.addAll(reader.raw(org, type, ids, servedFrom, q.to(), bucket));
        } else {
            Instant binFrom = widened ? router.bin(servedFrom, bucket) : router.floor(tier, servedFrom);
            boolean stitched = provisional != null && provisional.isBefore(q.to());
            Instant rollupTo = stitched ? provisional : q.to();
            if (binFrom.isBefore(rollupTo)) {
                bucketRows.addAll(reader.rollup(org, tier == Tier.DAILY, ids, binFrom, rollupTo));
            }
            if (stitched) {
                provisionalFrom = binFrom.isAfter(provisional) ? binFrom : provisional;
                bucketRows.addAll(reader.raw(org, type, ids, provisionalFrom, q.to(), bucket));
            }
        }

        if (widened) {
            bucketRows = new ArrayList<>(Aggregator.fold(type, bucketRows, bucket));
        }

        // ---- the lines ----
        Map<Long, List<BucketRow>> rowsBySeries = new HashMap<>();
        for (BucketRow r : bucketRows) {
            rowsBySeries.computeIfAbsent(r.seriesId(), k -> new ArrayList<>()).add(r);
        }
        Set<UUID> instancesWithData = new HashSet<>();
        List<Line> lines = new ArrayList<>();
        Map<UUID, TreeMap<Instant, Cell>> instanceCells = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<Placement>> entry : byInstance.entrySet()) {
            UUID instanceId = entry.getKey();
            List<Placement> placements = entry.getValue();
            int liveCount = (int) placements.stream().filter(p -> p.retiredAt() == null).count();
            Map<Placement, TreeMap<Instant, Cell>> perPlacement = new LinkedHashMap<>();
            for (Placement p : placements) {
                List<TreeMap<Instant, Cell>> ofSeries = new ArrayList<>();
                for (long id : p.seriesIds()) {
                    ofSeries.add(Aggregator.cells(type, rowsBySeries.getOrDefault(id, List.of()), properties.rateSeconds()));
                }
                perPlacement.put(p, Aggregator.combinePlacements(type, ofSeries));
            }
            // W3-11: placements that ran one after another are one line; placements that ran at once add (M-Q20)
            List<Integer> lanes = lanes(placements);
            List<TreeMap<Instant, Cell>> perPlacementCells = new ArrayList<>();
            for (Placement p : placements) {
                perPlacementCells.add(perPlacement.get(p));
            }
            TreeMap<Instant, Cell> whole = Aggregator.combinePlacements(type, perPlacementCells, lanes, properties.rateSeconds());
            if (!whole.isEmpty()) {
                instancesWithData.add(instanceId);
            }
            if (aggregated) {
                instanceCells.put(instanceId, whole);
            } else if (q.seriesBy() == RangeQuery.SeriesBy.REPLICA || (type == MetricType.GAUGE && overlap(placements))) {
                perPlacement.forEach((p, cells) -> lines.add(new Line(
                        new SeriesView.SeriesRef(instanceId, p.placementId(), p.placementId().toString()),
                        p.instanceName() + " · " + p.placementId().toString().substring(0, 8), liveCount, cells)));
            } else {
                String name = placements.get(0).instanceName();
                lines.add(new Line(new SeriesView.SeriesRef(instanceId, null, null), name, liveCount, whole));
            }
        }
        if (aggregated) {
            TreeMap<Instant, Cell> across = Aggregator.acrossInstances(type, q.acrossInstances(),
                    instanceCells.values().stream().filter(c -> !c.isEmpty()).toList());
            int contributors = (int) instanceCells.values().stream().filter(c -> !c.isEmpty()).count();
            int liveTotal = byInstance.values().stream().mapToInt(ps -> (int) ps.stream().filter(p -> p.retiredAt() == null).count()).sum();
            lines.add(new Line(new SeriesView.SeriesRef(null, null, null),
                    q.acrossInstances().name().toLowerCase(Locale.ROOT) + " of " + contributors
                            + (contributors == 1 ? " instance" : " instances"), liveTotal, across));
        }

        long rawThreshold = Math.max(2L * maxInterval, bucket);
        if (maxInterval == 0) {
            rawThreshold = 2L * bucket;
        }
        long threshold = tier == Tier.RAW ? rawThreshold : 2L * bucket;
        List<SeriesView> views = new ArrayList<>();
        for (Line line : lines) {
            List<SeriesView.Point> points = Aggregator.points(line.cells());
            if (points.isEmpty()) {
                continue;
            }
            List<SeriesView.Gap> gaps = tier == Tier.RAW ? GapDeriver.raw(points, bucket, threshold)
                    : GapDeriver.rollup(points, bucket, provisionalFrom, Aggregator.gappedBuckets(line.cells()));
            if (instant) {
                points = List.of(points.get(points.size() - 1));
                gaps = List.of();
            }
            views.add(new SeriesView(line.ref(), line.label(), line.replicas(), 0, SeriesView.State.OK, points, gaps));
        }

        RangeResult.Partial partial = partial(reader, org, names, instancesWithData, now);
        RangeResult.Render render = type == MetricType.COUNTER
                ? new RangeResult.Render(RangeResult.Transform.RATE, properties.rateSeconds())
                : new RangeResult.Render(RangeResult.Transform.NONE, null);
        return new RangeResult(new RangeResult.Metric(q.metric(), type, unit), render,
                new RangeResult.Served(q.resolution(), tier, bucket),
                new RangeResult.Coverage(q.from(), servedFrom, truncated, provisionalFrom), (int) Math.min(Integer.MAX_VALUE, threshold),
                views, partial);
    }

    private record Line(SeriesView.SeriesRef ref, String label, int replicas, TreeMap<Instant, Cell> cells) {
    }

    private Map<UUID, List<Placement>> placements(List<SeriesRow> series, Instant from, Instant to) {
        Map<UUID, Map<UUID, Placement>> byInstance = new LinkedHashMap<>();
        for (SeriesRow s : series) {
            boolean meetsRange = s.placementCreatedAt().isBefore(to)
                    && (s.placementRetiredAt() == null || s.placementRetiredAt().isAfter(from));
            if (!meetsRange) {
                continue;
            }
            Map<UUID, Placement> placements = byInstance.computeIfAbsent(s.instanceId(), k -> new LinkedHashMap<>());
            Placement existing = placements.get(s.placementId());
            if (existing == null) {
                placements.put(s.placementId(), new Placement(s.instanceId(), s.placementId(), s.instanceName(),
                        new ArrayList<>(List.of(s.seriesId())), s.placementCreatedAt(), s.placementRetiredAt()));
            } else {
                existing.seriesIds().add(s.seriesId());
            }
        }
        Map<UUID, List<Placement>> out = new LinkedHashMap<>();
        byInstance.forEach((instance, placements) -> out.put(instance, new ArrayList<>(placements.values())));
        return out;
    }

    /**
     * The lane of each placement (index-aligned): a placement joins the first lane none of whose members it overlaps, so
     * lanes are the instance's concurrent replicas and the placements within one ran in sequence. Placements are taken in
     * the order of their creation, which is what makes this greedy colouring the minimum number of lanes.
     */
    private List<Integer> lanes(List<Placement> placements) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < placements.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing((Integer i) -> placements.get(i).createdAt()));
        int[] lane = new int[placements.size()];
        List<List<Placement>> members = new ArrayList<>();
        for (int i : order) {
            Placement p = placements.get(i);
            int chosen = -1;
            for (int l = 0; l < members.size() && chosen < 0; l++) {
                boolean clear = true;
                for (Placement other : members.get(l)) {
                    if (overlap(List.of(p, other))) {
                        clear = false;
                        break;
                    }
                }
                if (clear) {
                    chosen = l;
                }
            }
            if (chosen < 0) {
                members.add(new ArrayList<>());
                chosen = members.size() - 1;
            }
            members.get(chosen).add(p);
            lane[i] = chosen;
        }
        List<Integer> out = new ArrayList<>();
        for (int l : lane) {
            out.add(l);
        }
        return out;
    }

    /** Two placements overlap when they share MORE than the grace of the lifetimes they have in common (M-Q20). */
    private boolean overlap(List<Placement> placements) {
        for (int i = 0; i < placements.size(); i++) {
            for (int j = i + 1; j < placements.size(); j++) {
                Placement a = placements.get(i);
                Placement b = placements.get(j);
                Instant start = a.createdAt().isAfter(b.createdAt()) ? a.createdAt() : b.createdAt();
                Instant end = end(a).isBefore(end(b)) ? end(a) : end(b);
                if (Duration.between(start, end).compareTo(properties.placementOverlapGrace()) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Instant end(Placement p) {
        return p.retiredAt() == null ? Instant.MAX : p.retiredAt();
    }

    /** The rendered series this request would produce: one per line (DM-D7 counts lines, not instances). */
    private int project(Map<UUID, List<Placement>> byInstance, MetricType type, RangeQuery q, boolean aggregated) {
        if (aggregated) {
            return byInstance.isEmpty() ? 0 : 1;
        }
        int total = 0;
        for (List<Placement> placements : byInstance.values()) {
            if (placements.isEmpty()) {
                continue;
            }
            boolean perPlacement = q.seriesBy() == RangeQuery.SeriesBy.REPLICA || (type == MetricType.GAUGE && overlap(placements));
            total += perPlacement ? placements.size() : 1;
        }
        return total;
    }

    private RangeResult.Partial partial(Reader reader, UUID org, Map<UUID, String> names, Set<UUID> withData, Instant now) {
        List<UUID> missing = names.keySet().stream().filter(id -> !withData.contains(id)).toList();
        if (missing.isEmpty()) {
            return new RangeResult.Partial(false, List.of());
        }
        Set<UUID> failing = reader.instancesWithCollectorFailures(org, missing, sinceDay(now));
        List<RangeResult.Unavailable> unavailable = new ArrayList<>();
        for (UUID id : missing) {
            unavailable.add(new RangeResult.Unavailable(id, names.get(id), failing.contains(id)
                    ? RangeResult.Reason.COLLECTOR_DEGRADED : RangeResult.Reason.NOT_COLLECTING));
        }
        return new RangeResult.Partial(true, unavailable);
    }

    /** The counter is per UTC day, so "the last 24 hours" is every day that overlaps them. */
    private static LocalDate sinceDay(Instant now) {
        return now.minus(Duration.ofHours(24)).atZone(ZoneOffset.UTC).toLocalDate();
    }

    // ---- the catalog's data-plane half, the active list, the collector's health -------------------------------------

    @Override
    public List<ObservedMetric> observed(UUID organizationId, String entityType, Set<UUID> instanceIds) {
        Objects.requireNonNull(organizationId, "organizationId");
        if (instanceIds == null || instanceIds.isEmpty()) {
            return List.of();
        }
        String type = entityType == null || entityType.isBlank() ? null : entityType;
        Instant now = clock.instant();
        return guarded(reader -> {
            List<MetricsQueryRows.ObservedRow> observed = reader.observed(organizationId, type, instanceIds);
            Set<UUID> placements = new HashSet<>();
            observed.forEach(o -> placements.add(o.placementId()));
            Map<UUID, Map<CollectorReason, Long>> counted = reader.reasonsByPlacement(organizationId, placements, sinceDay(now));
            Map<String, List<MetricsQueryRows.ObservedRow>> byName = new LinkedHashMap<>();
            observed.forEach(o -> byName.computeIfAbsent(o.metricName(), k -> new ArrayList<>()).add(o));
            List<ObservedMetric> out = new ArrayList<>();
            byName.forEach((name, rowsOfName) -> {
                MetricsQueryRows.ObservedRow newest = rowsOfName.stream()
                        .max(Comparator.comparing(MetricsQueryRows.ObservedRow::seriesCreatedAt)).orElseThrow();
                Instant lastSeen = rowsOfName.stream().map(MetricsQueryRows.ObservedRow::lastTs).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null);
                Map<CollectorReason, Long> sum = new EnumMap<>(CollectorReason.class);
                for (UUID placement : rowsOfName.stream().map(MetricsQueryRows.ObservedRow::placementId).collect(java.util.stream.Collectors.toSet())) {
                    counted.getOrDefault(placement, Map.of()).forEach((reason, n) -> {
                        if (reason != CollectorReason.UNDECLARED_DROPPED) {
                            sum.merge(reason, n, Long::sum);
                        }
                    });
                }
                out.add(new ObservedMetric(name, newest.type(), newest.unit(), lastSeen, reasonList(sum)));
            });
            return out;
        });
    }

    private static List<CollectorHealthView.ReasonCount> reasonList(Map<CollectorReason, Long> counts) {
        List<CollectorHealthView.ReasonCount> out = new ArrayList<>();
        for (CollectorReason reason : CollectorReason.values()) {
            long n = counts.getOrDefault(reason, 0L);
            if (n > 0) {
                out.add(new CollectorHealthView.ReasonCount(reason, n));
            }
        }
        return out;
    }

    @Override
    public List<String> activeMetrics(UUID organizationId, UUID instanceId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(instanceId, "instanceId");
        try (MetricsDbBudget.Permit permit = budget.acquire(properties.budgetWait())) {
            long generation = generations.latestRestartObservedGeneration(new TenantId(organizationId), new InstanceId(instanceId))
                    .orElse(0L);
            return collectionRows.activeMetrics(organizationId, instanceId, generation).orElse(List.of());
        } catch (MetricsBusyException e) {
            throw new MetricsQueryUnavailableException(MetricsQueryUnavailableException.Reason.BUSY, e);
        }
    }

    @Override
    public CollectorHealthView collectorHealth(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Instant now = clock.instant();
        DiskAlarmView disk = diskAlarm.view().orElse(null);
        return guarded(reader -> {
            Map<CollectorReason, Long> reasons = reader.reasons(organizationId, sinceDay(now));
            int unfrozen = reader.unfrozenClosedPartitions(now);
            boolean degraded = runway.runwayShort() || (disk != null && disk.state() == DiskAlarmView.State.CRITICAL);
            return new CollectorHealthView(degraded ? CollectorHealthView.State.DEGRADED : CollectorHealthView.State.LIVE,
                    runway.runwayEnd().orElse(null), unfrozen, reasonList(reasons), disk);
        });
    }
}
