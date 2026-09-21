/**
 * `<WidgetFrame>` (task 086, Dec `DM-D6` `FE-D20`, Design §12/§17,
 * `dynamic-metrics` §8/§13/§17, UI §A.11 — mirrors the visual reference's
 * own `WidgetCell` in `docs/design/frontend/reference/app/dashboards.jsx`).
 * One dashboard widget: title, `LayerBadge`, a menu, state chrome, and its
 * own error boundary — a throw inside this component never reaches a
 * sibling's (`WidgetErrorBoundary`, moved to `@shared/ui` by this task —
 * see that file's own header for why).
 *
 * **Query lifecycle**, entirely owned here, per widget:
 * - `useWidgetVisibility` gates the FIRST query — a widget below the fold
 *   issues no request until it scrolls into (near) view (§13, §17).
 * - The request is built via `buildQuery(widget.query, …)` against the
 *   DASHBOARD's own `absoluteRange`/`resolution` props — **never**
 *   `widget.range`/`widget.resolution`, which are defaults a dashboard
 *   overrides always (`DM-D21`; `DashboardView`'s own header states the
 *   same rule for the toolbar). This is where that rule actually binds a
 *   query, not just a screen-text statement.
 * - `refetchInterval`/`refetchOnWindowFocus` implement `DM-D17`'s
 *   pause-on-hidden/resume-with-one-fetch for free: `queryClient.ts` sets
 *   `refetchOnWindowFocus: false` app-wide (a deliberate choice for
 *   Designer/Governance — refocusing an admin console isn't a refresh
 *   signal), so this component overrides it back to `'always'` **only**
 *   while its own refresh is on — TanStack's `focusManager` already skips
 *   a scheduled `refetchInterval` tick while `document.hidden`
 *   (`query-core`'s own `queryObserver.js`, verified in `node_modules`),
 *   so no manual visibility listener is needed here; `DashboardView`'s own
 *   listener (task 085) is for the toolbar's stated "paused" reason, a
 *   separate concern from the actual pause mechanism.
 * - **Dim, never blank, on refresh**: `isFetching && data !== undefined`
 *   — broader than `ExploreResult.tsx`'s own `isPlaceholderData` check,
 *   which only covers a QUERY-KEY change. A widget's key never changes
 *   (the saved config is fixed); its "refreshing" moments are periodic
 *   `refetchInterval` ticks on the SAME key, where `isPlaceholderData` is
 *   always `false` and `data` simply stays the last response while
 *   `isFetching` goes true — `keepPreviousData` and this check are
 *   complementary, not redundant.
 * - A `schemaVersion` newer than this console's own renders **read-only
 *   and whole** (`migrateWidgetDocument`, task 084) — no query is even
 *   built for it.
 *
 * **Mark dispatch**: `line` → `ChartRenderer` (task 072-076, the app's one
 * `echarts` import site, `FE-D16`); `gauge`/`single-stat` → `GaugeMark`/
 * `SingleStatMark` (task 076, plain SVG/MUI, deliberately outside
 * `ChartRenderer`). One `projectSeries` call feeds whichever one mark
 * needs — `mark` is a closed set (`DM-D12`), so this switch is exhaustive.
 *
 * **The menu** (task 087) always carries a caption stating "Edit widget" is
 * still pending — design §12's edit flow ("widget menu → Explore prefilled
 * → overwrite or fork") has no owning `FE-*` task, and a clickable-but-
 * disabled item would look like a bug; the caption states the truth
 * instead, same posture `SaveWidgetDialog`'s own dev-note takes for its
 * invented route. A `product-default` widget's menu adds one real action —
 * "Hide (keep default)" (`onHide`) — a package default may be hidden but
 * never deleted (design §12); `DashboardGrid` swaps this component out for
 * `HiddenDefaultWidget` once `widget.hidden` is `true`, so this component
 * itself never renders a widget it has just hidden. A `shared` widget gets
 * no hide/delete item — deleting an operator-saved widget has no owning
 * task or repository method either.
 *
 * **Export CSV** (task 088, Dec `DM-D14` `DM-D18`, design §13/§15,
 * `dynamic-metrics` §5.8 — the widget-menu action `dynamic-metrics/
 * screens/console.html`'s own step 8 names). Gated on
 * `capabilities.features.streamingExport` alone, **never** on the
 * `metrics.export` verb: that verb is `proposed`, not `grantable`
 * (`shared/authz/catalog.ts`), and `permissions.ts`'s C12 rule forbids
 * gating a v1 feature on a non-`grantable` verb — same posture
 * `ResolutionControl`'s own `forceRaw` gate already takes.
 * TODO(verb): once `metrics.export` is ratified `grantable`, wrap this
 * entry in `Can` here — the gate on the capability flag stays either way.
 * Omitted entirely for a `too-new` document (no query to export) and
 * whenever the capability isn't advertised — "verb/capability absent →
 * control omitted" (design §15's own table).
 *
 * Confirmed, not just gated: a streaming export can strain the deployment
 * (design §15 lists it among the named high-consequence actions), so
 * clicking the entry opens a `HighConsequenceDialog`; only on confirm does
 * this build the export request — from the widget's own query against the
 * **dashboard's** `absoluteRange`/`resolution` props, exactly the query
 * this widget is currently rendering (`DM-D21`) — and hand its URL to
 * `startStreamingDownload` (task 035): same-origin navigation, streamed to
 * disk, never buffered, never a `fetch()`.
 */
import { Box, IconButton, Typography } from '@mui/material';
import type { Theme } from '@mui/material/styles';
import { useTheme } from '@mui/material/styles';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useRef, useState } from 'react';

import { buildQuery } from '@contracts/metrics/buildQuery';
import { canonicalQueryHash } from '@contracts/metrics/canonicalHash';
import { projectSeries } from '@contracts/metrics/projectSeries';
import type { ChartWidgetConfig } from '@contracts/metrics/types';
import { ApiError, describeErrorCode, startStreamingDownload } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import { echartsTheme } from '@shared/theme';
import { EmptyState, HighConsequenceDialog, Menu, Paper, Skeleton, Tooltip } from '@shared/ui';
import type { MenuEntry } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { WidgetErrorBoundary } from '@shared/ui/WidgetErrorBoundary';

import { ChartRenderer } from '../chart/ChartRenderer';
import { GaugeMark } from '../chart/GaugeMark';
import { SingleStatMark } from '../chart/SingleStatMark';
import type { WidgetConfig, WidgetPlacement } from '../dashboardRepository';
import { hasNoDataInRange } from '../explore/exploreResultState';
import type { Resolution } from '../explore/ResolutionControl';
import { useMetrics } from '../MetricsProvider';
import { migrateWidgetDocument, type WidgetDocument } from '../widget/migrations';
import { CURRENT_WIDGET_SCHEMA_VERSION, type WidgetConfigV1 } from '../widget/widgetConfig';

import { LayerBadge } from './LayerBadge';
import { useWidgetVisibility } from './useWidgetVisibility';

/**
 * Task 129 — a widget body is sized from its own `placement.h`, not from a
 * flat constant. `DashboardGrid` spans `placement.h` rows of a 150px
 * `gridAutoRows` track at `lg`+ (the reference's `.grid12` + `h * 150`), so
 * a flat 220px left an `h: 2` card two-thirds empty under the plot.
 *
 * The floor exists for `h: 1`: 150px minus the header rule and the body's
 * own padding is not enough for a legible chart, so a single-row widget
 * gets the same minimum the flat constant used to give every widget.
 */
const WIDGET_ROW_HEIGHT = 150;
const WIDGET_BODY_MIN_HEIGHT = 150;
/** The reference's `.grid12` gap — a spanned cell recovers it between each pair of rows. */
const WIDGET_ROW_GAP = 14;

export function widgetBodyHeight(h: number): number {
  const rows = Math.max(1, h);
  return Math.max(WIDGET_BODY_MIN_HEIGHT, rows * WIDGET_ROW_HEIGHT + (rows - 1) * WIDGET_ROW_GAP);
}

/**
 * The body slot, in both layout regimes.
 *
 * At `lg`+ the grid cell already HAS a real height — `DashboardGrid` sets
 * `gridAutoRows: 150px` and the card spans `placement.h` of them — so the
 * slot must *fill* what the header leaves rather than demand a height of
 * its own. Demanding one would push the card past its cell, and `Paper`'s
 * `fill` carries `overflow: hidden`, so the bottom of the plot would be
 * clipped.
 *
 * Below `lg` there is no `gridRow` at all (rows auto-size to content —
 * `DashboardGrid`'s own comment), so nothing imposes a height and the slot
 * has to bring its own or collapse to nothing.
 */
function bodySlotSx(bodyHeight: number) {
  return (theme: Theme) => ({
    flex: 1,
    minHeight: bodyHeight,
    [theme.breakpoints.up('lg')]: { minHeight: 0 },
  });
}

// Present for screen readers, invisible on screen — the same clip pattern
// `ChartRenderer.tsx`/`LogViewer.tsx` each already carry their own copy of
// (task 067/075); not consolidated there either, so this follows the same
// established per-file convention rather than being the one exception.
const visuallyHiddenSx = {
  position: 'absolute',
  width: 1,
  height: 1,
  padding: 0,
  margin: -1,
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  border: 0,
} as const;

export interface WidgetFrameProps {
  widget: WidgetConfig;
  placement: WidgetPlacement;
  /** The DASHBOARD's own resolved window — never `widget.range` (`DM-D21`). */
  absoluteRange: { from: string; to: string };
  /** The DASHBOARD's own resolution — never `widget.resolution` (`DM-D21`). */
  resolution: Resolution;
  /** Effective (already floored/disabled-aware) seconds; `0` means off. */
  refreshSeconds: number;
  editing: boolean;
  onMoveEarlier: () => void;
  onMoveLater: () => void;
  canMoveEarlier: boolean;
  canMoveLater: boolean;
  /** Hides this `product-default` widget (never called for a `shared` one — see this file's header). */
  onHide: () => void;
  /** True while a hide/restore call for THIS dashboard is already in flight — disables the menu item, not the whole menu. */
  hidePending: boolean;
}

function describeQueryError(error: unknown): {
  detail: string;
  code?: string;
  correlationId?: string;
} {
  if (error instanceof ApiError) {
    return {
      detail: describeErrorCode(error.code, error.message),
      ...(error.code !== undefined ? { code: error.code } : {}),
      ...(error.correlationId !== undefined ? { correlationId: error.correlationId } : {}),
    };
  }
  return { detail: t('errorGeneric') };
}

function TooNewContent({
  declaredVersion,
  bodyHeight,
}: {
  declaredVersion: number;
  bodyHeight: number;
}) {
  return (
    <Box sx={[bodySlotSx(bodyHeight), { display: 'grid', placeItems: 'center' }]}>
      <EmptyState
        title={t('widgetFrameSchemaTooNewTitle')}
        body={t('widgetFrameSchemaTooNewBody', {
          declared: declaredVersion,
          current: CURRENT_WIDGET_SCHEMA_VERSION,
        })}
      />
    </Box>
  );
}

/** The query-driven body: visibility gate, fetch, dim-not-blank, mark dispatch. Never called for a `too-new` document. */
function LiveWidgetBody({
  config,
  absoluteRange,
  resolution,
  refreshSeconds,
  bodyHeight,
}: {
  config: WidgetConfigV1;
  absoluteRange: { from: string; to: string };
  resolution: Resolution;
  refreshSeconds: number;
  /** Derived from `placement.h` by `widgetBodyHeight` — see this file's constants. */
  bodyHeight: number;
}) {
  const muiTheme = useTheme();
  const { metricsRepository, capabilities } = useMetrics();
  const containerRef = useRef<HTMLDivElement>(null);
  const visible = useWidgetVisibility(containerRef);

  const request = useMemo(
    () =>
      buildQuery(
        config.query,
        { range: { kind: 'absolute', from: absoluteRange.from, to: absoluteRange.to }, resolution },
        { features: { forceRaw: capabilities.features.forceRaw } },
      ),
    [config.query, absoluteRange, resolution, capabilities.features.forceRaw],
  );
  const queryHash = canonicalQueryHash(request);
  const refetchIntervalMs = refreshSeconds > 0 ? refreshSeconds * 1000 : false;

  const query = useQuery({
    queryKey: queryKeys.metrics.query(queryHash),
    queryFn: ({ signal }) => metricsRepository.queryRange(request, signal),
    enabled: visible,
    refetchInterval: refetchIntervalMs,
    // Overrides the app-wide `refetchOnWindowFocus: false` (`queryClient.ts`)
    // only while polling is actually on — see this file's header.
    refetchOnWindowFocus: refreshSeconds > 0 ? 'always' : false,
  });

  // `containerRef` is attached to ONE stable node that renders on every
  // branch below — including the very first, pre-visible one — since the
  // observer must exist before `visible` can ever become `true` (a ref
  // only mounted once already-visible would be a chicken-and-egg deadlock).
  if (!visible || query.isPending) {
    return (
      <Box
        ref={containerRef}
        role="status"
        aria-label={t('widgetFrameLoadingAriaLabel')}
        sx={bodySlotSx(bodyHeight)}
      >
        <Skeleton width="100%" height="100%" />
      </Box>
    );
  }

  if (query.isError) {
    const apiError = query.error instanceof ApiError ? query.error : null;
    return (
      <Box ref={containerRef}>
        <ErrorPanel
          title={t('widgetFrameQueryErrorTitle')}
          {...describeQueryError(query.error)}
          {...(apiError?.status === 422 ? {} : { onRetry: () => void query.refetch() })}
        />
      </Box>
    );
  }

  const response = query.data;
  // `isFetching` covers a periodic `refetchInterval` tick on the SAME key
  // (this file's header) — broader than Explore's key-change-only signal.
  const refreshing = query.isFetching && response !== undefined;

  if (hasNoDataInRange(response)) {
    return (
      <Box
        ref={containerRef}
        sx={[bodySlotSx(bodyHeight), { display: 'grid', placeItems: 'center' }]}
      >
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

  const chartWidgetConfig: ChartWidgetConfig = { series: config.series };
  const model = projectSeries(response, chartWidgetConfig, {
    categoricalColors: echartsTheme(muiTheme).color,
  });

  return (
    // A flex column so the chart branch's own `flex: 1` has something to
    // resolve against — the plot then takes exactly the height the slot
    // has, which is what removes the dead space under it.
    <Box
      ref={containerRef}
      sx={[
        bodySlotSx(bodyHeight),
        {
          position: 'relative',
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'center',
          opacity: refreshing ? 0.6 : 1,
        },
      ]}
    >
      {refreshing && (
        <Box role="status" aria-label={t('widgetFrameRefreshingAriaLabel')} sx={visuallyHiddenSx} />
      )}
      {config.mark === 'gauge' ? (
        <GaugeMark
          model={model}
          thresholds={config.thresholds}
          {...(config.axes.y.min !== null ? { min: config.axes.y.min } : {})}
          {...(config.axes.y.max !== null ? { max: config.axes.y.max } : {})}
        />
      ) : config.mark === 'single-stat' ? (
        <SingleStatMark model={model} />
      ) : (
        <Box sx={{ flex: 1, minHeight: 0 }}>
          <ChartRenderer
            model={model}
            ariaLabel={t('widgetFrameChartAriaLabel', {
              title: config.title,
              metric: config.query.metric,
            })}
            thresholds={config.thresholds}
            legendMaxHeightRows={config.legend.maxHeightRows}
            // Task 128: `WidgetConfigV1.legend.position` is finally read.
            // It has only ever been `'bottom'`, and `ChartRenderer` used
            // to ignore it and reserve 180px on the right instead.
            legendPosition={config.legend.position}
            fill={config.options.fill ?? false}
            {...(config.options.scale !== undefined ? { scale: config.options.scale } : {})}
          />
        </Box>
      )}
    </Box>
  );
}

export function WidgetFrame({
  widget,
  placement,
  absoluteRange,
  resolution,
  refreshSeconds,
  editing,
  onMoveEarlier,
  onMoveLater,
  canMoveEarlier,
  canMoveLater,
  onHide,
  hidePending,
}: WidgetFrameProps) {
  const { metricsRepository, capabilities } = useMetrics();
  const [exportConfirming, setExportConfirming] = useState(false);

  // `WidgetConfig` has no index signature, so it isn't structurally
  // assignable to `WidgetDocument` without a cast — same "the engine
  // works on any document shape, not just this console's own" genericity
  // `migrations.ts`'s own header describes.
  const migration = useMemo(
    () => migrateWidgetDocument(widget as unknown as WidgetDocument),
    [widget],
  );

  const canExport = capabilities.features.streamingExport && migration.status !== 'too-new';

  const menuEntries: MenuEntry[] = [
    { kind: 'caption', label: t('widgetFrameMenuEditPendingNote') },
    ...(widget.layer === 'product-default'
      ? [
          {
            label: t('widgetFrameMenuHide'),
            onClick: onHide,
            disabled: hidePending,
          } satisfies MenuEntry,
        ]
      : []),
    ...(canExport
      ? [
          {
            label: t('widgetFrameMenuExportCsv'),
            onClick: () => setExportConfirming(true),
          } satisfies MenuEntry,
        ]
      : []),
  ];

  function handleExportConfirm() {
    if (migration.status !== 'too-new') {
      const request = buildQuery(
        (migration.config as unknown as WidgetConfigV1).query,
        { range: { kind: 'absolute', from: absoluteRange.from, to: absoluteRange.to }, resolution },
        { features: { forceRaw: capabilities.features.forceRaw } },
      );
      startStreamingDownload(metricsRepository.exportRangeUrl(request));
    }
    setExportConfirming(false);
  }

  const bodyHeight = widgetBodyHeight(placement.h);

  return (
    // `surface="flat"` + `fill` (task 129): the card carries no padding of
    // its own, because the reference splits it between a header and a body
    // with a rule between them (`.widget-h` / `.widget-b`). `fill` makes
    // the card occupy its whole grid cell and lay the two out as a column,
    // so the body's `flex: 1` takes the remaining height instead of
    // leaving dead space under the plot.
    <Paper surface="flat" fill>
      <Box
        sx={(theme) => ({
          display: 'flex',
          alignItems: 'center',
          gap: '8px',
          // Reference `.widget-h`: `padding: 11px 14px 10px` plus a rule.
          padding: '11px 14px 10px',
          borderBottom: `1px solid ${theme.palette.divider}`,
        })}
      >
        {/* `component="div"` — a widget card title is a visual label, not a
            document heading; MUI's `subtitle2` variant defaults to `<h6>`,
            which would jump the page's real heading levels (`PageHeader`'s
            own `h1`/`EmptyState`'s `h2`) straight past h3–h5. */}
        <Typography
          variant="subtitle2"
          component="div"
          // Reference `.widget-h .wt`: `font-weight: 600; font-size: 13.5px`.
          sx={{
            flex: '1 1 auto',
            minWidth: 0,
            overflowWrap: 'anywhere',
            fontSize: '13.5px',
            fontWeight: 600,
          }}
        >
          {widget.title}
        </Typography>
        <LayerBadge layer={widget.layer} />
        {editing ? (
          <>
            <Tooltip title={t('widgetFrameOrderTooltip')}>
              <Box
                component="span"
                sx={{
                  fontFamily: 'monospace',
                  fontSize: '0.75rem',
                  color: 'text.secondary',
                  px: '4px',
                }}
              >
                #{placement.order}
              </Box>
            </Tooltip>
            <IconButton
              size="small"
              aria-label={t('widgetFrameMoveEarlier')}
              onClick={onMoveEarlier}
              disabled={!canMoveEarlier}
            >
              ↑
            </IconButton>
            <IconButton
              size="small"
              aria-label={t('widgetFrameMoveLater')}
              onClick={onMoveLater}
              disabled={!canMoveLater}
            >
              ↓
            </IconButton>
          </>
        ) : (
          <Menu
            trigger={
              <IconButton size="small" aria-label={t('widgetFrameMenuAriaLabel')}>
                ⋮
              </IconButton>
            }
            entries={menuEntries}
          />
        )}
      </Box>

      {/* No `title` override — the boundary's own default ("This widget
          failed to render") is deliberately distinct from
          `widgetFrameQueryErrorTitle` ("This widget failed to load"):
          the boundary catches an unexpected RENDER throw, not the normal
          query-error state `LiveWidgetBody` already renders inline. */}
      {/* Reference `.widget-b`: `padding: 12px 14px; flex: 1; min-height: 0`. */}
      <Box
        sx={{
          padding: '12px 14px',
          flex: 1,
          minHeight: 0,
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        <WidgetErrorBoundary>
          {migration.status === 'too-new' ? (
            <TooNewContent declaredVersion={migration.declaredVersion} bodyHeight={bodyHeight} />
          ) : (
            <LiveWidgetBody
              config={migration.config as unknown as WidgetConfigV1}
              absoluteRange={absoluteRange}
              resolution={resolution}
              refreshSeconds={refreshSeconds}
              bodyHeight={bodyHeight}
            />
          )}
        </WidgetErrorBoundary>
      </Box>

      <HighConsequenceDialog
        open={exportConfirming}
        title={t('widgetFrameExportConfirmTitle')}
        consequence="high"
        onConfirm={handleExportConfirm}
        onCancel={() => setExportConfirming(false)}
        confirmLabel={t('widgetFrameExportConfirmAction')}
      >
        {t('widgetFrameExportConfirmBody')}
      </HighConsequenceDialog>
    </Paper>
  );
}
