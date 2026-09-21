/**
 * `duration` control (task 047, `UI-D14`). A plain `TextField` — the
 * package's `pattern`/bind validation does the real format checking
 * (`UI-D16`, task 053) — plus the canonical-unit example text a Spring
 * `Duration` string accepts, surfaced verbatim so a value like `120m` or
 * `PT2H` doesn't need a spec lookup to understand.
 */
import { t } from '@shared/i18n';
import { TextField } from '@shared/ui';

import type { DurationControlProps } from './DurationControl.types';

export function DurationControl({
  label,
  value,
  onChange,
  required = false,
  helperText,
  error,
  disabled = false,
  name,
}: DurationControlProps) {
  const canonical = t('controlDurationHelper');
  const combinedHelperText = helperText !== undefined ? `${helperText} — ${canonical}` : canonical;

  return (
    <TextField
      label={label}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      required={required}
      disabled={disabled}
      helperText={combinedHelperText}
      {...(error !== undefined ? { error } : {})}
      {...(name !== undefined ? { name } : {})}
    />
  );
}
