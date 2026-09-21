/**
 * `HealthRollupState` (`governanceRepository.ts`'s wire contract, task 033)
 * is a different vocabulary from `HealthKey` (`shared/vocab/health.ts`,
 * task 013) — same five states, different casing/naming, because one
 * mirrors the backend enum and the other is the UI vocabulary every other
 * screen (`InstanceHeader`, the inventory table) already renders through.
 * This map is the one place that translates the rollup into a `healthVocab`
 * lookup, so `HealthHero` reuses the existing tone/icon instead of a third
 * copy of the same five states.
 */
import type { HealthKey } from '@shared/vocab';

import type { HealthRollupState } from './governanceRepository';

export const ROLLUP_TO_HEALTH_KEY: Record<HealthRollupState, HealthKey> = {
  GREEN: 'healthy',
  RED: 'not_responding',
  RESTART_EXPECTED: 'restart_expected',
  INACTIVE_REPLICA: 'inactive_replica',
  NOT_APPLICABLE: 'not_applicable',
};
