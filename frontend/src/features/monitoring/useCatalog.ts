/**
 * Catalog query hooks (task 069, `SM-D30`, `DM-D10`, `UI-D5`, `dynamic-metrics`
 * §5.5). Thin `useQuery` wrappers over `MetricsRepository`'s four SPECIFIED
 * catalog routes (`entity-types`, `entity-types/{t}/instances`, `metrics/catalog`,
 * `labels/{k}/values`), read through `useMetrics()` (task 068) rather than each
 * hook constructing its own repository.
 *
 * Every route here is passed through unchanged — **no hook infers, filters or
 * drops an entry**. In particular `useMetricCatalog` returns all three
 * `MetricState`s side by side; a caller choosing to grey out `needs-typing` or
 * warn on `type-suspect` (task 079) does that in its own rendering, not by this
 * hook silently thinning the list first. A `metricType: null` entry stays `null`
 * here — `SM-D30` forbids inferring one, and `metricsRepository.ts`'s
 * `declareMetricType` doc already flags the two fields the catalog entry has no
 * shape for (`firstSeen`, a suggested type — §16 Q14).
 */
import { useQuery } from '@tanstack/react-query';

import { queryKeys } from '@shared/api/queryKeys';

import { useMetrics } from './MetricsProvider';
import type { CatalogQuery, InstanceQuery, LabelQuery } from './metricsRepository';

export function useEntityTypes() {
  const { metricsRepository } = useMetrics();
  return useQuery({
    queryKey: queryKeys.metrics.entityTypes(),
    queryFn: ({ signal }) => metricsRepository.entityTypes(signal),
  });
}

export function useMetricInstances(entityType: string, query: InstanceQuery = {}) {
  const { metricsRepository } = useMetrics();
  return useQuery({
    queryKey: queryKeys.metrics.instances(entityType),
    queryFn: ({ signal }) => metricsRepository.instances(entityType, query, signal),
    enabled: entityType.length > 0,
  });
}

export function useMetricCatalog(query: CatalogQuery = {}) {
  const { metricsRepository } = useMetrics();
  return useQuery({
    queryKey: queryKeys.metrics.catalog(),
    queryFn: ({ signal }) => metricsRepository.metricCatalog(query, signal),
  });
}

export function useLabelValues(labelKey: string, query: LabelQuery = {}) {
  const { metricsRepository } = useMetrics();
  return useQuery({
    queryKey: queryKeys.metrics.labelValues(labelKey),
    queryFn: ({ signal }) => metricsRepository.labelValues(labelKey, query, signal),
    enabled: labelKey.length > 0,
  });
}
