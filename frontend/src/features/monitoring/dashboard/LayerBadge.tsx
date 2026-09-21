/**
 * `<LayerBadge>` (task 086, Dec `DM-D5`/`SM-D31`, Design §12 — the
 * `dynamic-metrics` §9.1 component tree names it as `WidgetFrame`'s own
 * chrome: *"`LayerBadge` + `LayerFilter` make provenance visible… the
 * three layers have different upgrade behaviour — without a badge an
 * operator cannot tell which of their widgets a package upgrade will
 * replace."*
 *
 * **Two layers only** — same posture `DashboardToolbar`'s own `LayerFilter`
 * already takes (task 085): `entity`/`organization`/`personal` wait on the `DM-D5` successor
 * ratification.
 *
 * Reuses the exact label copy `DashboardView`'s own (now-retired) inline
 * widget-list rows used — one copy, not two that can drift.
 */
import { t } from '@shared/i18n';
import { Chip } from '@shared/ui';

import type { WidgetLayer } from '../dashboardRepository';

export interface LayerBadgeProps {
  layer: WidgetLayer;
}

export function LayerBadge({ layer }: LayerBadgeProps) {
  return (
    <Chip
      label={layer === 'shared' ? t('dashboardViewLayerShared') : t('dashboardViewLayerProductDefault')}
      tone={layer === 'shared' ? 'info' : 'neutral'}
    />
  );
}
