import type { FieldProps } from '../internal/FieldProps';

export type CheckboxValue = boolean | 'mixed';

export interface CheckboxProps extends Omit<FieldProps, 'required'> {
  checked: CheckboxValue;
  onChange: (checked: boolean) => void;
  name?: string;
}
