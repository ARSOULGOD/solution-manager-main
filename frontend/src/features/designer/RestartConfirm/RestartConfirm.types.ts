import type { ReplicaObservationFixture } from '@shared/testdoubles/data/designer';

import type { ApplyOutcome, ApplyRepository } from '../applyRepository';
import type { RestartStrategy } from '../restartStrategies';

export interface RestartConfirmProps {
  open: boolean;
  instanceId: string;
  strategy: RestartStrategy;
  /** Only `.length` is read (replica count drives the copy) — same source as `ReplicaTable`. */
  replicas: readonly ReplicaObservationFixture[];
  repository: ApplyRepository;
  onCancel: () => void;
  /** Fires after staging and the separately requested restart both settle, never with a success claim. */
  onApplied: (outcome: ApplyOutcome) => void;
}
