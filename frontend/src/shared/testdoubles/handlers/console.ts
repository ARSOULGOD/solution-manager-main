/**
 * MSW handlers for the Dynamic Metrics query surface (task 033,
 * `dynamic-metrics` §5.4–5.8, §9.2's SPECIFIED table) plus dashboards
 * (CONTRACT: TBD, §16 Q15). Backs `metricsRepository` / `dashboardRepository`.
 */
import { HttpResponse, http } from 'msw';

import { CACHE_INSTANCES } from '../data/instances';
import { generateQueryResponse, METRIC_CATALOG, type SeriesScenario } from '../data/metrics';

import {
  addWidget,
  getDashboard,
  resetDashboard,
  RevisionConflictError,
  setLayout,
  setWidgetHidden,
} from './dashboardStore';

/**
 * `X-SM-Dashboard-Revision` is REQUIRED on every dashboard write
 * (`console.yaml`'s `DashboardRevision` parameter, W2-07): missing or
 * malformed is `400`, never a default-to-zero guess.
 */
function readRevision(request: Request): number | null {
  const header = request.headers.get('X-SM-Dashboard-Revision');
  if (header === null || !/^\d+$/.test(header)) {
    return null;
  }
  return Number(header);
}

const MISSING_REVISION_RESPONSE = HttpResponse.json(
  { type: 'about:blank', title: 'Bad Request', status: 400, code: 'BAD_REQUEST' },
  { status: 400 },
);

const REVISION_CONFLICT_RESPONSE = HttpResponse.json(
  { type: 'about:blank', title: 'Conflict', status: 409, code: 'CONFLICT' },
  { status: 409 },
);

const KNOWN_SCENARIOS: readonly SeriesScenario[] = [
  'normal',
  'gaps',
  'lowcov',
  'retention',
  'nodata',
  'partial',
  'inactive',
  'pending',
];

function isSeriesScenario(value: string | undefined): value is SeriesScenario {
  return value !== undefined && (KNOWN_SCENARIOS as readonly string[]).includes(value);
}

/**
 * Mirrors `features/monitoring/metricsRepository.ts`'s `QueryRequest`
 * (§6.0.3) — a local, request-shaped subset rather than an import, since
 * `shared/testdoubles` may not import from `@features/*` (boundaries
 * policy, task 009): a feature depends on shared, never the reverse. Only
 * the JSON wire shape is shared between the two, not the TS type.
 */
interface IncomingQueryRequest {
  metric: string;
  selector: { instanceIds: readonly string[]; labels: Readonly<Record<string, string>> };
  seriesBy: 'instance' | 'replica';
  range: { from: string; to: string };
  aggregation: { acrossInstances: 'none' | 'sum' | 'avg' | 'min' | 'max' };
}

/**
 * The one 422 this fixture backend actually enforces (task 082's own
 * business rule, `dynamic-metrics` §5.8): `AGGREGATION_NOT_APPLICABLE`
 * needs at least two instances to combine. It is the only SPECIFIED 422
 * code reachable through the live UI as it stands — `SERIES_CAP_EXCEEDED`,
 * `GROUP_BY_REQUIRES_AGGREGATION`, `STAT_REQUIRES_AGGREGATION` and
 * `METRIC_UNTYPED` are all already prevented client-side (`InstanceSelector`
 * disables past the cap, `GroupByControl` is disabled until aggregation is
 * on, no mark control exists to force single-value aggregation, and
 * `MetricSelector` disables a `needs-typing` row outright) — see
 * `ExploreResult.tsx`'s own header for the same point from the rendering
 * side. Simulating refusals nothing in the running app can trigger would
 * be untestable through real interaction, so this handler stays honest
 * about which one it can actually reproduce.
 */
function aggregationError(req: IncomingQueryRequest): { code: string; message: string } | null {
  if (req.aggregation.acrossInstances !== 'none' && req.selector.instanceIds.length < 2) {
    return {
      code: 'AGGREGATION_NOT_APPLICABLE',
      message: 'Aggregation across instances needs at least two instances to combine.',
    };
  }
  return null;
}

/**
 * The scenario an instance gets when a query does not name one.
 *
 * Health and metrics are two views of one fact, and the fixture used to
 * let them disagree: `cache-stage-01` reported `not_responding` while its
 * series came back perfectly normal. That is not a neutral simplification
 * — §A.13.4's own copy says the workflow is "red badge → open the graphs →
 * no data confirms it is genuinely down", and a down instance drawing a
 * healthy line makes that cross-check prove the opposite of what it says.
 *
 * So an instance whose health rollup says it stopped answering gets the
 * `gaps` scenario. `FE-099` journey 3 walks exactly that path.
 */
function defaultScenarioFor(instances: readonly { healthRollup: string }[]): SeriesScenario {
  return instances.some((instance) => instance.healthRollup === 'not_responding')
    ? 'gaps'
    : 'normal';
}

/**
 * Converts this task's demo-state generator (numeric timestamps, no
 * `queryVersion`) into the real `QueryResponse` envelope (§6.0.3) —
 * ISO-8601 strings, `queryVersion: 1`. The scenario itself is picked from
 * a `__scenario` selector label (a FIXTURE-ONLY testing hook, never a real
 * query field — the reference's own state harness is what task 036 must
 * NOT ship) so a test can request any of the 8 states deterministically.
 */
function toQueryResponse(req: IncomingQueryRequest) {
  const instances = CACHE_INSTANCES.filter((i) => req.selector.instanceIds.includes(i.id));
  const scenario = isSeriesScenario(req.selector.labels['__scenario'])
    ? req.selector.labels['__scenario']
    : defaultScenarioFor(instances);
  const from = Date.parse(req.range.from);
  const to = Date.parse(req.range.to);
  const fixture = generateQueryResponse({
    metricName: req.metric,
    instances: instances.length > 0 ? instances : CACHE_INSTANCES.slice(0, 1),
    seriesBy: req.seriesBy,
    scenario,
    range: { from, to },
  });

  return {
    queryVersion: 1,
    metric: fixture.metric,
    render: fixture.render,
    resolution: fixture.resolution,
    coverage: {
      requestedFrom: new Date(fixture.coverage.requestedFrom).toISOString(),
      servedFrom: new Date(fixture.coverage.servedFrom).toISOString(),
      truncatedByRetention: fixture.coverage.truncatedByRetention,
    },
    gapThresholdSeconds: fixture.gapThresholdSeconds,
    series: fixture.series.map((s) => ({
      seriesRef: { ...s.seriesRef, sourceKey: null },
      label: s.label,
      replicaComposition: s.replicaComposition,
      state: s.state,
      points: s.points.map(([ts, value, sampleCount, expectedCount]) => [
        new Date(ts).toISOString(),
        value,
        sampleCount,
        expectedCount,
      ]),
      gaps: s.gaps.map((g) => ({
        from: new Date(g.from).toISOString(),
        to: new Date(g.to).toISOString(),
        reason: g.reason,
      })),
    })),
    partial: fixture.partial,
  };
}

export const consoleHandlers = [
  http.get('*/console/v1/capabilities', () =>
    // §5.4's own example response — the fixture matches the spec verbatim
    // so a component reading real `tiers`/`marks`/`aggregations` here is
    // exercised the same way task 068's fail-closed defaults are.
    HttpResponse.json({
      queryVersion: 1,
      tiers: [
        { name: 'raw', retention: 'P6M', minBucketSeconds: 15 },
        { name: 'hourly', retention: 'P2Y', minBucketSeconds: 3600 },
        { name: 'daily', retention: null, minBucketSeconds: 86400 },
      ],
      maxRange: 'P2Y',
      maxSeriesPerWidget: 10,
      maxPointsPerSeries: 1000,
      marks: ['line', 'gauge', 'single-stat'],
      aggregations: ['sum', 'avg', 'min', 'max'],
      features: { forceRaw: true, streamingExport: true, pngExport: false },
      // Required in the real `Capabilities.collector` (`console.yaml`,
      // `OQ-16` (a), `I5`) — `live` with a recent runway and no reasons is
      // the fixture's steady state; nothing here is organization-specific.
      collector: {
        state: 'live',
        runwayEnd: '2026-12-31',
        unfrozenClosedPartitions: 0,
        reasons: [],
        disk: { state: 'ok' },
      },
    }),
  ),
  http.get('*/console/v1/entity-types', () =>
    HttpResponse.json([{ id: 'cache-instance', label: 'Cache instance' }]),
  ),
  http.get('*/console/v1/entity-types/:entityType/instances', () =>
    // `replicaCount` (task 078, `DM-D7`, §11.4): invented, same as the rest
    // of this route's response shape — see `MetricInstance`'s own comment.
    HttpResponse.json({
      items: CACHE_INSTANCES.map((i) => ({
        id: i.id,
        label: i.name,
        replicaCount: i.replicas.length,
      })),
      total: CACHE_INSTANCES.length,
    }),
  ),
  http.get('*/console/v1/metrics/catalog', () => HttpResponse.json(METRIC_CATALOG)),
  http.get('*/console/v1/labels/:labelKey/values', ({ params }) => {
    if (params['labelKey'] === 'region') {
      return HttpResponse.json(['us-east', 'us-west', 'eu-west']);
    }
    return HttpResponse.json([]);
  }),
  http.post('*/console/v1/query/range', async ({ request }) => {
    const body = (await request.json()) as IncomingQueryRequest;
    const error = aggregationError(body);
    if (error !== null) {
      return HttpResponse.json(error, { status: 422 });
    }
    return HttpResponse.json(toQueryResponse(body));
  }),
  http.post('*/console/v1/query/instant', async ({ request }) => {
    const body = (await request.json()) as IncomingQueryRequest;
    const error = aggregationError(body);
    if (error !== null) {
      return HttpResponse.json(error, { status: 422 });
    }
    return HttpResponse.json(toQueryResponse(body));
  }),
  // Real backend (`console.yaml` row 61, M-Q6, `OR-75`): quarantine is
  // deferred, so this ALWAYS answers 501 for a held verb — never a typed
  // catalog entry. See `metricsRepository.ts#declareMetricType`'s header.
  http.post('*/console/v1/metrics/catalog/:name/type', () =>
    HttpResponse.json(
      {
        type: 'about:blank',
        title: 'Not Implemented',
        status: 501,
        code: 'NOT_IMPLEMENTED',
        reason: 'metric-declare-deferred',
      },
      { status: 501 },
    ),
  ),

  // ---- Dashboards (CONTRACT: TBD, §16 Q15) ----
  // Task 132 — these are stateful now. The document lives in
  // `dashboardStore.ts`; see that file's header for what was wrong with the
  // write-only version (a save that never landed, and a "Reset defaults"
  // that emptied the board).
  http.get('*/console/v1/dashboards/:dashboardId', ({ params }) =>
    HttpResponse.json(getDashboard(String(params['dashboardId']))),
  ),
  http.post('*/console/v1/dashboards/:dashboardId/widgets', async ({ params, request }) => {
    const revision = readRevision(request);
    if (revision === null) return MISSING_REVISION_RESPONSE;
    const config = (await request.json()) as Record<string, unknown>;
    try {
      return HttpResponse.json(
        addWidget(String(params['dashboardId']), config, revision),
        { status: 201 },
      );
    } catch (error) {
      if (error instanceof RevisionConflictError) return REVISION_CONFLICT_RESPONSE;
      throw error;
    }
  }),
  http.put('*/console/v1/dashboards/:dashboardId/layout', async ({ params, request }) => {
    const revision = readRevision(request);
    if (revision === null) return MISSING_REVISION_RESPONSE;
    const body = (await request.json()) as { layout?: Parameters<typeof setLayout>[1] };
    try {
      setLayout(String(params['dashboardId']), body.layout ?? [], revision);
    } catch (error) {
      if (error instanceof RevisionConflictError) return REVISION_CONFLICT_RESPONSE;
      throw error;
    }
    return new HttpResponse(null, { status: 204 });
  }),
  http.patch(
    '*/console/v1/dashboards/:dashboardId/widgets/:widgetId',
    async ({ params, request }) => {
      const revision = readRevision(request);
      if (revision === null) return MISSING_REVISION_RESPONSE;
      const body = (await request.json()) as { hidden?: boolean };
      try {
        setWidgetHidden(
          String(params['dashboardId']),
          String(params['widgetId']),
          body.hidden === true,
          revision,
        );
      } catch (error) {
        if (error instanceof RevisionConflictError) return REVISION_CONFLICT_RESPONSE;
        throw error;
      }
      return new HttpResponse(null, { status: 204 });
    },
  ),
  http.post('*/console/v1/dashboards/:dashboardId/reset', ({ params, request }) => {
    const revision = readRevision(request);
    if (revision === null) return MISSING_REVISION_RESPONSE;
    try {
      return HttpResponse.json(resetDashboard(String(params['dashboardId']), revision));
    } catch (error) {
      if (error instanceof RevisionConflictError) return REVISION_CONFLICT_RESPONSE;
      throw error;
    }
  }),
];
