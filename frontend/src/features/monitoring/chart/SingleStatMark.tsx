/**
 * `mark: 'single-stat'` (`DM-D12`, `DM-D22`, `dynamic-metrics` §7, UI
 * §A.10.7: "a large value with the unit beneath and an optional delta").
 * No chart, no `echarts` import — see `GaugeMark.tsx`'s header for why
 * neither single-value mark goes through `ChartRenderer.tsx`.
 *
 * `delta` is caller-supplied, never computed here: line rendering's own
 * obligation 3 (`dynamic-metrics` §11.2, task 071/073) already rules out
 * computing a delta across a gap, and this component only ever sees the
 * one resolved point — only a caller holding both endpoints, and knowing
 * whether a gap separates them, could compute one safely.
 */
import { Box, Typography } from '@mui/material';

import type { ChartModel } from '@contracts/metrics/types';
import { formatUnitValue } from '@shared/format';
import { t } from '@shared/i18n';
import { EmptyState } from '@shared/ui';

import { resolveMarkValue } from './markValue';

export interface SingleStatMarkProps {
  model: ChartModel;
  /** Caller-supplied only — see header. `>= 0` renders with an up marker, negative with a down marker. */
  delta?: number;
}

export function SingleStatMark({ model, delta }: SingleStatMarkProps) {
  const resolved = resolveMarkValue(model);

  if (resolved === null) {
    return <EmptyState title={t('markNoDataTitle')} body={t('markNoDataBody')} />;
  }

  const { point, unit } = resolved;

  return (
    <Box sx={{ textAlign: 'center', py: 2 }}>
      <Typography variant="h3" sx={{ fontWeight: 600, letterSpacing: '-0.02em' }}>
        {formatUnitValue(point.value, unit)}
      </Typography>
      {delta !== undefined && (
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5 }}>
          {delta >= 0
            ? t('singleStatMarkDeltaUp', { value: formatUnitValue(Math.abs(delta), unit) })
            : t('singleStatMarkDeltaDown', { value: formatUnitValue(Math.abs(delta), unit) })}
        </Typography>
      )}
    </Box>
  );
}
