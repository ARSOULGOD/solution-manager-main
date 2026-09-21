/**
 * Label / helper / error wiring reused from `TextField`'s own `select`
 * mode (task 012, design §18 "Forms") — MUI wires the label, the
 * generated id and `aria-describedby` identically whether or not
 * `select` is set (verified against `@mui/material/TextField` source,
 * 2026-08-20).
 */
import { MenuItem, TextField as MuiTextField } from '@mui/material';

import { resolveHelperText } from '../internal/FieldProps';

import type { SelectProps } from './Select.types';

export function Select({
  label,
  required = false,
  helperText,
  error,
  disabled = false,
  value,
  onChange,
  options,
  fullWidth = true,
  size = 'medium',
  name,
}: SelectProps) {
  const { text, isError } = resolveHelperText(error, helperText);

  return (
    <MuiTextField
      select
      label={label}
      required={required}
      disabled={disabled}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      fullWidth={fullWidth}
      size={size}
      error={isError}
      {...(text !== undefined ? { helperText: text } : {})}
      {...(name !== undefined ? { name } : {})}
    >
      {options.map((option) => (
        <MenuItem key={option.value} value={option.value} disabled={option.disabled ?? false}>
          {option.label}
        </MenuItem>
      ))}
    </MuiTextField>
  );
}
