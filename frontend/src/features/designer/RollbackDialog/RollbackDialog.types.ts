import type { DiffRow } from '@shared/ui';

import type { ApplyOutcome, ApplyRepository } from '../applyRepository';

export interface RollbackDialogProps {
  open: boolean;
  instanceId: string;
  /** The generation every replica is being reverted FROM — shown in the diff header. */
  currentGeneration: string;
  /** The last uniform generation being reverted TO — the only target a rollback ever names (`B/D12`). */
  targetGeneration: string;
  /**
   * The field-level diff, current → target, secrets already masked (`I6`).
   * Computed by the caller — same split `ConflictDialog.rows` and
   * `AcceptRuntimeDialog.rows` already use: no rollback-diff endpoint is
   * specified anywhere, so there is no source to derive these rows from
   * yet. This component only ever renders what it is given.
   */
  rows: readonly DiffRow[];
  repository: ApplyRepository;
  onCancel: () => void;
  /** Fires once `rollback()` settles — with whatever `ApplyOutcome` it resolved to, never a "succeeded" one. */
  onApplied: (outcome: ApplyOutcome) => void;
}
