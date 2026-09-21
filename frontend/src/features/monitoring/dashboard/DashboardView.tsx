/**
 * `<DashboardView>` (task 085, Dec `DM-D21` `DM-D11` `DM-D17` `DM-D18`,
 * Design §12, `dynamic-metrics` §9.1/§9.2/§14, UI §A.11). Owns the
 * dashboard-wide toolbar state — time range, refresh, resolution, layer —
 * that `DM-D21` makes **authoritative over every widget's own defaults**.
 * There is deliberately no per-widget time pinning anywhere in this file.
 *
 * Mounted two ways, both real:
 * - `landing = true` — the Monitoring category tab itself
 *   (`InstanceShell`), already wrapped in `InstanceHeader`/`PageWidth`, so
 *   this component adds no page chrome of its own and states the "this
 *   IS the dashboard, there is no separate index" reading (§16 Q15's own
 *   answer) directly on screen rather than inventing a list route.
 * - `landing = false` — the standalone
 *   `/monitoring/dashboards/:dashboardId` route (`DashboardRoute.tsx`),
 *   which — like `ExplorePage.tsx` before it — does not render inside
 *   `InstanceShell`, so this component supplies its own minimal title and
 *   back-link. Same residual `ExplorePage.tsx`'s own header already
 *   flags: no tabs, no instance health chip.
 *
 * **`DashboardGrid`/`WidgetFrame` (task 086)** turn the fetched
 * `DashboardDoc` into a real 12-column layout with live, visibility-gated
 * per-widget queries — this file only fetches the document, applies the
 * layer filter, and hands the result to that grid; it never reads
 * `widget.range`/`widget.resolution` itself (`DM-D21` — see `WidgetFrame`'s
 * own header for where that rule actually binds a query). **A hidden
 * default is deliberately NOT filtered out here** (task 087) — `layer`
 * filtering still applies, but `widget.hidden` does not, so `DashboardGrid`
 * can render it as a restorable placeholder in its own slot rather than
 * this file making it disappear.
 *
 * **`dashboard.range`/`dashboard.refresh` are read from nowhere in this
 * file.** `dashboardRepository.ts`'s own header marks the whole document
 * shape `CONTRACT: TBD`, including these two fields — seeding the toolbar
 * from them would be guessing at a meaning the contract hasn't stated.
 * The toolbar starts at the same stated defaults Explore's own controls
 * use (`24h`, `auto`, refresh `off`) and is session-only, exactly like
 * Explore's `resolution`. Flagged as a residual, not silently decided.
 */
import { Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import { Button, EmptyState, Skeleton } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { Stack } from '@shared/ui/layout';

import type { DashboardDoc, WidgetConfig } from '../dashboardRepository';
import type { Resolution } from '../explore/ResolutionControl';
import {
  isTimeRangePresetKey,
  resolveTimeRange,
  type TimeRangeValue,
} from '../explore/timeRangeOptions';
import { useMetrics } from '../MetricsProvider';
import { MonitoringTabs } from '../MonitoringTabs';

import { DashboardGrid } from './DashboardGrid';
import { DashboardToolbar, type LayerFilterValue } from './DashboardToolbar';
import { MonitoringFirstRun } from './MonitoringFirstRun';
import {
  estimatedBucketSeconds,
  flooredRefreshSeconds,
  refreshDisabledForRange,
  type RefreshIntervalSeconds,
} from './refreshPolicy';

export interface DashboardViewProps {
  solutionId: string;
  instanceId: string;
  dashboardId: string;
  /** Whether this is the Monitoring category landing (embeds in existing chrome) or the standalone route. */
  landing: boolean;
}

// Feature code may not import `@app/routes` (module boundaries) — same
// hand-written-template pattern `ExplorePage.tsx`'s own `monitoringHref`
// already uses for this exact shape.
function exploreHref(solutionId: string, instanceId: string): string {
  return `/solutions/${solutionId}/instances/${instanceId}/monitoring/explore`;
}

function describeDashboardError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return t('errorGeneric');
}

/**
 * Layer filter, then `layout[].order` (`§18` — keyboard/reader traversal,
 * never pixel position). **A hidden default stays in this list** —
 * `DashboardGrid` (task 087) renders it as a restorable placeholder in its
 * own grid slot rather than dropping it; filtering it out here would make
 * "hidden, not deleted" invisible instead of just quiet. Filtering to the
 * `shared` layer still drops it, same as any other `product-default`
 * widget — a layer the operator has filtered OUT should show none of that
 * layer's widgets, hidden or not.
 */
function visibleWidgets(dashboard: DashboardDoc, layer: LayerFilterValue): WidgetConfig[] {
  const orderByWidgetId = new Map(
    dashboard.layout.map((placement) => [placement.widgetId, placement.order]),
  );
  return dashboard.widgets
    .filter((widget) => layer === 'all' || widget.layer === layer)
    .slice()
    .sort((a, b) => (orderByWidgetId.get(a.id) ?? 0) - (orderByWidgetId.get(b.id) ?? 0));
}

export function DashboardView({
  solutionId,
  instanceId,
  dashboardId,
  landing,
}: DashboardViewProps) {
  const navigate = useNavigate();
  const { capabilities, dashboardRepository } = useMetrics();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();

  const timeRange: TimeRangeValue = (() => {
    const range = searchParams.get('range');
    const from = searchParams.get('from');
    const to = searchParams.get('to');
    if (range === 'custom' && from !== null && to !== null) {
      return { kind: 'custom', from, to };
    }
    if (range !== null && isTimeRangePresetKey(range)) {
      return { kind: 'preset', preset: range };
    }
    return { kind: 'preset', preset: '24h' };
  })();

  function handleTimeRangeChange(value: TimeRangeValue): void {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value.kind === 'preset') {
          next.set('range', value.preset);
          next.delete('from');
          next.delete('to');
        } else {
          next.set('range', 'custom');
          next.set('from', value.from);
          next.set('to', value.to);
        }
        return next;
      },
      { replace: true },
    );
  }

  // Session-only — see this file's header on why these three don't read
  // `dashboard.refresh`/a doc-level resolution/layer default.
  const [refreshSeconds, setRefreshSeconds] = useState<RefreshIntervalSeconds>(0);
  const [resolution, setResolution] = useState<Resolution>('auto');
  const [layer, setLayer] = useState<LayerFilterValue>('all');

  // `DM-D17` — this listener only drives the toolbar's stated "paused"
  // reason (task 085). The actual pause/resume mechanism is TanStack
  // Query's own `focusManager` (per-widget `refetchInterval`/
  // `refetchOnWindowFocus`, task 086) — see `WidgetFrame.tsx`'s own header
  // for why no second listener was needed there.
  const [documentHidden, setDocumentHidden] = useState(() => document.hidden);
  useEffect(() => {
    function handleVisibilityChange() {
      setDocumentHidden(document.hidden);
    }
    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => document.removeEventListener('visibilitychange', handleVisibilityChange);
  }, []);

  // Memoized on `timeRange` alone, same reasoning `ExplorePage.tsx`'s own
  // `absoluteRange` gives: `resolveTimeRange` calls `Date.now()` for a
  // preset, and every widget on this dashboard must share the exact same
  // resolved window (`DM-D21`) — recomputing per render would let two
  // widgets built from two different render ticks disagree by
  // milliseconds, which would also defeat `canonicalQueryHash`'s "two
  // widgets asking the same question collapse to one request" (§10).
  const resolvedRange = useMemo(() => resolveTimeRange(timeRange), [timeRange]);
  const rangeMs = new Date(resolvedRange.to).getTime() - new Date(resolvedRange.from).getTime();
  const bucketSeconds = estimatedBucketSeconds(rangeMs, capabilities.tiers);
  const refreshDisabled = refreshDisabledForRange(timeRange);
  const refreshPaused = !refreshDisabled && refreshSeconds !== 0 && documentHidden;
  const effectiveRefreshSeconds = refreshDisabled
    ? 0
    : flooredRefreshSeconds(refreshSeconds, bucketSeconds);

  const dashboardQuery = useQuery({
    queryKey: queryKeys.dashboards.detail(dashboardId),
    queryFn: ({ signal }) => dashboardRepository.get(dashboardId, signal),
  });

  // Task 131 — both lifted out of `DashboardGrid`. "Edit layout" and
  // "Reset defaults" act on the dashboard, not on the grid that happens to
  // draw it, and they now sit on the dashboard's own toolbar.
  const [editing, setEditing] = useState(false);
  const resetMutation = useMutation({
    mutationFn: () => dashboardRepository.resetDefaults(dashboardId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.dashboards.detail(dashboardId) });
    },
  });

  const toolbarActions = (
    <>
      {/* The front door for "add a metric to this dashboard". Explore is
          where a widget is built and saved; before tasks 130/131 it was
          reachable only from empty states. */}
      <Button size="sm" onClick={() => void navigate(exploreHref(solutionId, instanceId))}>
        {t('dashboardAddWidget')}
      </Button>
      <Button
        size="sm"
        variant="text"
        onClick={() => resetMutation.mutate()}
        disabled={resetMutation.isPending}
      >
        {t('dashboardGridResetDefaults')}
      </Button>
      <Button
        size="sm"
        variant={editing ? 'contained' : 'outlined'}
        onClick={() => setEditing((value) => !value)}
      >
        {editing ? t('dashboardGridEditOff') : t('dashboardGridEditOn')}
      </Button>
    </>
  );

  return (
    // Task 130 — the Dashboards/Explore bar, on BOTH the category landing
    // and the standalone route. On the standalone route it also replaces
    // the "← Monitoring" link that used to sit beside the heading: the bar
    // is a two-way route between the two screens from a fixed place, so
    // the one-way link was a second, weaker copy of it.
    // Task 131 — the landing note was a permanent full-width `Alert`; it is
    // a fact an operator needs once, so it becomes an `InfoTip` on the tab
    // bar instead of a standing banner above every dashboard.
    <MonitoringTabs
      solutionId={solutionId}
      instanceId={instanceId}
      {...(landing ? { note: t('dashboardViewLandingNote') } : {})}
    >
      <Stack gap={2}>
        {!landing && (
          <Typography variant="h6" component="h2">
            {t('dashboardViewTitle')}
          </Typography>
        )}

        <DashboardToolbar
          timeRange={timeRange}
          onTimeRangeChange={handleTimeRangeChange}
          tiers={capabilities.tiers}
          refreshSeconds={refreshSeconds}
          onRefreshChange={setRefreshSeconds}
          effectiveRefreshSeconds={effectiveRefreshSeconds}
          refreshDisabled={refreshDisabled}
          refreshPaused={refreshPaused}
          resolution={resolution}
          onResolutionChange={setResolution}
          forceRawAdvertised={capabilities.features.forceRaw}
          layer={layer}
          onLayerChange={setLayer}
          actions={toolbarActions}
        />

        {dashboardQuery.isPending ? (
          <Skeleton width="100%" height={240} />
        ) : dashboardQuery.isError ? (
          <ErrorPanel
            title={t('dashboardViewErrorTitle')}
            detail={describeDashboardError(dashboardQuery.error)}
            onRetry={() => void dashboardQuery.refetch()}
          />
        ) : (
          (() => {
            const dashboard = dashboardQuery.data;
            const shown = visibleWidgets(dashboard, layer);

            // True first-run (task 089, `DM-D9`) — the dashboard has NO
            // widgets at all, in any layer. Distinct from a layer filter
            // that hides an otherwise-populated dashboard (below): that case
            // keeps the plain `EmptyState`, since the dashboard is not
            // actually empty and the fix is "change the filter", not
            // "go build something in Explore".
            if (dashboard.widgets.length === 0) {
              return <MonitoringFirstRun solutionId={solutionId} instanceId={instanceId} />;
            }

            if (shown.length === 0) {
              return (
                <EmptyState
                  title={t('dashboardViewEmptyTitle')}
                  body={t('dashboardViewEmptyBody')}
                  action={
                    <Button onClick={() => void navigate(exploreHref(solutionId, instanceId))}>
                      {t('dashboardViewEmptyAction')}
                    </Button>
                  }
                />
              );
            }

            return (
              <DashboardGrid
                dashboardId={dashboardId}
                widgets={shown}
                layout={dashboard.layout}
                absoluteRange={resolvedRange}
                resolution={resolution}
                refreshSeconds={effectiveRefreshSeconds}
                editing={editing}
              />
            );
          })()
        )}
      </Stack>
    </MonitoringTabs>
  );
}
