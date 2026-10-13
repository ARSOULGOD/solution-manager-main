import type { FieldProps } from '../internal/FieldProps';

export interface AutocompleteProps<T> extends FieldProps {
  value: T | null;
  onChange: (value: T | null) => void;
  options: T[];
  getOptionLabel: (option: T) => string;
  isOptionEqualToValue?: (option: T, value: T) => boolean;
  fullWidth?: boolean;
}
