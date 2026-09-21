/**
 * `<MonitoringTabs>` (task 130) — the Monitoring category's own sub-tab
 * bar, **Dashboards** and **Explore**. This is the reference's `.itabs`
 * row (`reference/app/dashboards.jsx` / `explore.jsx`, which render the
 * same two-entry bar on both screens).
 *
 * It is also the actual fix for plan `126`'s second defect, *"there is no
 * way to add a metric to a dashboard"*. Explore was reachable only from
 * two EMPTY states — `DashboardView`'s layer-filtered empty state and
 * `MonitoringFirstRun` — so the moment a dashboard had a single widget on
 * it, the screen that builds widgets became unreachable. A populated board
 * now carries a permanent route to it, and the ad-hoc "← Monitoring" links
 * the two screens used to carry become redundant: the bar navigates both
 * ways.
 *
 * `end: true` on the Dashboards tab is load-bearing, not decoration.
 * `/monitoring` is a path prefix of `/monitoring/explore`, and `RouteTabs`
 * otherwise treats a prefix as a match and returns the FIRST one — so
 * without it both tabs read as active on Explore, and the one that wins is
 * the wrong one. `RouteTab.end` was added by this task for exactly this
 * (see its own doc; it mirrors react-router's `NavLink` `end`).
 *
 * Deliberately NOT wrapped in `InstanceShell`, which is what the reference
 * does: that would need all five instance repositories on these routes and
 * would create a `@features/monitoring` → `@features/instance` import
 * against the existing edge in the other direction — an
 * `eslint-plugin-boundaries` cycle. Plan `126` names this out of scope;
 * this bar gets the reachability without it.
 *
 * Hrefs are hand-written rather than imported from `@app/routes`, which
 * feature code may not import (module boundaries) — the same pattern
 * `DashboardView.tsx` and `ExplorePage.tsx` already use.
 */
import { Box } from '@mui/material';
import type { ReactNode } from 'react';

import { t } from '@shared/i18n';
import { InfoTip, RouteTabs } from '@shared/ui';
import type { RouteTab } from '@shared/ui';

export interface MonitoringTabsProps {
  solutionId: string;
  instanceId: string;
  /** The page body — `RouteTabs` renders it inside the `tabpanel`. */
  children: ReactNode;
  /**
   * Optional explanation shown as an `InfoTip` at the end of the tab row
   * (task 131). `DashboardView` uses it for the landing-vs-index note,
   * which used to be a permanent full-width `Alert` above the toolbar —
   * a standing banner for a fact an operator needs once.
   */
  note?: string;
}

export function monitoringTabs(solutionId: string, instanceId: string): RouteTab[] {
  const base = `/solutions/${solutionId}/instances/${instanceId}/monitoring`;
  return [
    { to: base, label: t('monitoringTabDashboards'), end: true },
    { to: `${base}/explore`, label: t('monitoringTabExplore') },
  ];
}

export function MonitoringTabs({ solutionId, instanceId, children, note }: MonitoringTabsProps) {
  return (
    <RouteTabs
      tabs={monitoringTabs(solutionId, instanceId)}
      {...(note !== undefined ? { trailing: <InfoTip tip={note} /> } : {})}
    >
      {/* The gap under the bar lives here rather than on each page, so
          both screens sit the same distance below it. `shared/ui`'s
          `Stack` takes no `sx`, which is the other reason this is not the
          caller's job. */}
      <Box sx={{ mt: '14px' }}>{children}</Box>
    </RouteTabs>
  );
}
