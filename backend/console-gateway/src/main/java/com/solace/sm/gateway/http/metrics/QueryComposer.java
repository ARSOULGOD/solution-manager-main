package com.solace.sm.gateway.http.metrics;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.metrics.query.MetricsQueryService;
import com.solace.sm.dp.api.metrics.query.RangeQuery;
import com.solace.sm.dp.api.metrics.query.RangeResult;
import com.solace.sm.dp.api.metrics.query.SeriesView;
import com.solace.sm.gateway.http.ErrorCode;
import com.solace.sm.gateway.http.GatewayRejectionException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code queryRange} and {@code queryInstant} (rows 58-59): the rate gate, the reach filter, the data plane's query, and the
 * contract's wire shape.
 *
 * <h2>Reach, not refusal (M-Q17, {@code OR-79})</h2>
 * The interceptor has required {@code metrics.query} at one or more grants. Here the requested {@code instanceIds} are
 * <b>filtered</b> by {@link ReachAuthorization#authorizedSubset} at each instance's own scope, SILENTLY, exactly like ids
 * that do not exist. A query is never refused because one id is out of reach (DM §5.8's "unauthorized" branch is
 * superseded), and the response never says an id was dropped: {@code partial.unavailable[]} is built by the data plane
 * from the ids it was handed, so it can only name instances the caller may see.
 *
 * <p>{@code resolution: raw} on a RANGE additionally needs {@code metrics.forceRaw}: held somewhere in the organization
 * (else {@code 403}), and the id set is then intersected with where it is held. On an INSTANT query {@code raw} is
 * accepted and ignored (W4-01): row 59 carries one verb, so the data plane is always asked for {@code AUTO}. Passing
 * {@code RAW} through would pick a different tier, a different cost and a different point, which is exactly the
 * scan {@code metrics.forceRaw} exists to gate.
 *
 * <p>When the filter leaves nothing, the data plane is asked about ONE fresh random id instead of being handed an empty
 * list (which it refuses as malformed): whatever the data plane answers for an instance that does not exist is then,
 * by construction, what the caller gets for an id it may not see. The two cannot differ, so the answer cannot be an
 * existence oracle.
 *
 * <p>⚠️ <b>What v1 does not apply.</b> {@code aggregation.acrossReplicas} is validated and not passed on: the data
 * plane has no such switch (a counter sums across placements, M-Q20, and {@code seriesBy: replica} is the per-placement
 * line). And {@code replicaComposition.inactive} is {@code 0} and a series' {@code state} is {@code ok}: an inactive
 * replica is the health plane's fact, and composing it into a series is not in P11-7.2's test list.
 * An across-instances aggregate has no instance: its {@code seriesRef.instanceId} is null, written as {@code null} like
 * {@code placementId} and {@code sourceKey} (the contract makes it required and nullable, OR-112 (6), W4-06); the data
 * plane supplies the all-null reference and the gateway passes it through.
 */
@Component
public class QueryComposer {

    private final MonitoringInstances instances;
    private final ReachAuthorization reach;
    private final MetricsQueryService query;

    public QueryComposer(MonitoringInstances instances, ReachAuthorization reach, MetricsQueryService query) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.reach = Objects.requireNonNull(reach, "reach");
        this.query = Objects.requireNonNull(query, "query");
    }

    public MetricsWire.QueryResult range(Session session, MetricQueryRequest request) {
        return run(session, request, false);
    }

    public MetricsWire.QueryResult instant(Session session, MetricQueryRequest request) {
        return run(session, request, true);
    }

    private MetricsWire.QueryResult run(Session session, MetricQueryRequest request, boolean instant) {
        boolean forceRaw = request.raw() && !instant;
        if (forceRaw) {
            reach.requireHeldAnywhere(session.principal(), Permission.METRICS_FORCE_RAW);
        }
        List<UUID> requested = request.selector().instanceIds();
        Set<UUID> allowed = new LinkedHashSet<>(instances.reachable(session, Permission.METRICS_QUERY, requested));
        if (forceRaw) {
            allowed.retainAll(instances.reachable(session, Permission.METRICS_FORCE_RAW, requested));
        }
        List<UUID> ids = new ArrayList<>();
        for (UUID id : requested) {
            if (allowed.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            ids.add(UUID.randomUUID());
        }
        // W4-01: only a range that has passed the forceRaw gate may ask for the raw tier. An instant carries the one
        // metrics.query verb, so its resolution is AUTO whatever the body says: the tier, the cost and the point differ.
        RangeQuery.Resolution resolution = forceRaw ? RangeQuery.Resolution.RAW : RangeQuery.Resolution.AUTO;
        RangeQuery dataPlane = new RangeQuery(request.metric(), request.selector().entityType(), ids, Map.of(),
                request.range().fromInstant(), request.range().toInstant(), resolution,
                RangeQuery.SeriesBy.valueOf(request.seriesBy().toUpperCase(Locale.ROOT)),
                RangeQuery.Across.valueOf(request.aggregation().acrossInstances().toUpperCase(Locale.ROOT)),
                List.of());
        RangeResult result;
        try {
            result = instant ? query.instant(session.organizationId(), dataPlane)
                    : query.range(session.organizationId(), dataPlane);
        } catch (IllegalArgumentException malformed) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        return wire(result);
    }

    // ---- the wire ------------------------------------------------------------------------------------------------

    static MetricsWire.QueryResult wire(RangeResult result) {
        RangeResult.Metric metric = result.metric();
        return new MetricsWire.QueryResult(1,
                new MetricsWire.ResultMetric(metric.name(), lower(metric.metricType()), metric.unit()),
                new MetricsWire.Render(lower(result.render().transform()), result.render().rateSeconds()),
                new MetricsWire.Resolution(lower(result.resolution().requested()), lower(result.resolution().served()),
                        result.resolution().bucketSeconds()),
                new MetricsWire.Coverage(result.coverage().requestedFrom().toString(),
                        result.coverage().servedFrom().toString(), result.coverage().truncatedByRetention(),
                        result.coverage().provisionalFrom() == null ? null
                                : result.coverage().provisionalFrom().toString()),
                result.gapThresholdSeconds(), result.series().stream().map(QueryComposer::series).toList(),
                new MetricsWire.Partial(result.partial().degraded(), result.partial().unavailable().stream()
                        .map(u -> new MetricsWire.Unavailable(u.instanceId(), u.label(),
                                u.reason() == RangeResult.Reason.NOT_COLLECTING ? "not-collecting"
                                        : "collector-degraded"))
                        .toList()));
    }

    private static MetricsWire.ResultSeries series(SeriesView view) {
        SeriesView.SeriesRef ref = view.ref();
        List<List<Object>> points = new ArrayList<>(view.points().size());
        for (SeriesView.Point point : view.points()) {
            points.add(List.of(point.ts().toString(), point.value(), point.sampleCount(), point.expectedCount()));
        }
        return new MetricsWire.ResultSeries(
                new MetricsWire.SeriesRef(ref.instanceId(), ref.placementId(),
                        ref.sourceKey()),
                view.label(), new MetricsWire.ReplicaComposition(view.replicasTotal(), view.replicasInactive()),
                lower(view.state()).replace('_', '-'), points,
                view.gaps().stream().map(g -> new MetricsWire.Gap(g.from().toString(), g.to().toString(), g.reason()))
                        .toList());
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
