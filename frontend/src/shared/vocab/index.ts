/**
 * The state-vocabulary module (task 013) — the single place every status
 * string, tone, icon and explanatory tooltip lives. A screen renders a
 * state by looking it up here, never by inventing its own copy.
 */
export { configStateVocab } from './configState';
export type { ConfigStateKey } from './configState';

export { CONFIG_LADDER_STEP_KEYS, configLadderSteps } from './configLadder';
export type {
  ConfigLadderReachedKey,
  ConfigLadderStepEntry,
  ConfigLadderStepKey,
} from './configLadder';

export { driverVocab } from './driver';
export type { DriverKey } from './driver';

export { healthVocab } from './health';
export type { HealthKey } from './health';

export { locusVocab } from './locus';
export type { LocusKey } from './locus';

export { notFoundVocab } from './notFound';
export type { NotFoundKey } from './notFound';

export { versionVocab } from './version';
export type { VersionKey } from './version';

export type { VocabEntry } from './types';

import { configStateVocab } from './configState';
import { driverVocab } from './driver';
import { healthVocab } from './health';
import { locusVocab } from './locus';
import { notFoundVocab } from './notFound';
import type { VocabEntry } from './types';
import { versionVocab } from './version';

/** Every vocabulary entry across every category — the fixture the table-driven tests walk. */
export const allVocabEntries: VocabEntry[] = [
  ...Object.values(healthVocab),
  ...Object.values(notFoundVocab),
  ...Object.values(versionVocab),
  ...Object.values(configStateVocab),
  ...Object.values(driverVocab),
  ...Object.values(locusVocab),
];
