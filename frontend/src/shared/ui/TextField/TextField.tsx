/**
 * Label / helper / error wiring done once (task 012, design §18 "Forms").
 * MUI's own `TextField` already generates a stable id and wires
 * `aria-describedby` / `aria-invalid` from `helperText` / `error`
 * internally (verified against `@mui/material/TextField` source,
 * 2026-08-20) — this wrapper's job is narrower: swap `helperText` for
 * `error` so a caller cannot show both, and add the `reveal` toggle.
 */
import { InputAdornment, SvgIcon, TextField as MuiTextField } from '@mui/material';
import type { SvgIconProps } from '@mui/material';
import { useState } from 'react';

import { t } from '@shared/i18n';

import { IconButton } from '../Button';
import { resolveHelperText } from '../internal/FieldProps';

import type { TextFieldProps } from './TextField.types';

function EyeIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path
        d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
      />
      <circle cx="12" cy="12" r="3" />
    </SvgIcon>
  );
}

function EyeOffIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path
        d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
      />
      <circle cx="12" cy="12" r="3" />
      <line x1="2" y1="21" x2="22" y2="3" stroke="currentColor" strokeWidth="1.8" />
    </SvgIcon>
  );
}

export function TextField({
  label,
  required = false,
  helperText,
  error,
  disabled = false,
  value,
  onChange,
  onBlur,
  placeholder,
  reveal = false,
  multiline = false,
  rows,
  autoFocus,
  fullWidth = true,
  name,
  autoComplete,
  readOnly = false,
}: TextFieldProps) {
  const [revealed, setRevealed] = useState(false);
  const { text, isError } = resolveHelperText(error, helperText);

  return (
    <MuiTextField
      label={label}
      required={required}
      disabled={disabled}
      value={value}
      onChange={onChange}
      fullWidth={fullWidth}
      multiline={multiline}
      {...(rows !== undefined ? { rows } : {})}
      type={reveal && !revealed ? 'password' : 'text'}
      {...(text !== undefined ? { helperText: text } : {})}
      {...(onBlur !== undefined ? { onBlur } : {})}
      {...(placeholder !== undefined ? { placeholder } : {})}
      {...(autoFocus !== undefined ? { autoFocus } : {})}
      {...(name !== undefined ? { name } : {})}
      {...(autoComplete !== undefined ? { autoComplete } : {})}
      error={isError}
      {...(readOnly && !reveal ? { slotProps: { input: { readOnly: true } } } : {})}
      {...(reveal
        ? {
            slotProps: {
              input: {
                readOnly,
                endAdornment: (
                  <InputAdornment position="end">
                    <IconButton
                      aria-label={revealed ? t('hidePassword') : t('showPassword')}
                      size="sm"
                      onClick={() => setRevealed((prev) => !prev)}
                    >
                      {revealed ? <EyeOffIcon fontSize="small" /> : <EyeIcon fontSize="small" />}
                    </IconButton>
                  </InputAdornment>
                ),
              },
            },
          }
        : {})}
    />
  );
}
