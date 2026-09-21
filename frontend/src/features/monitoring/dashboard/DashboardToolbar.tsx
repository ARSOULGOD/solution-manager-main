/**
 * `<DashboardToolbar>` (task 085, Dec `DM-D17` `DM-D21`, `dynamic-metrics`
 * §9.1/§14, UI §A.11 — mirrors the `.dash-toolbar` row in
 * `docs/design/frontend/reference/app/dashboards.jsx`). Time range ·
 * refresh · resolution (Auto/Raw) · layer filter, **all dashboard-wide**
 * (`DM-D21`) — there is no per-widget equivalent of any of these controls.
 *
 * Presentational and controlled, same split as every other Explore/
 * Dashboard control (`TimeRangePicker`, `ResolutionControl`). All the
 * flooring/pause/disable DECISIONS live in `refreshPolicy.ts` and
 * `DashboardView`; this component only renders whatever it is told.
 *
 * **Two layers only** — `product-default` / `shared` (design §12): `entity`
 * / `organization` / `personal` wait on the `DM-D5` successor, so this filter's
 * option list stops there rather than anticipating a scope model that is
 * still contested (`DECISIONS.md` conflicts table).
 *
 * **Task 109.** SC 1.4.10's persistent-toolbar exception: `position:
 * sticky` (matching the visual reference's own `.dash-toolbar`) plus
 * `overflow-x: auto` on a single non-wrapping row, rather than letting
 * five controls wrap onto several lines at the reflow floor. This is the
 * declared exception `assertReflow`/`reflow.ts` already carves out for
 * "persistent toolbars" — see that file's own header. The attribute sits
 * on the row that actually scrolls; `reflowCheck.ts` resolves it with
 * `closest()`, so nesting that row inside the sticky shell is fine.
 *
 * **Controls row, then notes row.** Every control on the row is one
 * height — 40px `size="small"` selects (the reference's `select.bare` is
 * 40px) beside the 39px `Auto/Raw` toggle — and every one has a declared
 * width, so the row neither steps up and down nor reflows when a note
 * appears. Everything that used to hang *below* a control and drag it off
 * the centre line now renders on a second line under the whole row: the
 * resolved window (`TimeRangeDetails`), the refresh
 * disabled/paused/floored note, and `DM-D21`'s "the dashboard's range is
 * authoritative" sentence, which `DashboardView` used to float under the
 * toolbar on a negative margin. They belong to these controls, so they
 * live with them.
 */
import { Box, Typography } from '@mui/material';
import type { ReactNode } from 'react';

import { t } from '@shared/i18n';
import { Select } from '@shared/ui';
import type { SelectOption } from '@shared/ui';

import type { WidgetLayer } from '../dashboardRepository';
import { ResolutionControl, type Resolution } from '../explore/ResolutionControl';
import type { TimeRangeValue } from '../explore/timeRangeOptions';
import { TimeRangeDetails, TimeRangePicker } from '../explore/TimeRangePicker';
import type { CapabilityTier } from '../metricsRepository';

import { REFRESH_INTERVAL_SECONDS, type RefreshIntervalSeconds } from './refreshPolicy';

export type LayerFilterValue = 'all' | WidgetLayer;

const REFRESH_LABEL_KEY = {
  0: 'dashboardToolbarRefreshOff',
  10: 'dashboardToolbarRefresh10s',
  30: 'dashboardToolbarRefresh30s',
  60: 'dashboardToolbarRefresh1m',
  300: 'dashboardToolbarRefresh5m',
} as const;

const LAYER_LABEL_KEY = {
  all: 'dashboardToolbarLayerAll',
  'product-default': 'dashboardToolbarLayerProductDefault',
  shared: 'dashboardToolbarLayerShared',
} as const;

export interface DashboardToolbarProps {
  timeRange: TimeRangeValue;
  onTimeRangeChange: (value: TimeRangeValue) => void;
  /** `capabilities.tiers` — never a hardcoded preset list. */
  tiers: readonly CapabilityTier[];

  refreshSeconds: RefreshIntervalSeconds;
  onRefreshChange: (value: RefreshIntervalSeconds) => void;
  /** The requested interval after `DM-D17` flooring — may differ from `refreshSeconds`. */
  effectiveRefreshSeconds: number;
  /** Disabled for a `custom` (absolute) range — DM-D17. */
  refreshDisabled: boolean;
  /** True while `document.hidden` and a refresh would otherwise be running. */
  refreshPaused: boolean;

  resolution: Resolution;
  onResolutionChange: (value: Resolution) => void;
  /** `capabilities.features.forceRaw` — never hardcoded `true`. */
  forceRawAdvertised: boolean;

  layer: LayerFilterValue;
  onLayerChange: (value: LayerFilterValue) => void;

  /**
   * Dashboard-level actions, rendered at the end of the row behind a
   * spacer (task 131) — **Add widget**, **Reset defaults**, **Edit
   * layout**. The last two used to be a separate right-aligned row that
   * `DashboardGrid` rendered above the widgets; they are dashboard chrome,
   * so they belong on the dashboard's own toolbar.
   */
  actions?: ReactNode;
}

/** Declared widths, so a longer option label never resizes the row. */
const CONTROL_WIDTH = {
  timeRange: 168,
  refresh: 132,
  layer: 148,
} as const;

export function DashboardToolbar({
  timeRange,
  onTimeRangeChange,
  tiers,
  refreshSeconds,
  onRefreshChange,
  effectiveRefreshSeconds,
  refreshDisabled,
  refreshPaused,
  resolution,
  onResolutionChange,
  forceRawAdvertised,
  layer,
  onLayerChange,
  actions,
}: DashboardToolbarProps) {
  const refreshOptions: SelectOption[] = REFRESH_INTERVAL_SECONDS.map((seconds) => ({
    value: String(seconds),
    label: t(REFRESH_LABEL_KEY[seconds]),
  }));

  const layerOptions: SelectOption[] = (['all', 'product-default', 'shared'] as const).map(
    (value) => ({
      value,
      label: t(LAYER_LABEL_KEY[value]),
    }),
  );

  const flooredNote =
    !refreshDisabled && refreshSeconds !== 0 && effectiveRefreshSeconds !== refreshSeconds;

  const refreshNote = refreshDisabled
    ? t('dashboardToolbarRefreshDisabledNote')
    : refreshPaused
      ? t('dashboardToolbarRefreshPausedNote')
      : flooredNote
        ? t('dashboardToolbarRefreshFlooredNote', { seconds: effectiveRefreshSeconds })
        : null;

  return (
    <Box
      sx={{
        position: 'sticky',
        top: 0,
        zIndex: 1,
        backgroundColor: 'background.paper',
        borderBottom: '1px solid',
        borderColor: 'divider',
        pb: 1.5,
      }}
    >
      <Box
        // FE-110/`FE-D29`: this is the persistent-toolbar exception this
        // file's own header already names — the CI reflow gate's allowlist
        // entry for it. `tabIndex`: same reasoning as
        // `InstanceSelector.tsx`'s scrollable instance list (task 110's own
        // axe pass caught both the same way) — a scrollable region must be
        // focusable itself for Safari's keyboard scrolling, not merely
        // contain focusable children (`scrollable-region-focusable`).
        data-reflow-exception="persistent-toolbar"
        tabIndex={0}
        sx={{
          display: 'flex',
          flexWrap: 'nowrap',
          // Task 131 — one dense row (reference `.dash-toolbar`). Was
          // `flex-start`, which top-aligned controls of differing heights.
          alignItems: 'center',
          gap: '10px',
          overflowX: 'auto',
          // `overflow-x: auto` computes `overflow-y` to `auto` as well, so
          // this row clips vertically whether or not it is asked to. Two
          // things sit outside a control's own box and were being cut: the
          // outlined selects' floating labels (~9px above the input) and
          // the 4px focus ring `createSmTheme` paints. The padding is the
          // room they need; without it the row has to be tall enough by
          // accident, which is what the misaligned pre-fix layout was.
          py: '10px',
          '& > *': { flexShrink: 0 },
        }}
      >
        <Box sx={{ width: CONTROL_WIDTH.timeRange }}>
          <TimeRangePicker
            value={timeRange}
            onChange={onTimeRangeChange}
            tiers={tiers}
            size="small"
            showDetails={false}
          />
        </Box>

        <Box sx={{ width: CONTROL_WIDTH.refresh }}>
          <Select
            label={t('dashboardToolbarRefreshLabel')}
            value={String(refreshSeconds)}
            onChange={(value) => onRefreshChange(Number(value) as RefreshIntervalSeconds)}
            options={refreshOptions}
            disabled={refreshDisabled}
            size="small"
          />
        </Box>

        <ResolutionControl
          value={resolution}
          onChange={onResolutionChange}
          forceRawAdvertised={forceRawAdvertised}
        />

        <Box sx={{ width: CONTROL_WIDTH.layer }}>
          <Select
            label={t('dashboardToolbarLayerLabel')}
            value={layer}
            onChange={(value) => onLayerChange(value as LayerFilterValue)}
            options={layerOptions}
            size="small"
          />
        </Box>

        {/* Spacer, so the actions sit at the far end of the row however wide
            the controls are. `flexShrink` is reset because the container
            sets `'& > *': { flexShrink: 0 }` for the controls' benefit. */}
        {actions !== undefined && (
          <>
            <Box sx={{ flex: '1 1 auto', flexShrink: 1, minWidth: 0 }} />
            <Box sx={{ display: 'flex', alignItems: 'center', gap: '8px' }}>{actions}</Box>
          </>
        )}
      </Box>

      <Box
        sx={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'baseline',
          columnGap: '10px',
          rowGap: '4px',
          mt: '8px',
        }}
      >
        <TimeRangeDetails value={timeRange} onChange={onTimeRangeChange} />
        {refreshNote !== null && (
          <Typography variant="caption" color="text.secondary">
            {refreshNote}
          </Typography>
        )}
        <Typography variant="caption" color="text.secondary">
          {t('dashboardToolbarAuthoritativeNote')}
        </Typography>
      </Box>
    </Box>
  );
}
