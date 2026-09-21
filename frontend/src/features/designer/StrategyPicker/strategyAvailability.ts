/**
 * The suppression rules as rules, not copy (task 061, Design §10.1). Pure
 * so every branch is a fixture case rather than something only provable by
 * rendering the picker — same split as `majorityGeneration.ts` (task 060).
 *
 * Order matters: an MI-specific `healthGateUnsafeReason` (open item 15) is
 * checked before the generic uniform-config suppression, so a solution
 * like Cache — where both are technically true — states the honest,
 * specific cause rather than a generic one.
 */
import { t } from '@shared/i18n';

import type { RestartStrategy } from '../restartStrategies';

export interface StrategyAvailabilityModel {
  requiresUniformConfig: boolean;
  healthMappingDeclared: boolean;
  healthGateUnsafeReason?: string;
}

export interface StrategyAvailability {
  disabled: boolean;
  /** Always present when `disabled` — every suppression states its reason. */
  reason?: string;
}

export function evaluateStrategyAvailability(
  strategy: RestartStrategy,
  model: StrategyAvailabilityModel,
): StrategyAvailability {
  if (strategy.outOfRelease === true) {
    return { disabled: true, reason: t('strategyPickerReasonOutOfRelease') };
  }
  if (strategy.needsHealthMapping === true && !model.healthMappingDeclared) {
    return { disabled: true, reason: t('strategyPickerReasonNoHealthMapping') };
  }
  if (strategy.key === 'rolling-health-gated' && model.healthGateUnsafeReason !== undefined) {
    return { disabled: true, reason: model.healthGateUnsafeReason };
  }
  if (strategy.needsNonUniform === true && model.requiresUniformConfig) {
    return { disabled: true, reason: t('strategyPickerReasonRequiresUniform') };
  }
  return { disabled: false };
}
