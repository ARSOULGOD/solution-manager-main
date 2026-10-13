/**
 * Explore's result column body (task 082, Dec `DM-D16` `FE-D10` `FE-D20`
 * `SM-D27`, `dynamic-metrics` §5.8/§11.2, UI §A.10 — mirrors
 * `docs/design/frontend/reference/app/explore.jsx`'s own `errors`/`result`
 * rendering, adapted to a real `useQuery` instead of the reference's
 * synchronous `dataState` `<select>`, which is a prototype-only debug
 * harness this file does not carry over — see `console.ts`'s own comment
 * on the same point).
 *
 * §11.2 lists thirteen states. Four carry NO new rendering here because an
 * earlier task already owns them, and duplicating the rendering would be
 * the second copy `chartStateTags.ts`'s own header warns against:
 * - `needs-typing` — `MetricSelector` (task 079) disables the picker row
 *   and offers the typing affordance; a `needs-typing` metric can never
 *   become `selectedMetric`, so this file only ever sees "no metric
 *   selected", handled by `empty-selection`.
 * - `type-suspect` — `MetricSelector` (task 079) renders the persistent
 *   warning; `ChartRenderer`'s legend/tooltip (task 074) carries the
 *   per-series state tag for as long as the chart is on screen, which is
 *   what makes it "persistent" rather than a toast.
 * - `pending-config-change`, `inactive-replica`, unverified durability —
 *   all three are `SeriesState` values `ChartRenderer` (tasks 073/074)
 *   already dims/dashes/tags via `chartStateTags.ts`. This file's job is
 *   only to make sure a real `ChartModel` reaches `ChartRenderer` — the
 *   per-series rendering itself is not this task's.
 * - Gaps — `ChartRenderer` (task 073) breaks the line from
 *   `ChartSeries.gaps`; nothing here re-derives a threshold.
 *
 * The remaining rows — empty selection, first loading, refreshing, no
 * data, partial, retention truncation, and the typed `422` — are this
 * file's own: none of them had a home before this task wired a real query
 * into Explore's result column.
 */
import { Box, LinearProgress, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import { useQuery } from '@tanstack/react-query';

import { canonicalQueryHash } from '@contracts/metrics/canonicalHash';
import { projectSeries } from '@contracts/metrics/projectSeries';
import type { ChartWidgetConfig } from '@contracts/metrics/types';
import { ApiError, describeErrorCode } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { formatDateTime, timeZoneLabel } from '@shared/format';
import { t } from '@shared/i18n';
import { echartsTheme } from '@shared/theme';
import { Alert, EmptyState, Paper, Skeleton } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';

import { ChartRenderer } from '../chart/ChartRenderer';
import type { MetricsRepository, QueryRequest, QueryResponse } from '../metricsRepository';

import { deriveExploreResultState, hasNoDataInRange } from './exploreResultState';

/** Fixed so the loading skeleton and the settled chart occupy the same box — no layout shift on arrival (§11.2). */
export const EXPLORE_CHART_HEIGHT = 300;

/** `projectSeries`'s series-colour override slot — no colour-override UI exists in Explore. */
const NO_WIDGET_CONFIG: ChartWidgetConfig = {};

export interface ExploreResultProps {
  metricsRepository: Pick<MetricsRepository, 'queryRange'>;
  metricName: string | null;
  hasInstances: boolean;
  /** `null` whenever `metricName === null || !hasInstances` — the caller (`ExplorePage`) builds it once both are true. */
  request: QueryRequest | null;
}

function partialBanner(response: QueryResponse) {
  if (!response.partial.degraded) {
    return null;
  }
  return (
    <Alert severity="warn" title={t('exploreResultPartialTitle')}>
      {response.partial.unavailable.map((u) => (
        <Typography key={u.instanceId} variant="body2">
          <Box component="span" sx={{ fontFamily: 'monospace' }}>
            {u.label}
          </Box>{' '}
          — {u.reason}
        </Typography>
      ))}
      <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5, display: 'block' }}>
        {t('exploreResultPartialResidualNote')}
      </Typography>
    </Alert>
  );
}

function retentionBanner(response: QueryResponse) {
  if (!response.coverage.truncatedByRetention) {
    return null;
  }
  const time = `${formatDateTime(response.coverage.servedFrom)} ${timeZoneLabel()}`;
  return <Alert severity="info">{t('exploreResultRetentionTruncated', { time })}</Alert>;
}

function ChartOrNoData({ response }: { response: QueryResponse }) {
  const muiTheme = useTheme();

  if (hasNoDataInRange(response)) {
    return (
      <Box sx={{ height: EXPLORE_CHART_HEIGHT, display: 'grid', placeItems: 'center' }}>
        <EmptyState
          title={t('exploreResultNoDataTitle')}
          body={t('exploreResultNoDataBody', {
            served: response.resolution.served,
            bucketSeconds: response.resolution.bucketSeconds,
          })}
        />
      </Box>
    );
  }

  const model = projectSeries(response, NO_WIDGET_CONFIG, {
    categoricalColors: echartsTheme(muiTheme).color,
  });
  return (
    <Box sx={{ height: EXPLORE_CHART_HEIGHT }}>
      {/* `legendPosition="right"` — task 128 flipped `ChartRenderer`'s
          default to `'bottom'` for widget cards, which are short and wide.
          Explore's result panel is the wide-and-tall case the vertical
          legend was designed for, so it opts back in explicitly. Below
          `md` `ChartRenderer` still forces the legend under the plot
          (task 109), so this does not regress the narrow layout. */}
      <ChartRenderer
        model={model}
        ariaLabel={t('exploreChartAriaLabel', { metric: response.metric.name })}
        legendPosition="right"
      />
    </Box>
  );
}

export function ExploreResult({ metricsRepository, metricName, hasInstances, request }: ExploreResultProps) {
  const hasMetric = metricName !== null;
  const enabled = request !== null;
  const queryHash = request !== null ? canonicalQueryHash(request) : 'disabled';

  const query = useQuery({
    queryKey: queryKeys.metrics.query(queryHash),
    queryFn: ({ signal }) => metricsRepository.queryRange(request as QueryRequest, signal),
    enabled,
  });

  const state = deriveExploreResultState({
    hasMetric,
    hasInstances,
    status: query.status,
    isPlaceholderData: query.isPlaceholderData,
    error: query.error,
    data: query.data,
  });

  if (state.kind === 'empty-selection') {
    return (
      <Paper>
        <Box sx={{ height: EXPLORE_CHART_HEIGHT, display: 'grid', placeItems: 'center' }}>
          <EmptyState
            title={t(
              state.missing === 'metric' ? 'exploreResultEmptyMetricTitle' : 'exploreResultEmptyInstancesTitle',
            )}
            body={t(
              state.missing === 'metric' ? 'exploreResultEmptyMetricBody' : 'exploreResultEmptyInstancesBody',
            )}
          />
        </Box>
      </Paper>
    );
  }

  if (state.kind === 'loading') {
    return (
      <Paper>
        <Box role="status" aria-label={t('exploreResultLoadingAriaLabel')}>
          <Skeleton width="100%" height={EXPLORE_CHART_HEIGHT} />
        </Box>
      </Paper>
    );
  }

  if (state.kind === 'error') {
    const apiError = state.error instanceof ApiError ? state.error : null;
    const is422 = apiError !== null && apiError.status === 422;
    return (
      <Box>
        <ErrorPanel
          title={is422 ? t('exploreResult422Title') : t('exploreResultErrorTitle')}
          detail={apiError !== null ? describeErrorCode(apiError.code, apiError.message) : t('errorGeneric')}
          {...(apiError?.code !== undefined ? { code: apiError.code } : {})}
          {...(apiError?.correlationId !== undefined ? { correlationId: apiError.correlationId } : {})}
          {...(is422 ? {} : { onRetry: () => void query.refetch() })}
        />
        {is422 && (
          <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5, display: 'block' }}>
            {t('exploreResult422ResidualNote')}
          </Typography>
        )}
      </Box>
    );
  }

  // `state.kind === 'result'`
  const { response, refreshing } = state;
  return (
    <Box>
      {partialBanner(response)}
      {retentionBanner(response)}
      <Paper>
        <Box sx={{ position: 'relative', opacity: refreshing ? 0.6 : 1 }}>
          {refreshing && (
            <LinearProgress
              aria-label={t('exploreResultRefreshingAriaLabel')}
              sx={{ position: 'absolute', top: 0, left: 0, right: 0, zIndex: 1 }}
            />
          )}
          <ChartOrNoData response={response} />
        </Box>
      </Paper>
    </Box>
  );
}
