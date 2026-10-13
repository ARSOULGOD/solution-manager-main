/**
 * `FormControlLabel` doesn't itself link a helper/error line to its
 * control the way `TextField` does — `useDescribedByField` builds that
 * `aria-describedby` / `aria-invalid` wiring by hand (task 012, design
 * §18 "Forms"). Those land on `slotProps.input`, not as top-level props
 * on `Switch` itself: `SwitchBase` forwards its own props to the *root*
 * span (the clickable/ripple wrapper), not the native `<input>` — an
 * `aria-describedby` passed directly would sit on the wrong element and
 * never reach the thing a screen reader actually associates with the
 * label (verified against `@mui/material/internal/SwitchBase` source and
 * by inspecting the rendered DOM, 2026-08-20).
 */
import { FormControlLabel, FormHelperText, Switch as MuiSwitch } from '@mui/material';

import { resolveHelperText } from '../internal/FieldProps';
import { useDescribedByField } from '../internal/useDescribedByField';

import type { SwitchProps } from './Switch.types';

export function Switch({
  label,
  helperText,
  error,
  disabled = false,
  checked,
  onChange,
  name,
}: SwitchProps) {
  const { text, isError } = resolveHelperText(error, helperText);
  const { helperTextId, controlProps } = useDescribedByField(text, isError);

  return (
    <div>
      <FormControlLabel
        control={
          <MuiSwitch
            checked={checked}
            onChange={(event) => onChange(event.target.checked)}
            disabled={disabled}
            slotProps={{ input: controlProps }}
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
