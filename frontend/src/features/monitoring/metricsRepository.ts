/**
 * `MetricsRepository` (task 033, §6.0.3, §6.0.5). SPECIFIED to endpoint
 * level (`dynamic-metrics` §5.4–5.8, §9.2's table) — implemented against
 * the real shape, unlike the other eleven seams. Routes go through
 * `consoleClient` (task 027) per that same table.
 */
import type { PlaneClient } from '@shared/api';
import type { Paged } from '@shared/api/contracts/common';
import type { CatalogEntry, MetricType } from '@shared/testdoubles/data/metrics';

export type AcrossInstances = 'none' | 'sum' | 'avg' | 'min' | 'max';
export type SeriesBy = 'instance' | 'replica';

export interface QueryRequest {
  queryVersion: 1;
  /** EXACTLY ONE metric per request (`DM-D23`). */
  metric: string;
  selector: {
    entityType: string;
    instanceIds: readonly string[];
    labels: Readonly<Record<string, string>>;
  };
  /** Absolute ISO-8601 UTC, resolved at issue time. */
  range: { from: string; to: string };
  /** No free bucket size (`SM-D31`). */
  resolution: 'auto' | 'raw';
  seriesBy: SeriesBy;
  aggregation: { acrossReplicas: 'auto' | AcrossInstances; acrossInstances: AcrossInstances };
  /** Composes AFTER instance aggregation only. */
  groupBy?: readonly string[];
}

/** [timestamp, value, sampleCount, expectedCount]. A bucket with no samples emits NO tuple (G10). */
export type Point = readonly [ts: string, value: number, sampleCount: number, expectedCount: number];

export type SeriesState =
  | 'ok'
  | 'inactive-replica'
  | 'pending-config-change'
  | 'type-suspect'
  | 'unverified-durability';

export interface QueryResponse {
  queryVersion: number;
  metric: { name: string; metricType: MetricType | null; unit: string | null };
  /** SERVER-decided. */
  render: { transform: 'none' | 'rate' | 'delta'; rateSeconds?: number };
  resolution: { requested: 'auto' | 'raw'; served: string; bucketSeconds: number };
  coverage: { requestedFrom: string; servedFrom: string; truncatedByRetention: boolean };
  /** SERVER-declared — the client never re-derives it. */
  gapThresholdSeconds: number;
  series: readonly {
    seriesRef: { instanceId: string; placementId: string | null; sourceKey: string | null };
    label: string;
    replicaComposition: { total: number; inactive: number };
    state: SeriesState;
    points: readonly Point[];
    gaps: readonly { from: string; to: string; reason: string }[];
  }[];
  // CONTRACT: TBD — item shape and reason vocabulary are INVENTED (§16 Q9).
  partial: { degraded: boolean; unavailable: readonly { instanceId: string; label: string; reason: string }[] };
}

export interface CapabilityTier {
  name: string;
  /** ISO-8601 duration, or `null` for unlimited (e.g. the `daily` tier). */
  retention: string | null;
  minBucketSeconds: number;
}

/**
 * The full `GET /capabilities` shape (task 068, `DM-D10`, `dynamic-metrics`
 * §5.4). Presets, caps and tiers are **derived** from this — nothing
 * downstream may hardcode one (enforced starting at `capabilityDefaults.ts`).
 */
export interface Capabilities {
  queryVersion: number;
  tiers: readonly CapabilityTier[];
  /** ISO-8601 duration — the longest range any tier can serve. */
  maxRange: string;
  /** RENDERED series, not instances (`DM-D7`). */
  maxSeriesPerWidget: number;
  maxPointsPerSeries: number;
  marks: readonly ('line' | 'gauge' | 'single-stat')[];
  aggregations: readonly ('sum' | 'avg' | 'min' | 'max')[];
  features: { forceRaw: boolean; streamingExport: boolean; pngExport: boolean };
}

export interface EntityType {
  id: string;
  label: string;
}

export interface MetricInstance {
  id: string;
  label: string;
  /**
   * CONTRACT: TBD — §5.5 specifies only the route, not a response shape,
   * so this field has no spec source. It exists because task 078's series
   * cap projection is `Σ replicas` under `seriesBy: 'replica'` (`DM-D7`,
   * §11.4) and cannot be computed client-side without a per-instance
   * replica count somewhere in this catalog entry.
   */
  replicaCount: number;
}

export interface InstanceQuery {
  search?: string;
}

export interface CatalogQuery {
  entityType?: string;
}

export interface LabelQuery {
  entityType?: string;
}

export interface MetricsRepository {
  capabilities(signal?: AbortSignal): Promise<Capabilities>;
  entityTypes(signal?: AbortSignal): Promise<EntityType[]>;
  instances(entityType: string, query: InstanceQuery, signal?: AbortSignal): Promise<Paged<MetricInstance>>;
  metricCatalog(query: CatalogQuery, signal?: AbortSignal): Promise<CatalogEntry[]>;
  labelValues(labelKey: string, query: LabelQuery, signal?: AbortSignal): Promise<string[]>;
  queryRange(req: QueryRequest, signal?: AbortSignal): Promise<QueryResponse>;
  queryInstant(req: QueryRequest, signal?: AbortSignal): Promise<QueryResponse>;
  /** Streamed via same-origin navigation (task 035) — never buffered, never a fetch(). */
  exportRangeUrl(req: QueryRequest): string;
  /** CONTRACT: TBD (§16 Q14) — no `firstSeen`, no suggested-type field. */
  declareMetricType(name: string, type: MetricType, unit: string): Promise<CatalogEntry>;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

export function createMetricsRepository(consoleClient: PlaneClient): MetricsRepository {
  return {
    capabilities(signal) {
      return consoleClient.get<Capabilities>('/capabilities', opts(signal));
    },
    entityTypes(signal) {
      return consoleClient.get<EntityType[]>('/entity-types', opts(signal));
    },
    instances(entityType, query, signal) {
      const params = new URLSearchParams();
      if (query.search !== undefined && query.search.length > 0) params.set('search', query.search);
      const qs = params.toString();
      return consoleClient.get<Paged<MetricInstance>>(
        `/entity-types/${encodeURIComponent(entityType)}/instances${qs.length > 0 ? `?${qs}` : ''}`,
        opts(signal),
      );
    },
    metricCatalog(query, signal) {
      const params = new URLSearchParams();
      if (query.entityType !== undefined) params.set('entityType', query.entityType);
      const qs = params.toString();
      return consoleClient.get<CatalogEntry[]>(
        `/metrics/catalog${qs.length > 0 ? `?${qs}` : ''}`,
        opts(signal),
      );
    },
    labelValues(labelKey, query, signal) {
      const params = new URLSearchParams();
      if (query.entityType !== undefined) params.set('entityType', query.entityType);
      const qs = params.toString();
      return consoleClient.get<string[]>(
        `/labels/${encodeURIComponent(labelKey)}/values${qs.length > 0 ? `?${qs}` : ''}`,
        opts(signal),
      );
    },
    queryRange(req, signal) {
      return consoleClient.post<QueryResponse>('/query/range', req, opts(signal));
    },
    queryInstant(req, signal) {
      return consoleClient.post<QueryResponse>('/query/instant', req, opts(signal));
    },
    exportRangeUrl(req) {
      // A GET, same-origin, navigation-style download (task 035) — the
      // request body becomes a query string since a download link cannot
      // carry a POST body.
      const params = new URLSearchParams({ metric: req.metric, from: req.range.from, to: req.range.to });
      return consoleClient.resolve(`/export/range?${params.toString()}`);
    },
    declareMetricType(name, type, unit) {
      return consoleClient.post<CatalogEntry>(`/metrics/catalog/${encodeURIComponent(name)}/type`, {
        metricType: type,
        unit,
      });
    },
  };
}
