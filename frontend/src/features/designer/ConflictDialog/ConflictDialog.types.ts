import type { DiffRow } from '@shared/ui';

import type { ConfigRepository, DesignerLoad } from '../configRepository';

export interface ConflictDialogProps {
  open: boolean;
  instanceId: string;
  repository: ConfigRepository;
  /**
   * "Your draft → current record", secrets already masked at the type
   * level (`I6`). Computed by the caller, never here — the `409` body
   * shape is `API CONTRACT — TBD` (§16 Q9), so there is no source to
   * derive these rows from yet. Supply them however is currently possible
   * (a fixture today, a real diff once Q9 lands); this component only
   * ever renders what it is given, exactly as `DiffView` itself does.
   */
  rows: readonly DiffRow[];
  onClose: () => void;
  /**
   * Fired after "Refetch & review" successfully reloads the current
   * record. The caller resets its own draft baseline/generation and is
   * expected to close the dialog itself — the same split `SaveBar.onSaved`
   * and `LoadPackageDialog.onLoaded` already use, so a refetch failure
   * leaves the caller's state untouched.
   */
  onRefetched: (load: DesignerLoad) => void;
}
