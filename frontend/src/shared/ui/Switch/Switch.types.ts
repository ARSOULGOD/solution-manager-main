import type { FieldProps } from '../internal/FieldProps';

export interface SwitchProps extends Omit<FieldProps, 'required'> {
  checked: boolean;
  onChange: (checked: boolean) => void;
  name?: string;
}
