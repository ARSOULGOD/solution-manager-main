/**
 * Step 4's real control (task 080, Dec `DM-D8` `DM-D22`, `dynamic-metrics`
 * §5.7/§9.1, UI §A.10 — mirrors the reference's `<select className="bare">`
 * in `docs/design/frontend/reference/app/explore.jsx`, extended to the
 * full sum/avg/min/max set `capabilities.aggregations` and `DM-D8`'s table
 * actually specify, rather than the reference's sum/avg-only options).
 *
 * **`acrossReplicas` is not a control here.** Every JSON example in
 * `dynamic-metrics` design.md sends `"acrossReplicas": "auto"`; no
 * separate value is ever shown, and the component tree (§9.1) lists only
 * `<AggregationControl>` (for `acrossInstances`) and `<GroupByControl>` —
 * no third replica-aggregation control. Replicas are folded into each
 * instance's line type-awarely by default (`DM-D4` §4.4); `seriesBy:
 * "replica"` (task 078, already built) is the only override, and it
 * bypasses replica aggregation entirely rather than choosing a function
 * for it. Wherever a `QueryRequest` is eventually built, `acrossReplicas`
 * stays the literal `"auto"` — flagged in the task report, not silently
 * decided.
 *
 * Presentational and controlled, same split as `InstanceSelector`/
 * `MetricSelector`. `permittedAggregationFunctions` (task 080's own
 * `aggregationOptions.ts`) supplies the type-aware, capability-intersected
 * option set; `marksRequiringAggregation` (task 076,
 * `contracts/metrics/markAggregation.ts`) supplies `DM-D22`'s forcing
 * rule as-is rather than re-implementing it.
 *
 * **Deliberate correction of the reference.** `explore.jsx` disables the
 * whole `<select>` when forced (`disabled={forceAgg}`) while leaving its
 * value stuck at `"none"` — a forced-but-still-`none` state. This control
 * instead disables only the `"none"` *option* and corrects the value with
 * an effect, so a forced draft is never left sitting in the exact shape
 * `DM-D22` exists to refuse.
 *
 * Forces on `mark` alone, not on instance count — matches `DM-D22`'s own
 * wording ("forces a non-`none` value when either mark is selected") and
 * avoids a flicker state machine where dropping back to one selected
 * instance would need to un-force. The count-conditioned *refusal* check
 * (`evaluateMarkAggregation`) stays `markAggregation.ts`'s job for
 * wherever a query is eventually issued.
 *
 * ⚠️ **No mark toggle exists yet** (`ExplorePage`'s own header comment) —
 * `mark` is always `"line"` there today, so the forcing branch below is
 * real, type-checked, unit-tested code with no live caller that can
 * trigger it. Flagged as a residual for whichever task adds the result
 * column's mark toggle, not silently decided.
 */
import { Box, Typography } from '@mui/material';
import { useEffect } from 'react';

import { marksRequiringAggregation } from '@contracts/metrics/markAggregation';
import type { Mark } from '@contracts/metrics/types';
import { t } from '@shared/i18n';
import type { MessageKey } from '@shared/i18n';
import type { MetricType } from '@shared/testdoubles/data/metrics';
import { Select } from '@shared/ui';
import type { SelectOption } from '@shared/ui';

import type { AcrossInstances } from '../metricsRepository';

import type { AggregationFunction } from './aggregationOptions';
import { permittedAggregationFunctions } from './aggregationOptions';

const AGG_LABEL_KEY = {
  sum: 'aggregationControlSumLabel',
  avg: 'aggregationControlAvgLabel',
  min: 'aggregationControlMinLabel',
  max: 'aggregationControlMaxLabel',
} as const satisfies Record<AggregationFunction, MessageKey>;

export interface AggregationControlProps {
  value: AcrossInstances;
  onChange: (value: AcrossInstances) => void;
  metricType: MetricType | null;
  /** `capabilities.aggregations` — never a hardcoded option list. */
  capabilityAggregations: readonly AggregationFunction[];
  /** Always `'line'` until a mark toggle exists (see this file's header comment). */
  mark: Mark;
  /** True while this step is gate-locked (task 077's `ControlStep` contract). */
  disabled?: boolean;
}

export function AggregationControl({
  value,
  onChange,
  metricType,
  capabilityAggregations,
  mark,
  disabled = false,
}: AggregationControlProps) {
  const permitted = permittedAggregationFunctions(metricType, capabilityAggregations);
  const forced = marksRequiringAggregation(mark);

  useEffect(() => {
    if (forced && value === 'none' && permitted.length > 0) {
      onChange(permitted[0] as AggregationFunction);
    }
  }, [forced, value, permitted, onChange]);

  const options: SelectOption[] = [
    { value: 'none', label: t('aggregationControlNoneLabel'), disabled: forced },
    ...permitted.map((fn) => ({ value: fn, label: t(AGG_LABEL_KEY[fn]) })),
  ];

  return (
    <Box>
      <Select
        label={t('aggregationControlFieldLabel')}
        value={value}
        onChange={(next) => onChange(next as AcrossInstances)}
        options={options}
        disabled={disabled}
      />
      {forced && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: '6px' }}>
          {t('aggregationControlForcedCaption', { mark })}
        </Typography>
      )}
      {metricType === 'histogram' && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: '6px' }}>
          {t('aggregationControlHistogramUnsupportedCaption')}
        </Typography>
      )}
    </Box>
  );
}
