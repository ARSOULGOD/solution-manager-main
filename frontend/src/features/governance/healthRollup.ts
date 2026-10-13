/**
 * `HealthState` (`governanceRepository.ts`'s wire contract) is nine values: the five
 * SCREAMING rollup keys plus the four NOTFOUND sub-states (`unreachable`,
 * `not_exposed`, `missing`, `bootstrap`) as distinct values (ruling `OR-39`). Two
 * different UI vocabularies already cover exactly those nine states —
 * `shared/vocab/health.ts`'s `healthVocab` (the five) and `shared/vocab/notFound.ts`'s
 * `notFoundVocab` (the four-or-five NOTFOUND states, which overlaps on
 * `restart_expected`) — so this module maps every wire state to ONE vocab entry
 * rather than inventing a third copy of the same tones.
 *
 * `unreachable` resolves to `notFoundVocab.unreachable` (`err` tone, "Host alert"),
 * never falling back to a generic error tone or to `RED`'s own entry — `OR-39`'s
 * "`UNKNOWN`, not `DOWN`" is a distinct meaning, and `HealthHero`/`ReplicaCards` must
 * render it distinctly, not as a same-looking failure.
 */
import { healthVocab, notFoundVocab, type VocabEntry } from '@shared/vocab';

import type { HealthState } from './governanceRepository';

type AnyVocabKey = keyof typeof healthVocab | keyof typeof notFoundVocab;

export const HEALTH_STATE_VOCAB: Record<HealthState, VocabEntry<AnyVocabKey>> = {
  GREEN: healthVocab.healthy,
  RED: healthVocab.not_responding,
  RESTART_EXPECTED: healthVocab.restart_expected,
  INACTIVE_REPLICA: healthVocab.inactive_replica,
  NOT_APPLICABLE: healthVocab.not_applicable,
  unreachable: notFoundVocab.unreachable,
  not_exposed: notFoundVocab.not_exposed,
  missing: notFoundVocab.missing,
  bootstrap: notFoundVocab.bootstrap,
};

/**
 * Worst-first ordering for the hero's single headline state, since `Health` carries
 * no top-level rollup any more (`OR-39`/`OR-55`) and the hero still needs one number
 * to lead with. `unreachable`/`missing` outrank `RED` (a confirmed host alert is worse
 * than "not responding to the health check"); `NOT_APPLICABLE` and `bootstrap` sit at
 * the bottom because neither is an outage.
 */
const SEVERITY_ORDER: readonly HealthState[] = [
  'unreachable',
  'missing',
  'RED',
  'not_exposed',
  'RESTART_EXPECTED',
  'INACTIVE_REPLICA',
  'bootstrap',
  'GREEN',
  'NOT_APPLICABLE',
];

/** The single worst state across an instance's live placements, for the hero banner. */
export function deriveOverallState(states: readonly HealthState[]): HealthState {
  if (states.length === 0) {
    return 'NOT_APPLICABLE';
  }
  for (const candidate of SEVERITY_ORDER) {
    if (states.includes(candidate)) {
      return candidate;
    }
  }
  return states[0]!;
}
