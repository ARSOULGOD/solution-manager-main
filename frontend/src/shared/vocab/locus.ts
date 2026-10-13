/**
 * Which actor applies configuration for an instance (UI §A.13.1, §A.12.6).
 * Row data with a tooltip — never a UI branch (`REQ-005`, `REQ-008`). Tips
 * state the credential posture per `architecture.md`'s DP model, since
 * that is exactly what a reader needs from this chip.
 */
import { TONE_ICON } from './toneIcons';
import type { VocabEntry } from './types';

export type LocusKey = 'dp_remote' | 'host_local' | 'observe_only';

export const locusVocab: Record<LocusKey, VocabEntry<LocusKey>> = {
  dp_remote: {
    key: 'dp_remote',
    label: 'dp-remote',
    tone: 'neutral',
    icon: TONE_ICON.neutral,
    tip: 'Solution Manager reaches into this host directly (SSH / Docker API / K8s API), holding scoped credentials for it.',
  },
  host_local: {
    key: 'host_local',
    label: 'host-local',
    tone: 'neutral',
    icon: TONE_ICON.neutral,
    tip: 'An outbound-only agent on this host reports to Solution Manager. No credentials for it are stored centrally.',
  },
  observe_only: {
    key: 'observe_only',
    label: 'observe-only',
    tone: 'neutral',
    icon: TONE_ICON.neutral,
    tip: 'Solution Manager only observes this target. It writes no configuration and holds no credentials for it.',
  },
};
