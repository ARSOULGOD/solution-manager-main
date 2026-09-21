/**
 * `<TimeRangePicker>` (task 081, Dec `DM-D10` `B/D15` 🔒, `dynamic-metrics`
 * §5.4/§9.1, UI §A.10 — mirrors the reference's `<select className="bare">`
 * range picker in `docs/design/frontend/reference/app/{explore,
 * dashboards}.jsx`, extended with a real custom-range escape hatch neither
 * reference screen builds).
 *
 * The option list is `permittedPresets(capabilities.tiers)` (this task's
 * own `timeRangeOptions.ts`) plus a `'custom'` entry that is never
 * tier-gated — an operator can always request an absolute window; the
 * server truncates or 422s beyond what a tier can serve (task 082's
 * territory).
 *
 * **`B/D15` 🔒 — UTC is canonical on the wire; the zone is always
 * labelled.** The resolved window renders through `shared/format/time.ts`
 * (task 007), in the browser's own resolved zone, with that zone spelled
 * out in the caption — never a bare local time that leaves an operator
 * guessing which zone they're reading.
 *
 * Custom bounds are two native `datetime-local` inputs, built directly on
 * MUI's own `TextField` rather than the shared `TextField` wrapper (task
 * 012) — that wrapper's `type` is hardcoded to `text`/`password` and
 * widening its public type for this one call site isn't warranted, the
 * same "bespoke MUI primitive for one use site" precedent `GroupByControl`
 * and `InstanceSelector`'s own `Meter` already set. `datetime-local`
 * reads/writes the browser's LOCAL wall clock with no zone in the string
 * (matching `displayTimeZone`'s own default); this component converts to
 * UTC ISO at the boundary, going in and out.
 *
 * Presentational and controlled, same split as every other real Explore
 * control. Switching to `'custom'` seeds its bounds from whatever window
 * is currently resolved, rather than a jarring fixed default, so the
 * operator's current view doesn't jump when they open the escape hatch.
 *
 * **Two components, one default composition.** The custom-range inputs and
 * the resolved-window caption are `<TimeRangeDetails>`, which
 * `<TimeRangePicker>` renders beneath its own select unless told not to
 * (`showDetails={false}`). Explore stacks them, which is what its control
 * column wants. `DashboardToolbar` does not: its controls are one dense
 * row (reference `.dash-toolbar`), and a caption stacked under the select
 * both stretched the select to the caption's width and pushed it off the
 * row's centre line. There it renders the picker in the row and
 * `<TimeRangeDetails>` on the line below — the same two pieces, composed
 * for the layout instead of duplicated for it.
 */
import { Box, TextField as MuiTextField, Typography } from '@mui/material';
import { useEffect } from 'react';

import { formatDateTime, timeZoneLabel } from '@shared/format/time';
import { t } from '@shared/i18n';
import type { MessageKey } from '@shared/i18n';
import { Select } from '@shared/ui';
import type { SelectOption } from '@shared/ui';

import type { CapabilityTier } from '../metricsRepository';

import type { TimeRangePresetKey, TimeRangeValue } from './timeRangeOptions';
import { permittedPresets, resolveTimeRange } from './timeRangeOptions';

const PRESET_LABEL_KEY = {
  '15m': 'timeRangePickerPreset15m',
  '1h': 'timeRangePickerPreset1h',
  '6h': 'timeRangePickerPreset6h',
  '24h': 'timeRangePickerPreset24h',
  '7d': 'timeRangePickerPreset7d',
  '30d': 'timeRangePickerPreset30d',
  '90d': 'timeRangePickerPreset90d',
  '6mo': 'timeRangePickerPreset6mo',
} as const satisfies Record<TimeRangePresetKey, MessageKey>;

const CUSTOM_VALUE = 'custom';

export interface TimeRangePickerProps {
  value: TimeRangeValue;
  onChange: (value: TimeRangeValue) => void;
  /** `capabilities.tiers` — never a hardcoded preset list. */
  tiers: readonly CapabilityTier[];
  disabled?: boolean;
  /** `'small'` for a dense toolbar row; defaults to the form-height control. */
  size?: 'small' | 'medium';
  /** Set false to place `<TimeRangeDetails>` yourself. */
  showDetails?: boolean;
}

export interface TimeRangeDetailsProps {
  value: TimeRangeValue;
  onChange: (value: TimeRangeValue) => void;
  disabled?: boolean;
}

/** `datetime-local`'s wall-clock string has no zone — read/written in the browser's own resolved zone. */
function toLocalInputValue(isoUtc: string): string {
  const date = new Date(isoUtc);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function fromLocalInputValue(local: string): string {
  return new Date(local).toISOString();
}

/**
 * The custom-range bounds (when the range is absolute) and the resolved
 * window, always labelled with its zone — `B/D15` 🔒.
 */
export function TimeRangeDetails({ value, onChange, disabled = false }: TimeRangeDetailsProps) {
  const resolved = resolveTimeRange(value);

  return (
    <Box>
      {value.kind === 'custom' && (
        <Box sx={{ display: 'flex', gap: '10px', flexWrap: 'wrap', mb: '6px' }}>
          <MuiTextField
            type="datetime-local"
            label={t('timeRangePickerFromLabel')}
            value={toLocalInputValue(value.from)}
            onChange={(event) => {
              if (event.target.value.length === 0) return;
              onChange({ ...value, from: fromLocalInputValue(event.target.value) });
            }}
            disabled={disabled}
            size="small"
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <MuiTextField
            type="datetime-local"
            label={t('timeRangePickerToLabel')}
            value={toLocalInputValue(value.to)}
            onChange={(event) => {
              if (event.target.value.length === 0) return;
              onChange({ ...value, to: fromLocalInputValue(event.target.value) });
            }}
            disabled={disabled}
            size="small"
            slotProps={{ inputLabel: { shrink: true } }}
          />
        </Box>
      )}

      <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
        {formatDateTime(resolved.from)} – {formatDateTime(resolved.to)} {timeZoneLabel()}
      </Typography>
    </Box>
  );
}

export function TimeRangePicker({
  value,
  onChange,
  tiers,
  disabled = false,
  size = 'medium',
  showDetails = true,
}: TimeRangePickerProps) {
  const permitted = permittedPresets(tiers);

  // A preset selected before a capabilities downgrade (e.g. `90d` while
  // only `24h` is now advertised) is corrected forward rather than left
  // pointing at an option this render no longer offers — same posture
  // `AggregationControl`'s own effect takes for `DM-D22`'s forcing.
  useEffect(() => {
    if (value.kind === 'preset' && !permitted.includes(value.preset) && permitted.length > 0) {
      onChange({ kind: 'preset', preset: permitted[permitted.length - 1] as TimeRangePresetKey });
    }
  }, [value, permitted, onChange]);

  const options: SelectOption[] = [
    ...permitted.map((preset) => ({ value: preset, label: t(PRESET_LABEL_KEY[preset]) })),
    { value: CUSTOM_VALUE, label: t('timeRangePickerCustomLabel') },
  ];

  function handleSelectChange(next: string) {
    if (next === CUSTOM_VALUE) {
      const resolved = resolveTimeRange(value);
      onChange({ kind: 'custom', from: resolved.from, to: resolved.to });
    } else {
      onChange({ kind: 'preset', preset: next as TimeRangePresetKey });
    }
  }

  return (
    <Box>
      <Select
        label={t('timeRangePickerFieldLabel')}
        value={value.kind === 'custom' ? CUSTOM_VALUE : value.preset}
        onChange={handleSelectChange}
        options={options}
        disabled={disabled}
        size={size}
      />

      {showDetails && (
        <Box sx={{ mt: '6px' }}>
          <TimeRangeDetails value={value} onChange={onChange} disabled={disabled} />
        </Box>
      )}
    </Box>
  );
}
