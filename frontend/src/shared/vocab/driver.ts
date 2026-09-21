/**
 * Where an instance runs (UI §A.13.1, §A.6.1). Row data with a tooltip —
 * never a UI branch (`REQ-005`, `REQ-008`): the same table and the same
 * screens serve Docker, Kubernetes and systemd alike.
 */
import { TONE_ICON } from './toneIcons';
import type { VocabEntry } from './types';

export type DriverKey = 'docker' | 'k8s' | 'systemd';

export const driverVocab: Record<DriverKey, VocabEntry<DriverKey>> = {
  docker: {
    key: 'docker',
    label: 'Docker',
    tone: 'neutral',
    icon: TONE_ICON.neutral,
  },
  k8s: {
    key: 'k8s',
    label: 'Kubernetes',
    tone: 'neutral',
    icon: TONE_ICON.neutral,
  },
  systemd: {
    key: 'systemd',
    label: 'systemd · VM',
    tone: 'neutral',
    icon: TONE_ICON.neutral,
  },
};
