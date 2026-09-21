import type { ConfigRepository } from '@features/designer';

import type { GovernanceRepository } from '../governanceRepository';

export interface GovernancePageProps {
  solutionId: string;
  instanceId: string;
  repository: GovernanceRepository;
  /** Sources the package's declared components for the log viewer's component selector (task 066). */
  configRepository: ConfigRepository;
}
