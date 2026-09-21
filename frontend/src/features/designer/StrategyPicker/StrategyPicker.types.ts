import type { RestartStrategy } from '../restartStrategies';

import type { StrategyAvailabilityModel } from './strategyAvailability';

export interface StrategyPickerProps extends StrategyAvailabilityModel {
  strategies: readonly RestartStrategy[];
  value: string;
  onChange: (key: string) => void;
}
