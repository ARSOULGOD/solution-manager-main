/**
 * Metric catalog and the deterministic series generator (task 033, UI
 * §A.13.4). Ported from
 * `docs/design/frontend/reference/app/charts.jsx`'s `METRIC_CATALOG` and
 * `genResult()` — the query request/response SHAPE mirrors plan §6.0.3
 * (`QueryRequest`/`QueryResponse`), which is SPECIFIED to endpoint level
 * (`dynamic-metrics` §5.4–5.8, §9.2's table). The catalog entries and the
 * generator's synthetic values are INVENTED demo content, same as the
 * reference.
 */
import type { InstanceRow } from './instances';

export type MetricType = 'counter' | 'gauge' | 'histogram';
export type MetricState = 'plottable' | 'needs-typing' | 'type-suspect';

export interface CatalogEntry {
  name: string;
  metricType: MetricType | null;
  unit: string | null;
  layer: 'package-baseline' | 'user-extension';
  state: MetricState;
  lastSeen: number;
  labelKeys: readonly string[];
  durability?: 'durable' | 'best-effort';
  suspectReason?: string;
}

const NOW = Date.parse('2026-08-14T10:00:00Z');
const HOUR = 3_600_000;
const DAY = 86_400_000;

// Eight metrics: six plottable (both layers), one needs-typing, one type-suspect.
export const METRIC_CATALOG: readonly CatalogEntry[] = [
  {
    name: 'cache.hit_rate',
    metricType: 'gauge',
    unit: 'ratio',
    layer: 'package-baseline',
    state: 'plottable',
    lastSeen: NOW - 5 * 60_000,
    labelKeys: ['region'],
    durability: 'durable',
  },
  {
    name: 'cache.evictions',
    metricType: 'counter',
    unit: 'count',
    layer: 'package-baseline',
    state: 'plottable',
    lastSeen: NOW - 5 * 60_000,
    labelKeys: ['region'],
    durability: 'best-effort',
  },
  {
    name: 'cache.hit_count',
    metricType: 'counter',
    unit: 'events',
    layer: 'package-baseline',
    state: 'plottable',
    lastSeen: NOW - 5 * 60_000,
    labelKeys: [],
    durability: 'best-effort',
  },
  {
    name: 'cache.miss_count',
    metricType: 'counter',
    unit: 'events',
    layer: 'package-baseline',
    state: 'plottable',
    lastSeen: NOW - 5 * 60_000,
    labelKeys: [],
    durability: 'best-effort',
  },
  {
    name: 'cache.size',
    metricType: 'gauge',
    unit: 'entries',
    layer: 'package-baseline',
    state: 'plottable',
    lastSeen: NOW - 5 * 60_000,
    labelKeys: [],
    durability: 'durable',
  },
  {
    name: 'cache.p99_get_latency',
    metricType: 'gauge',
    unit: 'ms',
    layer: 'user-extension',
    state: 'plottable',
    lastSeen: NOW - 8 * 60_000,
    labelKeys: [],
    durability: 'best-effort',
  },
  {
    name: 'cache.custom_lag',
    metricType: null,
    unit: null,
    layer: 'user-extension',
    state: 'needs-typing',
    lastSeen: NOW - 30 * 60_000,
    labelKeys: [],
  },
  {
    name: 'cache.queue_depth',
    metricType: 'gauge',
    unit: 'items',
    layer: 'user-extension',
    state: 'type-suspect',
    lastSeen: NOW - 12 * 60_000,
    labelKeys: [],
    durability: 'best-effort',
    suspectReason:
      'Observed values climb then drop to zero like a counter reset, but this is typed as a gauge — deltas may mislead.',
  },
];

export const RANGE_PRESETS: readonly { key: string; label: string; ms: number }[] = [
  { key: '15m', label: '15m', ms: 15 * 60_000 },
  { key: '1h', label: '1h', ms: HOUR },
  { key: '6h', label: '6h', ms: 6 * HOUR },
  { key: '24h', label: '24h', ms: DAY },
  { key: '7d', label: '7d', ms: 7 * DAY },
  { key: '30d', label: '30d', ms: 30 * DAY },
  { key: '90d', label: '90d', ms: 90 * DAY },
  { key: '6mo', label: '6mo', ms: 182 * DAY },
];

/** [timestamp, value, sampleCount, expectedCount]. A bucket with no samples emits NO tuple (G10). */
export type Point = readonly [
  ts: number,
  value: number,
  sampleCount: number,
  expectedCount: number,
];

export type SeriesState =
  'ok' | 'inactive-replica' | 'pending-config-change' | 'type-suspect' | 'unverified-durability';

export interface SeriesFixture {
  seriesRef: { instanceId: string; placementId: string | null };
  label: string;
  points: readonly Point[];
  gaps: readonly { from: number; to: number; reason: string }[];
  replicaComposition: { total: number; inactive: number };
  state: SeriesState;
}

export interface QueryResponseFixture {
  metric: { name: string; metricType: MetricType | null; unit: string | null };
  render: { transform: 'none' | 'rate'; rateSeconds?: number };
  resolution: { requested: 'auto' | 'raw'; served: string; bucketSeconds: number };
  coverage: { requestedFrom: number; servedFrom: number; truncatedByRetention: boolean };
  gapThresholdSeconds: number;
  series: readonly SeriesFixture[];
  annotations: readonly { ts: number; label: string }[];
  // CONTRACT: TBD — item shape and reason vocabulary are INVENTED (§16 Q9).
  partial: {
    degraded: boolean;
    unavailable: readonly { instanceId: string; label: string; reason: string }[];
  };
}

/** The 8 states §A.13.4 requires the generator to honour. */
export type SeriesScenario =
  'normal' | 'gaps' | 'lowcov' | 'retention' | 'nodata' | 'partial' | 'inactive' | 'pending';

// Deterministic PRNG (mulberry32) — same seeding strategy as the
// reference, so a given (instance, replica) pair always produces the same
// synthetic series, run after run.
function mulberry32(seed: number): () => number {
  let a = seed;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function seedFrom(text: string): number {
  let sum = 0;
  for (const ch of text) sum += ch.charCodeAt(0);
  return sum;
}

export interface GenerateSeriesOptions {
  metricName: string;
  instances: readonly InstanceRow[];
  seriesBy: 'instance' | 'replica';
  scenario: SeriesScenario;
  range: { from: number; to: number };
}

export function generateQueryResponse(options: GenerateSeriesOptions): QueryResponseFixture {
  const { metricName, instances, seriesBy, scenario, range } = options;
  const step = HOUR;
  const { from, to } = range;
  const catalogEntry = METRIC_CATALOG.find((m) => m.name === metricName) ?? METRIC_CATALOG[0]!;
  const isRate = catalogEntry.metricType === 'counter';

  const gapFrom = scenario === 'gaps' ? from + (to - from) * 0.45 : null;
  const gapTo = scenario === 'gaps' ? from + (to - from) * 0.58 : null;
  const servedFrom = scenario === 'retention' ? from + (to - from) * 0.35 : from;
  const lowSpanA = from + (to - from) * 0.68;
  const lowSpanB = from + (to - from) * 0.78;

  const series: SeriesFixture[] = [];
  instances.forEach((inst) => {
    const reps =
      seriesBy === 'replica'
        ? inst.replicas
        : [
            {
              name: inst.name,
              health: inst.healthRollup === 'notfound' ? 'not_responding' : inst.healthRollup,
            },
          ];

    reps.forEach((rep) => {
      const rnd = mulberry32(seedFrom(inst.id + rep.name));
      const inactive = rep.health === 'inactive_replica';
      const base =
        catalogEntry.unit === 'ratio'
          ? 0.6 + rnd() * 0.3
          : catalogEntry.unit === 'ms'
            ? 40 + rnd() * 60
            : catalogEntry.unit === 'entries'
              ? 40_000 + rnd() * 40_000
              : 8 + rnd() * 20;

      const points: Point[] = [];
      for (let t = from; t <= to; t += step) {
        if (t < servedFrom) continue;
        if (gapFrom !== null && gapTo !== null && t >= gapFrom && t <= gapTo) continue;
        if (scenario === 'nodata') continue;

        const expected = 60;
        let sample = 60;
        if (scenario === 'lowcov' && t >= lowSpanA && t <= lowSpanB) {
          sample = 14 + Math.floor(rnd() * 10);
        }

        let value: number;
        if (inactive) {
          value = 0; // standby: legitimately zero, never a gap
        } else {
          const wobble = Math.sin(t / (DAY / 2) + seedFrom(inst.id)) * 0.5 + (rnd() - 0.5) * 0.4;
          value = Math.max(0, base * (1 + wobble * 0.35));
          if (catalogEntry.unit === 'ratio') {
            value = Math.min(1, base + wobble * 0.12);
          }
        }
        points.push([t, isRate && !inactive ? Math.max(0, value) : value, sample, expected]);
      }

      const gaps =
        gapFrom !== null && gapTo !== null
          ? [{ from: gapFrom, to: gapTo, reason: 'no-samples' }]
          : [];

      const state: SeriesState =
        scenario === 'pending'
          ? 'pending-config-change'
          : inactive
            ? 'inactive-replica'
            : catalogEntry.state === 'type-suspect'
              ? 'type-suspect'
              : catalogEntry.durability === 'best-effort'
                ? 'unverified-durability'
                : 'ok';

      series.push({
        seriesRef: { instanceId: inst.id, placementId: seriesBy === 'replica' ? rep.name : null },
        label: seriesBy === 'replica' ? `${inst.name}·${rep.name}` : inst.name,
        points,
        gaps,
        replicaComposition: {
          total: inst.replicas.length,
          inactive: inst.replicas.filter((r) => r.health === 'inactive_replica').length,
        },
        state,
      });
    });
  });

  const annotations =
    scenario === 'normal' || scenario === 'gaps'
      ? [
          {
            ts: from + (to - from) * 0.6,
            label: 'replica added (+1) — summed series steps; not a workload change',
          },
        ]
      : [];

  return {
    metric: {
      name: catalogEntry.name,
      metricType: catalogEntry.metricType,
      unit: catalogEntry.unit,
    },
    render: { transform: isRate ? 'rate' : 'none', rateSeconds: 60 },
    resolution: { requested: 'auto', served: 'hourly', bucketSeconds: 3600 },
    coverage: {
      requestedFrom: from,
      servedFrom: scenario === 'retention' ? from + (to - from) * 0.35 : from,
      truncatedByRetention: scenario === 'retention',
    },
    gapThresholdSeconds: 7200,
    series,
    annotations,
    partial:
      scenario === 'partial'
        ? {
            degraded: true,
            unavailable: [
              {
                instanceId: 'cache-edge-01',
                label: 'cache-edge-01',
                reason: 'host unreachable · no telemetry for 8m',
              },
            ],
          }
        : { degraded: false, unavailable: [] },
  };
}
