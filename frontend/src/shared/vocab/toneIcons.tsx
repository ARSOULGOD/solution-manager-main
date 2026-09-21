/**
 * One pre-built icon element per `ChipTone`, shared across every
 * vocabulary entry of that tone. Text (the entry's `label`) is what
 * distinguishes individual states from one another; the icon's job is
 * only the non-colour channel `StateChip` needs so a state is never
 * colour-only (task 013 objective). The glyphs themselves live in
 * `shared/ui/internal/toneIcons` — task 014's `Alert` draws its default
 * severity icons from the same set.
 */
import type { ChipTone } from '../ui/Chip/Chip.types';
import { ToneIcon } from '../ui/internal/toneIcons';

/** One pre-built element per tone — plain descriptors, safe to reuse across many chips at once. */
export const TONE_ICON: Record<ChipTone, ReturnType<typeof ToneIcon>> = {
  ok: <ToneIcon tone="ok" fontSize="small" />,
  warn: <ToneIcon tone="warn" fontSize="small" />,
  err: <ToneIcon tone="err" fontSize="small" />,
  info: <ToneIcon tone="info" fontSize="small" />,
  idle: <ToneIcon tone="idle" fontSize="small" />,
  neutral: <ToneIcon tone="neutral" fontSize="small" />,
  purple: <ToneIcon tone="purple" fontSize="small" />,
};
