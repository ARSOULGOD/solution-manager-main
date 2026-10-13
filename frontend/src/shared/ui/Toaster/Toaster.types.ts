import type { AlertSeverity } from '../Alert';

export interface ToastInput {
  title: string;
  detail?: string;
  /** Required — a toast without a server correlation id is a defect (FE-D13). */
  correlationId: string;
  severity?: AlertSeverity;
}

export interface ToastRecord extends ToastInput {
  id: string;
  severity: AlertSeverity;
}

export interface OutcomeBadgeProps {
  title: string;
  correlationId: string;
  severity: AlertSeverity;
}
