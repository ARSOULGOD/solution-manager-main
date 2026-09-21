export interface DivergedBannerProps {
  /** Re-stages the last uniform generation to every replica — no confirm dialog, same low-consequence tier as `manual`'s own default stage-and-wait step. */
  onRestage: () => void;
  /** Opens `RestartConfirm` (task 061) on the remaining, not-yet-restarted replicas — the caller's to wire. */
  onRestart: () => void;
  /** Opens `RollbackDialog`. */
  onRollback: () => void;
}
