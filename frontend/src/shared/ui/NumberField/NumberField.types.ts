import type { FocusEventHandler } from 'react';

import type { FieldProps } from '../internal/FieldProps';

export interface NumberFieldProps extends FieldProps {
  /** Empty string is the "no value entered" state — never coerced to `0`. */
  value: number | '';
  onChange: (value: number | '') => void;
  onBlur?: FocusEventHandler<HTMLInputElement>;
  min?: number;
  max?: number;
  step?: number;
  autoFocus?: boolean;
  fullWidth?: boolean;
  name?: string;
}
