import type { ReplicaObservationFixture } from '@shared/testdoubles/data/designer';
import type { ConfigLadderReachedKey } from '@shared/vocab';

export interface RolloutCardProps {
  replicas: readonly ReplicaObservationFixture[];
  reached: ConfigLadderReachedKey;
  /**
   * A halted rollout has left replicas on different generations (task
   * 062's `CONFIG_DIVERGED`) — caller-supplied, not derived from
   * `replicas` here: an in-progress rolling restart also has mixed
   * generations transiently, and only the orchestration layer knows
   * whether the rollout actually halted.
   */
  diverged: boolean;
  /** Matches the reference's own `defaultOpen` — uncontrolled after mount. */
  defaultOpen?: boolean;
}
