import type { ConfigRepository } from '../configRepository';

export interface RestoreRuntimeDialogProps {
  open: boolean;
  instanceId: string;
  /** The declared version being restored — shown in the confirm copy so the operator knows what redeploys. */
  declaredVersion: string;
  repository: ConfigRepository;
  onCancel: () => void;
  /** Fired once the restore instruction is accepted. The caller re-checks version state on its own cadence — this dialog does not poll. */
  onRestored: () => void;
}
