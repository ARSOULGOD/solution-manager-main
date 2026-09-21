import type { ApplyOutcome } from '../applyRepository';

export interface ApplyOutcomePanelProps {
  outcome: ApplyOutcome;
  /** The ONLY caller of `confirmActual()` — an operator act, never automatic. */
  onConfirm: () => void;
  /** Operator-initiated only, and only reachable once `outcome.kind === 'CONFIRMED_BY_OPERATOR'`. */
  onRetry: () => void;
}
