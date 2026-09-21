/**
 * `<DashboardGrid>` (task 086/087, Dec `DM-D6` `SM-D31` `DM-D18`, Design
 * §12/§17, `dynamic-metrics` §8/§18, Conflict C7). Lays out `DashboardView`'s
 * already layer-filtered, `order`-sorted widget list onto a 12-column grid
 * — **hidden defaults included, one per slot** — and owns "Edit layout"
 * (move-earlier/move-later), "Hide"/"Restore", and "Reset defaults", all
 * persisted via `dashboardRepository`.
 *
 * **Visual position (`{x,y,w,h}`) and traversal order are independent
 * axes, on purpose** (§18: *"an explicit order… must not depend on pixel
 * position"*). This grid places each widget with explicit CSS Grid line
 * coordinates (`gridColumn`/`gridRow` from `{x,y,w,h}`) while the DOM
 * itself is authored in `order` sequence (the `widgets` prop arrives
 * pre-sorted that way) — CSS's own `order` property is deliberately never
 * used for this, because it changes VISUAL order while leaving DOM/focus
 * order untouched, which is the opposite of what's needed: keyboard and
 * screen-reader traversal must follow the stated `order` field regardless
 * of where a widget happens to sit on screen.
 *
 * **`layout` is the FULL dashboard layout, not just the visible subset**
 * — `order` is a dashboard-global sequence (`WidgetPlacement.order`,
 * `dashboardRepository.ts`), so a move must read/write real neighbours in
 * that full sequence's terms even though only the layer-visible widgets
 * can be moved through this UI. Swapping is scoped to adjacent VISIBLE
 * **and unhidden** widgets (mirroring the visual reference's own
 * `moveWidget`) — a hidden default renders as `HiddenDefaultWidget`, with
 * no move controls of its own, so `movableWidgets` (§087) is the subset
 * `moveWidget`/`canMove*` actually navigate; a widget's array-adjacent
 * neighbour is not necessarily its move-adjacent one once a hidden default
 * sits between them.
 *
 * **Hide/Restore.** A `product-default` widget's `hideDefault(…, true)`
 * (from `WidgetFrame`'s own menu) and a `HiddenDefaultWidget`'s `Restore`
 * (`hideDefault(…, false)`) share one mutation — both just flip the same
 * boolean for one widget id. **Reset defaults** (`resetDefaults`) restores
 * every hidden default at once; design §12 gives it no narrower scope than
 * "restores the package layer."
 *
 * **Task 109 — responsive columns.** 12 columns at `lg` and up (unchanged:
 * the explicit `{x,y,w,h}` placement below still drives both visual
 * position and `Edit layout`'s move controls). 6 at `md`, 1 (`sm` and
 * below) — at both of those, this file stops asserting `x`/`y` and lets
 * the grid auto-place each item into the next row, because the DOM itself
 * is already in `order` sequence (`widgets` arrives pre-sorted — see
 * above); the header's own "must not depend on pixel position" rule is
 * exactly what dropping the explicit position at those two widths keeps.
 * `w` still bounds the column span at `md` (clamped to the 6 columns that
 * exist there) so a widget authored to be wide doesn't shrink to the same
 * single column as a narrow one.
 */
import { Box } from '@mui/material';
import type { Theme } from '@mui/material/styles';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useMemo } from 'react';

import { queryKeys } from '@shared/api/queryKeys';

import type { WidgetConfig, WidgetPlacement } from '../dashboardRepository';
import type { Resolution } from '../explore/ResolutionControl';
import { useMetrics } from '../MetricsProvider';

import { HiddenDefaultWidget } from './HiddenDefaultWidget';
import { WidgetFrame } from './WidgetFrame';

export interface DashboardGridProps {
  dashboardId: string;
  /** Pre-filtered by layer and pre-sorted by `order` — `DashboardView`'s own `visibleWidgets`. Hidden defaults ARE included (rendered as `HiddenDefaultWidget`). */
  widgets: readonly WidgetConfig[];
  /** The FULL dashboard layout — see this file's header on why. */
  layout: readonly WidgetPlacement[];
  absoluteRange: { from: string; to: string };
  resolution: Resolution;
  refreshSeconds: number;
  /** Owned by `DashboardView` since task 131 — the "Edit layout" toggle lives on the toolbar, not here. */
  editing: boolean;
}

function placementFor(
  layout: readonly WidgetPlacement[],
  widgetId: string,
): WidgetPlacement | undefined {
  return layout.find((placement) => placement.widgetId === widgetId);
}

const DASHBOARD_GRID_COLUMNS_MD = 6;
/** Reference `.grid12` — one 150px row per unit of `placement.h`, 14px apart. */
const DASHBOARD_GRID_ROW_HEIGHT_PX = 150;
const DASHBOARD_GRID_GAP_PX = 14;

/**
 * `lg`+ keeps the explicit `{x,y,w,h}` placement (unchanged). Below that,
 * no `gridColumn` start or `gridRow` is set at all — leaving both to the
 * grid's own auto-placement, which walks the DOM in the order this file's
 * caller already renders it (`order`), never the discarded `x`/`y`
 * (header comment above).
 */
function responsiveGridItemSx(placement: WidgetPlacement) {
  return (theme: Theme) => ({
    minWidth: 0,
    gridColumn: '1 / -1',
    [theme.breakpoints.only('md')]: {
      gridColumn: `span ${String(Math.min(placement.w, DASHBOARD_GRID_COLUMNS_MD))}`,
    },
    [theme.breakpoints.up('lg')]: {
      gridColumn: `${String(placement.x + 1)} / span ${String(placement.w)}`,
      gridRow: `${String(placement.y + 1)} / span ${String(placement.h)}`,
    },
  });
}

export function DashboardGrid({
  dashboardId,
  widgets,
  layout,
  absoluteRange,
  resolution,
  refreshSeconds,
  editing,
}: DashboardGridProps) {
  const { dashboardRepository } = useMetrics();
  const queryClient = useQueryClient();

  function invalidateDashboard(): void {
    void queryClient.invalidateQueries({ queryKey: queryKeys.dashboards.detail(dashboardId) });
  }

  const setLayoutMutation = useMutation({
    mutationFn: (nextLayout: readonly WidgetPlacement[]) =>
      dashboardRepository.setLayout(dashboardId, nextLayout),
    onSuccess: invalidateDashboard,
  });

  // One mutation for both directions — hiding (from `WidgetFrame`'s menu)
  // and restoring (from `HiddenDefaultWidget`) are the same PATCH with the
  // opposite boolean.
  const hideMutation = useMutation({
    mutationFn: ({ widgetId, hidden }: { widgetId: string; hidden: boolean }) =>
      dashboardRepository.hideDefault(dashboardId, widgetId, hidden),
    onSuccess: invalidateDashboard,
  });

  // Only a live (non-hidden) widget has move controls — a hidden default
  // renders as `HiddenDefaultWidget` with none of its own — so adjacency
  // for move-earlier/move-later is resolved against this subset, not the
  // full (rendered) `widgets` list, or a move could silently swap order
  // with a hidden neighbour the operator never saw move.
  const movableWidgets = useMemo(
    () => widgets.filter((widget) => widget.hidden !== true),
    [widgets],
  );

  function moveWidget(widgetId: string, direction: -1 | 1): void {
    const currentIndex = movableWidgets.findIndex((widget) => widget.id === widgetId);
    const neighborIndex = currentIndex + direction;
    if (currentIndex < 0 || neighborIndex < 0 || neighborIndex >= movableWidgets.length) {
      return;
    }
    const neighborId = movableWidgets[neighborIndex]?.id;
    if (neighborId === undefined) {
      return;
    }
    const current = placementFor(layout, widgetId);
    const neighbor = placementFor(layout, neighborId);
    if (current === undefined || neighbor === undefined) {
      return;
    }
    const nextLayout = layout.map((placement) => {
      if (placement.widgetId === widgetId) {
        return { ...placement, order: neighbor.order };
      }
      if (placement.widgetId === neighborId) {
        return { ...placement, order: current.order };
      }
      return placement;
    });
    setLayoutMutation.mutate(nextLayout);
  }

  return (
    <Box>
      {/* Task 131 — "Reset defaults" and "Edit layout" used to be a
          right-aligned row here. They are dashboard chrome, not grid
          chrome, so they moved onto `DashboardToolbar`'s single row and
          `editing` is now a prop rather than this component's own state. */}
      <Box
        sx={(theme) => ({
          display: 'grid',
          gridTemplateColumns: 'repeat(1, 1fr)',
          // Reference `.grid12`: `gap: 14px`.
          gap: `${String(DASHBOARD_GRID_GAP_PX)}px`,
          [theme.breakpoints.only('md')]: {
            gridTemplateColumns: `repeat(${String(DASHBOARD_GRID_COLUMNS_MD)}, 1fr)`,
          },
          [theme.breakpoints.up('lg')]: {
            gridTemplateColumns: 'repeat(12, 1fr)',
            // Task 129 — `placement.h` finally means a height. Without a
            // sized row track, a `gridRow: span 2` widget was just "two
            // content-sized rows", so `h` changed nothing on screen and
            // `WidgetFrame` fell back to a flat 220px body regardless.
            // `WidgetFrame.widgetBodyHeight` derives from these same two
            // numbers, so the card and its cell agree.
            // `minmax(150px, auto)`, not a flat `150px`: `h` still means
            // "this many 150px rows", but a widget whose content genuinely
            // needs more never gets CLIPPED by `Paper`'s `fill`
            // (`overflow: hidden`). Found by task 132's screenshot pass —
            // `GaugeMark`'s arc plus the card header exceed a single 150px
            // row, so a flat track cut the bottom off the gauge. The
            // reference does not hit this because its own `.grid12` rows
            // are content-sized; ours carry `h` explicitly, so they need
            // the floor AND the give.
            gridAutoRows: `minmax(${String(DASHBOARD_GRID_ROW_HEIGHT_PX)}px, auto)`,
          },
        })}
      >
        {widgets.map((widget) => {
          const placement = placementFor(layout, widget.id);
          if (placement === undefined) {
            return null;
          }
          const gridSx = responsiveGridItemSx(placement);

          if (widget.hidden === true) {
            return (
              <Box key={widget.id} sx={gridSx}>
                <HiddenDefaultWidget
                  title={widget.title}
                  onRestore={() => hideMutation.mutate({ widgetId: widget.id, hidden: false })}
                  restorePending={hideMutation.isPending}
                />
              </Box>
            );
          }

          const movableIndex = movableWidgets.findIndex((movable) => movable.id === widget.id);
          return (
            <Box key={widget.id} sx={gridSx}>
              <WidgetFrame
                widget={widget}
                placement={placement}
                absoluteRange={absoluteRange}
                resolution={resolution}
                refreshSeconds={refreshSeconds}
                editing={editing}
                onMoveEarlier={() => moveWidget(widget.id, -1)}
                onMoveLater={() => moveWidget(widget.id, 1)}
                canMoveEarlier={movableIndex > 0 && !setLayoutMutation.isPending}
                canMoveLater={
                  movableIndex < movableWidgets.length - 1 && !setLayoutMutation.isPending
                }
                onHide={() => hideMutation.mutate({ widgetId: widget.id, hidden: true })}
                hidePending={hideMutation.isPending}
              />
            </Box>
          );
        })}
      </Box>
    </Box>
  );
}
