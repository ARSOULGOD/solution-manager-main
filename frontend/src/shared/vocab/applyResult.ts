/**
 * A replica's outcome from the apply that produced it — NOT a live health
 * check. `ReplicaTable` used to reuse `healthVocab` for this column, which
 * made a replica read "Healthy" indefinitely after a successful apply even
 * if the container stopped a minute later, because nothing here ever
 * re-polls the host. This vocabulary says only what the apply itself
 * observed, keyed by the same four `ReplicaObservationFixture['health']`
 * values applyRepository.ts computes.
 */
import { TONE_ICON } from './toneIcons';
import type { VocabEntry } from './types';

export type ApplyResultKey = 'restart_succeeded' | 'restart_failed' | 'restart_pending' | 'inactive_replica';

export const applyResultVocab: Record<ApplyResultKey, VocabEntry<ApplyResultKey>> = {
  restart_succeeded: {
    key: 'restart_succeeded',
    label: 'Restart succeeded',
    tone: 'ok',
    icon: TONE_ICON.ok,
    tip: 'The apply observed this replica restart. It is not re-checked afterward — this is a point-in-time result, not current status.',
  },
  restart_failed: {
    key: 'restart_failed',
    label: 'Restart failed',
    tone: 'err',
    icon: TONE_ICON.err,
    tip: 'The apply did not observe a successful restart for this replica.',
  },
  restart_pending: {
    key: 'restart_pending',
    label: 'Restart not yet observed',
    tone: 'warn',
    icon: TONE_ICON.warn,
    tip: 'The platform restarted this instance on purpose — this is not an outage.',
  },
  inactive_replica: {
    key: 'inactive_replica',
    label: 'Inactive replica',
    tone: 'idle',
    icon: TONE_ICON.idle,
    tip: 'A standby: up and deliberately processing nothing. Its KPIs read zero because it takes no traffic.',
  },
};
