/**
 * Labelled search. Debounces **only** free text (FE-025, design §17).
 */
import { TextField as MuiTextField } from '@mui/material';
import { useRef, useState } from 'react';

import { t } from '@shared/i18n';

import type { SearchFieldProps } from './FilterBar.types';

export function SearchField({
  label = t('search'),
  value,
  onChange,
  debounceMs = 300,
}: SearchFieldProps) {
  const [local, setLocal] = useState(value);
  const [prevValue, setPrevValue] = useState(value);
  if (value !== prevValue) {
    setPrevValue(value);
    setLocal(value);
  }

  const timer = useRef<number>(0);

  return (
    <MuiTextField
      label={label}
      value={local}
      onChange={(event) => {
        const next = event.target.value;
        setLocal(next);
        window.clearTimeout(timer.current);
        timer.current = window.setTimeout(() => {
          onChange(next);
        }, debounceMs);
      }}
      size="small"
    />
  );
}
