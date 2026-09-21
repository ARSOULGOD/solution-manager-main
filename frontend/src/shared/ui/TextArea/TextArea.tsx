/**
 * `TextField`'s own `multiline` mode (task 012, design §18 "Forms") — same
 * automatic label/helper/error wiring, just rows instead of a single line.
 */
import { TextField as MuiTextField } from '@mui/material';

import { resolveHelperText } from '../internal/FieldProps';

import type { TextAreaProps } from './TextArea.types';

export function TextArea({
  label,
  required = false,
  helperText,
  error,
  disabled = false,
  value,
  onChange,
  minRows = 3,
  maxRows,
  fullWidth = true,
  name,
}: TextAreaProps) {
  const { text, isError } = resolveHelperText(error, helperText);

  return (
    <MuiTextField
      multiline
      label={label}
      required={required}
      disabled={disabled}
      value={value}
      onChange={onChange}
      fullWidth={fullWidth}
      minRows={minRows}
      error={isError}
      {...(maxRows !== undefined ? { maxRows } : {})}
      {...(text !== undefined ? { helperText: text } : {})}
      {...(name !== undefined ? { name } : {})}
    />
  );
}
