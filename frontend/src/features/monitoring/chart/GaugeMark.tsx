/**
 * `mark: 'gauge'` (`DM-D12`, `DM-D22`, `dynamic-metrics` §7, UI §A.10.7:
 * "a semicircular arc, filled proportionally, turning warn-coloured past a
 * threshold, with the value and unit beneath"). Drawn as plain SVG, not
 * through ECharts — `ChartRenderer.tsx` is the app's one `echarts` import
 * site (`FE-D16`), and a semicircular arc has no axis, no series-over-time,
 * no legend, none of the machinery `DM-D13`/§12 actually specify (theming,
 * canvas renderer, gaps, tooltip). The visual reference
 * (`docs/design/frontend/reference/app/charts.jsx`'s own `GaugeMark`) draws
 * it the same way, for the same reason.
 *
 * Renders the aggregated series' **last point** (`markValue.ts`) — `DM-D22`
 * requires the caller to have already forced aggregation to exactly one
 * value; this component does not re-validate that, only renders what it is
 * given.
 */
import { Box, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';

import type { ChartModel, ChartThreshold } from '@contracts/metrics/types';
import { formatUnitValue } from '@shared/format';
import { t } from '@shared/i18n';
import { EmptyState } from '@shared/ui';

import { resolveMarkValue } from './markValue';

export interface GaugeMarkProps {
  model: ChartModel;
  /** Defaults to 0 — the visual reference's own default. */
  min?: number;
  /** Defaults to 100 — a caller with a better bound (a `ratio` metric's 1, or an observed max) overrides it. */
  max?: number;
  /** Crossing a threshold turns the arc the warn colour — the same convention `ChartRenderer`'s `markLine` thresholds use. */
  thresholds?: readonly ChartThreshold[];
}

const ARC_PATH = 'M16 88 A64 64 0 0 1 144 88';
const ARC_RADIUS = 64;
const ARC_CIRCUMFERENCE = Math.PI * ARC_RADIUS;

export function GaugeMark({ model, min = 0, max = 100, thresholds = [] }: GaugeMarkProps) {
  const theme = useTheme();
  const resolved = resolveMarkValue(model);

  if (resolved === null) {
    return <EmptyState title={t('markNoDataTitle')} body={t('markNoDataBody')} />;
  }

  const { point, unit } = resolved;
  const fraction = Math.max(0, Math.min(1, (point.value - min) / (max - min)));
  const crossedThreshold = thresholds.some((threshold) => point.value >= threshold.value);
  const arcColor = crossedThreshold ? theme.palette.tone.warn.main : theme.palette.primary.main;
  const formattedValue = formatUnitValue(point.value, unit);

  return (
    <Box sx={{ textAlign: 'center', py: 1 }}>
      <svg
        width={160}
        height={96}
        viewBox="0 0 160 96"
        role="img"
        aria-label={t('gaugeMarkAriaLabel', { value: formattedValue })}
      >
        <path d={ARC_PATH} fill="none" stroke={theme.palette.action.hover} strokeWidth={12} strokeLinecap="round" />
        <path
          d={ARC_PATH}
          fill="none"
          stroke={arcColor}
          strokeWidth={12}
          strokeLinecap="round"
          strokeDasharray={`${fraction * ARC_CIRCUMFERENCE} ${ARC_CIRCUMFERENCE}`}
        />
      </svg>
      <Box sx={{ mt: '-30px' }}>
        <Typography variant="h4">{formattedValue}</Typography>
      </Box>
    </Box>
  );
}
