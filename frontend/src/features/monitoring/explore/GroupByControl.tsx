/**
 * Step 5's real control (task 080, Dec `DM-D8` §5.7, `dynamic-metrics`
 * §9.1 — mirrors the reference's `chip chip-info`/`chip-out` toggle row in
 * `explore.jsx`, rebuilt on MUI's own `ToggleButtonGroup` in non-exclusive
 * mode rather than the reference's plain `<button>`s, for the same reason
 * task 079 rejected a hand-rolled clickable div: a real `aria-pressed`
 * toggle button is accessible for free; a styled div is not.
 *
 * The shared `ToggleGroup` (task 011) is hard-typed single-exclusive
 * (`value: V`, one selection) — not reusable here without widening its
 * public type for this one call site, so this builds directly on MUI's
 * own primitive, the same "bespoke MUI primitive for one use site"
 * precedent `InstanceSelector.tsx`'s own `Meter` sub-component sets.
 *
 * The candidate label set is the selected metric's own `labelKeys`
 * (`CatalogEntry.labelKeys`) — never the reference's hardcoded `["region",
 * "org"]`. 7 of the current fixture catalog's 8 entries carry `labelKeys:
 * []`; that is the common case here, not an edge case, so it renders its
 * own explanatory caption rather than a silently-empty control.
 *
 * Presentational and controlled, same split as every other real Explore
 * control.
 */
import { ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';

import { t } from '@shared/i18n';

export interface GroupByControlProps {
  value: readonly string[];
  onChange: (value: readonly string[]) => void;
  /** The selected metric's candidate group-by keys (`selectedEntry.labelKeys`). */
  labelKeys: readonly string[];
  /** True while this step is gate-locked (task 077's `ControlStep` contract). */
  disabled?: boolean;
}

export function GroupByControl({ value, onChange, labelKeys, disabled = false }: GroupByControlProps) {
  if (labelKeys.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        {t('groupByControlNoLabelsCaption')}
      </Typography>
    );
  }

  return (
    <ToggleButtonGroup
      value={value}
      onChange={(_event, next: string[]) => onChange(next)}
      aria-label={t('exploreStepGroupByTitle')}
      disabled={disabled}
      size="small"
      sx={{ flexWrap: 'wrap', gap: '6px' }}
    >
      {labelKeys.map((key) => (
        <ToggleButton key={key} value={key} aria-pressed={value.includes(key)}>
          {key}
        </ToggleButton>
      ))}
    </ToggleButtonGroup>
  );
}
