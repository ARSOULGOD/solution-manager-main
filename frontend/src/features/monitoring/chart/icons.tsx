/**
 * Inline `SvgIcon`s for the chart folder — `@mui/icons-material` is not a
 * project dependency, the same reasoning `dashboard/icons.tsx`,
 * `features/home/icons.tsx` and `shared/ui/internal/toneIcons.tsx` all
 * record. One icon so far: task 128 moved "Open data table" out of the
 * plot area and onto an icon button, so it needed a glyph.
 */
import { SvgIcon } from '@mui/material';
import type { SvgIconProps } from '@mui/material';

/** A table: header rule plus two body rows. `aria-hidden` — the button carries the accessible name. */
export function TableIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path
        d="M4 5h16v14H4z M4 9.5h16 M4 14.5h16 M10 9.5V19"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </SvgIcon>
  );
}
