/**
 * Adds the tri-state `mixed` value (design §9's control-kinds catalogue,
 * task 012 acceptance) on top of MUI's own `Checkbox`. MUI sets
 * `aria-checked="mixed"` from its `indeterminate` prop but deliberately
 * does **not** set the native `<input>`'s own `.indeterminate` DOM
 * property ("inconsistent behaviour across browsers" — its own source
 * comment, `@mui/material/Checkbox`, verified 2026-08-20), relying on a
 * `data-indeterminate` attribute instead. Without the real DOM property
 * backing it, axe-core's `aria-conditional-attr` rule correctly flags the
 * `aria-checked="mixed"` attribute as unbacked by native semantics — so
 * this wrapper sets `.indeterminate` itself via the `input` slot's `ref`,
 * the standard React pattern for a tri-state native checkbox (there is no
 * HTML attribute for it). Helper/error wiring is hand-built the same way
 * `Switch` does — `FormControlLabel` doesn't link one on its own, and
 * (like `Switch`) the `aria-describedby` / `aria-invalid` pair has to go
 * through `slotProps.input`, not as top-level props, or it lands on the
 * root span instead of the native `<input>`.
 */
import { Checkbox as MuiCheckbox, FormControlLabel, FormHelperText } from '@mui/material';
import { useEffect, useRef } from 'react';

import { resolveHelperText } from '../internal/FieldProps';
import { useDescribedByField } from '../internal/useDescribedByField';

import type { CheckboxProps } from './Checkbox.types';

export function Checkbox({
  label,
  helperText,
  error,
  disabled = false,
  checked,
  onChange,
  name,
}: CheckboxProps) {
  const { text, isError } = resolveHelperText(error, helperText);
  const { helperTextId, controlProps } = useDescribedByField(text, isError);
  const inputRef = useRef<HTMLInputElement>(null);
  const isMixed = checked === 'mixed';

  useEffect(() => {
    if (inputRef.current) {
      inputRef.current.indeterminate = isMixed;
    }
  }, [isMixed]);

  return (
    <div>
      <FormControlLabel
        control={
          <MuiCheckbox
            checked={checked === true}
            indeterminate={isMixed}
            onChange={(event) => onChange(event.target.checked)}
            disabled={disabled}
            slotProps={{ input: { ref: inputRef, ...controlProps } }}
            {...(name !== undefined ? { name } : {})}
          />
        }
        label={label}
      />
      {text !== undefined && (
        <FormHelperText id={helperTextId} error={isError}>
          {text}
        </FormHelperText>
      )}
    </div>
  );
}
