/**
 * Segmented control (env filters, seriesBy, mark, resolution, layer).
 * `role="group"` on the root; each option is `aria-pressed` (FE-018).
 */
import { ToggleButton, ToggleButtonGroup } from '@mui/material';

import type { ToggleGroupProps } from './ToggleGroup.types';

export function ToggleGroup<V extends string>({
  value,
  onChange,
  options,
  'aria-label': ariaLabel,
  disabled = false,
}: ToggleGroupProps<V>) {
  return (
    <ToggleButtonGroup
      exclusive
      value={value}
      onChange={(_event, next: V | null) => {
        if (next !== null) {
          onChange(next);
        }
      }}
      aria-label={ariaLabel}
      size="small"
      disabled={disabled}
    >
      {options.map((option) => (
        <ToggleButton
          key={option.value}
          value={option.value}
          aria-pressed={value === option.value}
          aria-label={option['aria-label']}
        >
          {option.label}
        </ToggleButton>
      ))}
    </ToggleButtonGroup>
  );
}
