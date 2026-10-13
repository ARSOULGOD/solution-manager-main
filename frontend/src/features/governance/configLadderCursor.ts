/**
 * `ConfigLadder.reached` (and each `placements[].reached`) is now a server-computed
 * single rung, not a per-step boolean array to walk — but the wire value is still
 * `ConfigLadderRungKey`, which legally includes `'effective-verified'`, and
 * `ConfigStepper` (`shared/ui`) is deliberately typed to make that claim
 * structurally impossible to pass through (`ConfigLadderReachedKey` excludes it).
 * `clampReached` is the one translation boundary: it never trusts the wire's claim
 * that the fifth rung is reached, same guarantee the old per-step walk gave, just
 * over the new shape.
 */
import type { ConfigLadderReachedKey } from '@shared/vocab';

import type { ConfigLadderRungKey } from './governanceRepository';

export function clampReached(reached: ConfigLadderRungKey): ConfigLadderReachedKey {
  return reached === 'effective-verified' ? 'restart-observed' : reached;
}
