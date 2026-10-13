/**
 * `DashboardRepository` (task 033, §6.0.5, §9.2, UI §A.11; `WidgetConfig`
 * updated by task 084). CONTRACT: TBD — the ENTIRE document has no shape
 * (§16 Q15): envelope, the hidden-default representation, dashboard-level
 * `range`/`refresh`, whether `{x,y,w,h,order}` live on the widget or a
 * placement entry, a list route, and the reset payload are all invented
 * here. `list()` is intentionally ABSENT — the contract has no list route;
 * do not invent one.
 *
 * **`WidgetConfig` is `widgetConfig.ts`'s real `§7` document plus `layer`/
 * `hidden`.** Task 033 predates the real schema (task 084) and stubbed a
 * placeholder (`id`/`title`/`mark`/`metric`/`layer`/`hidden`) shaped just
 * enough to prototype a dashboard grid — keeping it once the real schema
 * existed would be exactly the two-copies-that-can-drift this codebase's
 * own conventions warn against elsewhere (`chartStateTags.ts`,
 * `buildQuery.ts`). `layer`/`hidden` themselves stay HERE rather than
 * folding into `WidgetConfigV1` — see that file's own header on why they
 * are dashboard-placement metadata, not part of the portable document.
 * `'product-default'`/`'shared'` (not task 033's `'package-baseline'`/
 * `'operator'`) match the CURRENT design vocabulary (design §12, FE-087,
 * Conflict C7) — corrected here for the same reason.
 */
import type { PlaneClient } from '@shared/api';

import type { WidgetConfigV1 } from './widget/widgetConfig';

export interface WidgetPlacement {
  widgetId: string;
  x: number;
  y: number;
  w: number;
  h: number;
  order: number;
}

/** Design §12 / Conflict C7's vocabulary — a package-shipped default vs. an operator save. */
export type WidgetLayer = 'product-default' | 'shared';

export interface WidgetConfig extends WidgetConfigV1 {
  layer: WidgetLayer;
  /** A hidden `product-default` — never set on a `shared` widget. Representation is FE-087's own open item (§16 Q15). */
  hidden?: boolean;
}

export interface DashboardDoc {
  id: string;
  schemaVersion: number;
  /**
   * Optimistic-concurrency token (`console.yaml`'s `Dashboard.revision`,
   * REQUIRED on the wire, `0` while nothing is stored yet). Every write
   * below takes the `revision` the caller read this document at and sends
   * it back via `X-SM-Dashboard-Revision` (W2-07) — there is no
   * last-writer-wins form. A stale value is `409 CONFLICT` (surfaced as an
   * `ApiError` with `status === 409`); the caller re-fetches and retries.
   */
  revision: number;
  widgets: readonly WidgetConfig[];
  layout: readonly WidgetPlacement[];
  range?: { preset: string };
  refresh?: { intervalSeconds: number | null };
}

export interface SaveWidgetResult {
  widget: WidgetConfig;
  /** Server-authoritative (`FE-D13`) — never client-fabricated; the caller's toast quotes this. */
  correlationId: string;
}

export interface DashboardRepository {
  get(dashboardId: string, signal?: AbortSignal): Promise<DashboardDoc>;
  /** `revision` is the value `get()` returned this dashboard's document at. */
  saveWidget(dashboardId: string, widget: WidgetConfig, revision: number): Promise<SaveWidgetResult>;
  setLayout(dashboardId: string, layout: readonly WidgetPlacement[], revision: number): Promise<void>;
  hideDefault(dashboardId: string, widgetId: string, hidden: boolean, revision: number): Promise<void>;
  resetDefaults(dashboardId: string, revision: number): Promise<DashboardDoc>;
  // list(): intentionally ABSENT — see header.
}

/**
 * Invented (§16 Q15) — the contract has no route to look up an instance's
 * dashboard id, and `SM-D31` gives each instance exactly one (per-instance,
 * shared) dashboard. Deterministic from `instanceId` alone so `ExplorePage`
 * (task 084) and a future `DashboardView` (task 085) agree without either
 * one calling the other.
 */
export function defaultDashboardId(instanceId: string): string {
  return `db_${instanceId}`;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

/** `console.yaml`'s `DashboardRevision` parameter — REQUIRED on every write (W2-07). */
const DASHBOARD_REVISION_HEADER = 'X-SM-Dashboard-Revision';

function revisionHeaders(revision: number): { headers: Record<string, string> } {
  return { headers: { [DASHBOARD_REVISION_HEADER]: String(revision) } };
}

export function createDashboardRepository(consoleClient: PlaneClient): DashboardRepository {
  return {
    get(dashboardId, signal) {
      return consoleClient.get<DashboardDoc>(
        `/dashboards/${encodeURIComponent(dashboardId)}`,
        opts(signal),
      );
    },
    saveWidget(dashboardId, widget, revision) {
      // `postWithCorrelation`, not `post` — a save reports its own outcome
      // (`FE-D13`), and `ToastInput.correlationId` is required, never
      // client-fabricated.
      return consoleClient
        .postWithCorrelation<WidgetConfig>(
          `/dashboards/${encodeURIComponent(dashboardId)}/widgets`,
          widget,
          revisionHeaders(revision),
        )
        .then(({ data, correlationId }) => ({ widget: data, correlationId }));
    },
    setLayout(dashboardId, layout, revision) {
      return consoleClient.put<void>(
        `/dashboards/${encodeURIComponent(dashboardId)}/layout`,
        { layout },
        revisionHeaders(revision),
      );
    },
    hideDefault(dashboardId, widgetId, hidden, revision) {
      return consoleClient.patch<void>(
        `/dashboards/${encodeURIComponent(dashboardId)}/widgets/${encodeURIComponent(widgetId)}`,
        { hidden },
        revisionHeaders(revision),
      );
    },
    resetDefaults(dashboardId, revision) {
      return consoleClient.post<DashboardDoc>(
        `/dashboards/${encodeURIComponent(dashboardId)}/reset`,
        undefined,
        revisionHeaders(revision),
      );
    },
  };
}
