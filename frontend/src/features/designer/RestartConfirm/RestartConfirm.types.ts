import type { ReplicaObservationFixture } from '@shared/testdoubles/data/designer';

import type { ApplyOutcome, ApplyRepository } from '../applyRepository';
import type { RestartStrategy } from '../restartStrategies';

export interface RestartConfirmProps {
  open: boolean;
  instanceId: string;
  placementId?: string;
  generation?: number;
  strategy: RestartStrategy;
  /** Only `.length` is read (replica count drives the copy) — same source as `ReplicaTable`. */
  replicas: readonly ReplicaObservationFixture[];
  repository: ApplyRepository;
  onCancel: () => void;
  /** Fires once `stage()` settles — with whatever `ApplyOutcome` it resolved to, never a "succeeded" one. */
  onApplied: (outcome: ApplyOutcome) => void;
}
