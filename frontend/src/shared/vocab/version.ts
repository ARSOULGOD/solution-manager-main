/**
 * Version reconciliation state (UI §A.13.1). `unverified` must not be
 * styled as an error — it is the permanent-normal state for an MI on a
 * plain VM, and editing stays allowed (`UI-D12` over `SM-D14`).
 */
import { TONE_ICON } from './toneIcons';
import type { VocabEntry } from './types';

export type VersionKey = 'ok' | 'drift' | 'unknown' | 'unverified';

export const versionVocab: Record<VersionKey, VocabEntry<VersionKey>> = {
  ok: {
    key: 'ok',
    label: 'OK',
    tone: 'ok',
    icon: TONE_ICON.ok,
  },
  drift: {
    key: 'drift',
    label: 'Drift',
    tone: 'warn',
    icon: TONE_ICON.warn,
    tip: 'Observed version no longer matches the declared version — read-only until reconciled.',
  },
  unknown: {
    key: 'unknown',
    label: 'Unknown',
    tone: 'warn',
    icon: TONE_ICON.warn,
    tip: 'Version could not be determined — editing is blocked, monitoring continues.',
  },
  unverified: {
    key: 'unverified',
    label: 'Unverified',
    tone: 'neutral',
    icon: TONE_ICON.neutral,
    tip: 'Normal for an MI on a plain VM with no version-reporting mechanism. Editing is allowed.',
  },
};
