import type { ReactNode } from 'react';

export interface EmptyStateProps {
  icon?: ReactNode;
  title: string;
  /** Must name why it is empty and what to do — never just "no data". */
  body: string;
  action?: ReactNode;
}
