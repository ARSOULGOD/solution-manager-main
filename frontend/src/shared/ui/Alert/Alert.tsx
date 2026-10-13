/**
 * Typed severities, an icon override, a trailing action slot and — the
 * one behavioural requirement design §16 puts on this component —
 * `role="alert"` **only** for `err` (task 014 acceptance). MUI's own
 * `Alert` defaults `role="alert"` for every severity (verified against
 * `@mui/material/Alert` source, 2026-08-20); left alone, an `info`/`ok`
 * banner would interrupt a screen reader the same way a real error does.
 * "Alert" and "Banner" (design §16's "banner of unavailable instances")
 * are the same component — `fullWidth` is the only thing that changes.
 * Colour comes from `theme.palette.tone` (task 013's extension), the
 * same source `Chip`/`StateChip` use, never a literal here (`FE-D5`).
 */
import { Alert as MuiAlert, AlertTitle, useTheme } from '@mui/material';

import { ToneIcon } from '../internal/toneIcons';

import type { AlertProps, AlertSeverity } from './Alert.types';

const DEFAULT_ICON: Record<AlertSeverity, ReturnType<typeof ToneIcon>> = {
  info: <ToneIcon tone="info" />,
  warn: <ToneIcon tone="warn" />,
  err: <ToneIcon tone="err" />,
  ok: <ToneIcon tone="ok" />,
  neutral: <ToneIcon tone="neutral" />,
};

export function Alert({ severity, title, children, icon, action, fullWidth = false }: AlertProps) {
  const theme = useTheme();
  const { main, bg } = theme.palette.tone[severity];

  return (
    <MuiAlert
      icon={icon ?? DEFAULT_ICON[severity]}
      role={severity === 'err' ? 'alert' : 'status'}
      {...(action !== undefined ? { action } : {})}
      sx={{
        backgroundColor: bg,
        color: main,
        ...(fullWidth ? { width: '100%' } : {}),
        '& .MuiAlert-icon': { color: main },
      }}
    >
      {title !== undefined && <AlertTitle>{title}</AlertTitle>}
      {children}
    </MuiAlert>
  );
}
