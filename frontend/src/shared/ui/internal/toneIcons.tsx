/**
 * One icon path per `ChipTone` (task 013), shared by every component that
 * needs a tone-coloured icon — `StateChip`'s vocabulary entries and
 * `Alert`'s default severity icon (task 014) both draw from this one set,
 * so a fix to one glyph doesn't need a second, easy-to-miss edit
 * elsewhere. Inlined `SvgIcon`s, not `@mui/icons-material` — that package
 * is not a project dependency (only `@mui/material` is), same as
 * `Button`'s own danger marker (task 011).
 */
import { SvgIcon } from '@mui/material';
import type { SvgIconProps } from '@mui/material';

import type { ChipTone } from '../Chip/Chip.types';

export const TONE_ICON_PATHS: Record<ChipTone, string> = {
  ok: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z',
  warn: 'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z',
  err: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z',
  info: 'M11 7h2v2h-2V7zm0 4h2v6h-2v-6zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z',
  idle: 'M6 5h4v14H6zm8 0h4v14h-4z',
  neutral:
    'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zM7 11h10v2H7z',
  purple: 'M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16z',
};

export function ToneIcon({ tone, ...rest }: SvgIconProps & { tone: ChipTone }) {
  return (
    <SvgIcon {...rest} aria-hidden focusable="false">
      <path d={TONE_ICON_PATHS[tone]} />
    </SvgIcon>
  );
}
