import type { ReactNode } from 'react';

export interface ToggleOption<V extends string> {
  value: V;
  label: ReactNode;
  /** Required when `label` is icon-only — an icon-only control needs its own accessible name (`FE-D9`). */
  'aria-label'?: string;
}

export interface ToggleGroupProps<V extends string> {
  value: V;
  onChange: (value: V) => void;
  options: readonly ToggleOption<V>[];
  'aria-label': string;
  /** Disables every option, e.g. while its `ControlStep` (task 077) is gated-locked. */
  disabled?: boolean;
}
