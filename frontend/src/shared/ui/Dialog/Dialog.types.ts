import type { ReactNode } from 'react';

import type { BreakpointKey } from '@shared/theme/breakpoints';

export type Consequence = 'outage' | 'high';

export interface DialogProps {
  open: boolean;
  title: string;
  children: ReactNode;
  onClose: () => void;
  /** When true, a scrim click does not close (Escape still does). */
  dirty?: boolean;
  maxWidth?: 'sm' | 'md';
  actions?: ReactNode;
  /**
   * Task 108 (`FE-D29`): below this breakpoint the dialog becomes a
   * full-screen sheet (MUI's own `fullScreen`) instead of a centred
   * modal. Opt-in and omitted by default — every caller predating task
   * 108 keeps its current centred behaviour at every width.
   */
  fullScreenBelow?: BreakpointKey;
}

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  children?: ReactNode;
  consequence?: Consequence;
  onConfirm: () => void;
  onCancel: () => void;
  confirmLabel: string;
  dirty?: boolean;
}

export interface HighConsequenceDialogProps extends ConfirmDialogProps {
  consequence: Consequence;
}
