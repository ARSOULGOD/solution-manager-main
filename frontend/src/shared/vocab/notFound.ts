/**
 * "Declared but not found" (UI §A.13.1). A single `MISSING` badge covering
 * every one of these is a defect (`architecture.md` §7.2) — each has a
 * distinct meaning and a distinct next action.
 */
import { TONE_ICON } from './toneIcons';
import type { VocabEntry } from './types';

export type NotFoundKey =
  | 'restart_expected'
  | 'unreachable'
  | 'not_exposed'
  | 'missing'
  | 'bootstrap';

export const notFoundVocab: Record<NotFoundKey, VocabEntry<NotFoundKey>> = {
  restart_expected: {
    key: 'restart_expected',
    label: 'Restart expected',
    tone: 'warn',
    icon: TONE_ICON.warn,
    tip: 'The platform restarted this instance on purpose — it is expected to reappear.',
  },
  unreachable: {
    key: 'unreachable',
    label: 'Unreachable',
    tone: 'err',
    icon: TONE_ICON.err,
    scope: 'Host alert',
    tip: 'The host could not be reached.',
  },
  not_exposed: {
    key: 'not_exposed',
    label: 'Not exposed',
    tone: 'warn',
    icon: TONE_ICON.warn,
    scope: 'Configuration finding',
    tip: 'Declared but not exposed for discovery — a configuration issue, not necessarily an outage.',
  },
  missing: {
    key: 'missing',
    label: 'Missing',
    tone: 'err',
    icon: TONE_ICON.err,
    scope: 'Instance alert',
    tip: 'Declared but not found anywhere it was expected.',
  },
  bootstrap: {
    key: 'bootstrap',
    label: 'Bootstrap',
    tone: 'info',
    icon: TONE_ICON.info,
    tip: 'Declared, not yet applied — awaiting its first restart before it becomes observable.',
  },
};
