/**
 * MUI's own combobox/listbox wiring (`aria-expanded`, `aria-activedescendant`,
 * `role="combobox"`) plus the same label/helper/error contract every other
 * control in this kit uses (task 012, design §18 "Forms"). `renderInput`
 * uses MUI's raw `TextField` — Autocomplete's `params` carry the `ref` and
 * `InputProps` its popover positioning needs, which our own `TextField`
 * wrapper (task 012) doesn't accept passthrough for — but the label /
 * required / helper / error props are still resolved through the same
 * `resolveHelperText` every other wrapper uses.
 */
import { Autocomplete as MuiAutocomplete, TextField as MuiTextField } from '@mui/material';

import { resolveHelperText } from '../internal/FieldProps';

import type { AutocompleteProps } from './Autocomplete.types';

export function Autocomplete<T>({
  label,
  required = false,
  helperText,
  error,
  disabled = false,
  value,
  onChange,
  options,
  getOptionLabel,
  isOptionEqualToValue,
  fullWidth = true,
}: AutocompleteProps<T>) {
  const { text, isError } = resolveHelperText(error, helperText);

  return (
    <MuiAutocomplete
      options={options}
      value={value}
      onChange={(_event, next) => onChange(next)}
      getOptionLabel={getOptionLabel}
      disabled={disabled}
      fullWidth={fullWidth}
      {...(isOptionEqualToValue !== undefined ? { isOptionEqualToValue } : {})}
      renderInput={(params) => (
        <MuiTextField
          {...params}
          label={label}
          required={required}
          error={isError}
          {...(text !== undefined ? { helperText: text } : {})}
        />
      )}
    />
  );
}
