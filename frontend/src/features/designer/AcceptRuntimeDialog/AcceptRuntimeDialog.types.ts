import type { AcceptedRuntimeConfig, ConfigRepository } from '../configRepository';

export interface AcceptRuntimeDialogProps {
  open: boolean;
  instanceId: string;
  /** `acceptRuntimeConfig`'s `placementId` (`BE-D23`) — `undefined` disables confirm, no placement to target. */
  placementId: string | undefined;
  /** Optimistic-concurrency base (`Designer.stateVersion`) — the operation's own `baseStateVersion`. */
  baseStateVersion: number;
  repository: ConfigRepository;
  onClose: () => void;
  /**
   * Fired once the runtime is accepted. The caller re-baselines its own draft against the new
   * generation and is expected to close the dialog itself — same split `SaveBar.onSaved`/
   * `ConflictDialog.onRefetched` already use.
   */
  onAccepted: (result: AcceptedRuntimeConfig) => void;
}
