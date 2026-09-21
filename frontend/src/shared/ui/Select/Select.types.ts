import type { FieldProps } from '../internal/FieldProps';

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface SelectProps extends FieldProps {
  value: string;
  onChange: (value: string) => void;
  options: SelectOption[];
  fullWidth?: boolean;
  /**
   * `'small'` is the 40px control the reference's `select.bare` draws —
   * what a dense toolbar row needs. Defaults to `'medium'` so every form
   * screen keeps the height it already had.
   */
  size?: 'small' | 'medium';
  name?: string;
}
