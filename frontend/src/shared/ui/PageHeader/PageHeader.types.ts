import type { ReactNode } from 'react';

export interface PageHeaderProps {
  title: string;
  sub?: string;
  /**
   * Right-hand actions. Unauthorised actions must be **omitted** by the
   * caller (FE-D9 / G13) — never passed as a disabled control. This slot
   * wraps below the title under `md` and never truncates to save space,
   * because a missing action is the unauthorised signal.
   */
  actions?: ReactNode;
}
