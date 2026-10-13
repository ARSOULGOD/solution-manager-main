import type { HealthSnapshot } from '../governanceRepository';

export interface HealthHeroProps {
  snapshot: HealthSnapshot;
  onOpenMonitoring: () => void;
}
