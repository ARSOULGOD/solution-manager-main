import type { ConfigRepository } from '../configRepository';

export interface RestoreRuntimeDialogProps {
  open: boolean;
  instanceId: string;
  /** `restoreRuntimeConfig`'s `placementId` (`BE-D23`) — `undefined` disables confirm, no placement to target. */
  placementId: string | undefined;
  /** The declared version being restored — shown in the confirm copy so the operator knows what redeploys. */
  declaredVersion: string;
  repository: ConfigRepository;
  onCancel: () => void;
  /** Fired once the restore is admitted. The caller re-checks version state on its own cadence — this dialog does not poll. */
  onRestored: () => void;
}
