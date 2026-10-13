/**
 * Instance/replica health (UI §A.13.1, ported verbatim). `not_applicable`
 * must never resolve to the error tone — it means "no health endpoint",
 * a normal condition, never an outage.
 */
import { TONE_ICON } from './toneIcons';
import type { VocabEntry } from './types';

export type HealthKey =
  | 'healthy'
  | 'not_responding'
  | 'restart_expected'
  | 'inactive_replica'
  | 'not_applicable';

export const healthVocab: Record<HealthKey, VocabEntry<HealthKey>> = {
  healthy: {
    key: 'healthy',
    label: 'Healthy',
    tone: 'ok',
    icon: TONE_ICON.ok,
  },
  not_responding: {
    key: 'not_responding',
    label: 'Not responding',
    tone: 'err',
    icon: TONE_ICON.err,
    tip: 'Stopped answering health checks.',
  },
  restart_expected: {
    key: 'restart_expected',
    label: 'Restart expected',
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
  not_applicable: {
    key: 'not_applicable',
    label: 'Not applicable',
    tone: 'neutral',
    icon: TONE_ICON.neutral,
    tip: 'Exposes no health endpoint — a normal condition. It is never treated as an outage.',
  },
};
