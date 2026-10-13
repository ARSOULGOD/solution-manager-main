import type { GovernanceRepository } from '../governanceRepository';

export interface LogViewerProps {
  instanceId: string;
  /** The package's declared component types for this instance's version — see `deriveComponentSelection`. */
  components: readonly string[];
  /** The instance's live placement ids (`Health.placements[]`) — a selector renders only when there is more than one. */
  placementIds: readonly string[];
  repository: GovernanceRepository;
}
