/**
 * A `TextField` restricted to numeric entry (task 012). `type="number"`
 * plus `inputMode="numeric"` together: the former is what makes the
 * value model numeric (spinners, `valueAsNumber`), the latter is what
 * gets a numeric keypad on mobile even for browsers that don't infer one
 * from `type="number"` alone.
 */
import { TextField as MuiTextField } from '@mui/material';

import { resolveHelperText } from '../internal/FieldProps';

import type { NumberFieldProps } from './NumberField.types';

export function NumberField({
  label,
  required = false,
  helperText,
  error,
  disabled = false,
  value,
  onChange,
  onBlur,
  min,
  max,
  step,
  autoFocus,
  fullWidth = true,
  name,
}: NumberFieldProps) {
  const { text, isError } = resolveHelperText(error, helperText);
  const htmlInputProps = {
    inputMode: 'numeric' as const,
    ...(min !== undefined ? { min } : {}),
    ...(max !== undefined ? { max } : {}),
    ...(step !== undefined ? { step } : {}),
  };

  return (
    <MuiTextField
      type="number"
      label={label}
      required={required}
      disabled={disabled}
      value={value}
      onChange={(event) => {
        const raw = event.target.value;
        onChange(raw === '' ? '' : Number(raw));
      }}
      fullWidth={fullWidth}
      error={isError}
      slotProps={{ htmlInput: htmlInputProps }}
      {...(text !== undefined ? { helperText: text } : {})}
      {...(onBlur !== undefined ? { onBlur } : {})}
      {...(autoFocus !== undefined ? { autoFocus } : {})}
      {...(name !== undefined ? { name } : {})}
    />
  );
}
