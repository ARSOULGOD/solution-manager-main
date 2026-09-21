import type { ReactNode } from 'react';

export type AlertSeverity = 'info' | 'warn' | 'err' | 'ok' | 'neutral';

export interface AlertProps {
  severity: AlertSeverity;
  title?: string;
  children: ReactNode;
  /** Overrides the default per-severity icon. */
  icon?: ReactNode;
  /** Trailing action slot — e.g. the retry button. */
  action?: ReactNode;
  /** Renders full-width, for a page-top banner rather than an inline alert. */
  fullWidth?: boolean;
}
