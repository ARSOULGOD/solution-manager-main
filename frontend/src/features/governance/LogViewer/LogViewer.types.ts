import type { GovernanceRepository } from '../governanceRepository';

export interface LogViewerProps {
  instanceId: string;
  /** The package's declared component types for this instance's version — see `deriveComponentSelection`. */
  components: readonly string[];
  repository: GovernanceRepository;
}
