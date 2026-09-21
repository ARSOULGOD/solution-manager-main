import type { ReactNode } from 'react';

import type { ChipTone } from '../ui/Chip/Chip.types';

/**
 * One entry in a state vocabulary (task 013, UI §A.13.1) — the single
 * place a status string, tone, icon and explanatory tooltip lives, so a
 * screen never invents its own copy for a state it renders.
 */
export interface VocabEntry<Key extends string = string> {
  key: Key;
  label: string;
  tone: ChipTone;
  icon: ReactNode;
  /** Shown via an info affordance next to the chip when present. */
  tip?: string;
  /** Where this state surfaces, for entries that carry that as row context (e.g. "Host alert"). */
  scope?: string;
}
