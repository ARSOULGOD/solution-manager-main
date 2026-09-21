import type { DiffRow } from '@shared/ui';

import type { ConfigRepository, VersionDiff } from '../configRepository';

export interface AcceptRuntimeDialogProps {
  open: boolean;
  instanceId: string;
  /** The observed version the declaration is re-rendered against. */
  toVersion: string;
  repository: ConfigRepository;
  /**
   * The classified diff to review — "declared schema → observed schema",
   * secrets already masked (`I6`). Computed by the caller, the same split
   * `ConflictDialog.rows` already uses (task 056): the accept-runtime
   * response shape is itself `API CONTRACT — TBD`, so there is no source
   * to derive these rows from yet.
   */
  rows: readonly DiffRow[];
  onClose: () => void;
  /**
   * Fired once the runtime is accepted. The caller re-baselines its own
   * draft against the new declaration and is expected to close the dialog
   * itself — same split `SaveBar.onSaved`/`ConflictDialog.onRefetched`
   * already use.
   */
  onAccepted: (result: VersionDiff) => void;
}
