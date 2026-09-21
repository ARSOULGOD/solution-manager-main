import type { HealthSnapshot } from '../governanceRepository';

export interface ReplicaCardsProps {
  replicas: HealthSnapshot['replicas'];
}
