import type { HealthSnapshot } from '../governanceRepository';

export interface ReplicaCardsProps {
  /** One card per live placement (`Health.placements[]` — `OR-39`/`OR-55`, no top-level rollup). */
  placements: HealthSnapshot['placements'];
}
