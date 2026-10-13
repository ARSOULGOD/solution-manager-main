/**
 * Configuration-of-record state (UI §A.13.1). `in_sync` is rendered by
 * its consumer with an additional `effective-unverified` qualifier chip —
 * restart-observed is not the same claim as convergence (`SM-D22`) — that
 * pairing is the consumer's job, not this vocabulary's.
 */
import { TONE_ICON } from './toneIcons';
import type { VocabEntry } from './types';

export type ConfigStateKey = 'in_sync' | 'staged' | 'rendered' | 'diverged' | 'drift' | 'na';

export const configStateVocab: Record<ConfigStateKey, VocabEntry<ConfigStateKey>> = {
  in_sync: {
    key: 'in_sync',
    label: 'Applied',
    tone: 'ok',
    icon: TONE_ICON.ok,
  },
  staged: {
    key: 'staged',
    label: 'Staged · restart req’d',
    tone: 'warn',
    icon: TONE_ICON.warn,
    tip: 'Written is not working — the change is saved but has not taken effect yet.',
  },
  rendered: {
    key: 'rendered',
    label: 'Rendered · not persisted',
    tone: 'info',
    icon: TONE_ICON.info,
    tip: 'Previewed but not saved.',
  },
  diverged: {
    key: 'diverged',
    label: 'Diverged',
    tone: 'err',
    icon: TONE_ICON.err,
    tip: 'The deployed configuration no longer matches what was applied.',
  },
  drift: {
    key: 'drift',
    label: 'Drift',
    tone: 'warn',
    icon: TONE_ICON.warn,
    tip: 'A hand-edit outside Solution Manager. Alert-only — reconcile manually; never auto-reverted.',
  },
  na: {
    key: 'na',
    label: 'N/A',
    tone: 'neutral',
    icon: TONE_ICON.neutral,
    tip: 'Observe-only target — Solution Manager writes no configuration here.',
  },
};
