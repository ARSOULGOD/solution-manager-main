/**
 * `governanceRepository.ts`'s `ConfigLadder` (task 033's scaffold) carries
 * a per-step boolean (`{ key, reached: boolean }[]`) — the same shape
 * `ConfigStepper` (task 060) was deliberately built to NOT trust, because
 * nothing in that shape stops a caller (or a malformed response) from
 * marking the fifth rung, `effective-verified`, reached directly.
 * `deriveReachedRung` is the one translation boundary: it walks the
 * ladder from `desired` and stops at the first gap or at
 * `restart-observed`, so the result always fits `ConfigLadderReachedKey`
 * — a claim of convergence from the wire is structurally impossible to
 * pass through, not just discouraged.
 */
import { CONFIG_LADDER_STEP_KEYS, type ConfigLadderReachedKey } from '@shared/vocab';

import type { ConfigLadder } from './governanceRepository';

const REACHABLE_KEYS = CONFIG_LADDER_STEP_KEYS.filter(
  (key): key is ConfigLadderReachedKey => key !== 'effective-verified',
);

export function deriveReachedRung(steps: ConfigLadder['steps']): ConfigLadderReachedKey {
  const reachedByKey = new Map(steps.map((step) => [step.key, step.reached]));
  let reached: ConfigLadderReachedKey = 'desired';
  for (const key of REACHABLE_KEYS) {
    if (reachedByKey.get(key) !== true) {
      break;
    }
    reached = key;
  }
  return reached;
}
