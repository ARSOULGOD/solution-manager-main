/**
 * Glyphs for the drawer's own two platform entries plus the rail
 * collapse control (task 024, task 111). Inlined `SvgIcon`s, not
 * `@mui/icons-material` — that package is not a project dependency (same
 * reasoning as `app/shell/icons.tsx` and `internal/toneIcons.tsx`).
 *
 * Solution entries do **not** draw from here: they use `SolutionGlyph`
 * (`internal/solutionGlyphs.tsx`), keyed by the package's own `icon`
 * field, so the drawer and the catalog card show the same mark.
 */
import { SvgIcon } from '@mui/material';
import type { SvgIconProps } from '@mui/material';

export function HomeIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 3 2 11.5h3V21h5.5v-6h3v6H19v-9.5h3L12 3Z" />
    </SvgIcon>
  );
}

/** Four tiles — the catalog/grid mark the reference shell uses for Solutions. */
export function GridIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M3 3h8v8H3V3Zm10 0h8v8h-8V3ZM3 13h8v8H3v-8Zm10 0h8v8h-8v-8Z" />
    </SvgIcon>
  );
}

export function ChevronLeftIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M15.4 7.4 14 6l-6 6 6 6 1.4-1.4-4.6-4.6 4.6-4.6Z" />
    </SvgIcon>
  );
}

export function ChevronRightIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M8.6 7.4 10 6l6 6-6 6-1.4-1.4 4.6-4.6-4.6-4.6Z" />
    </SvgIcon>
  );
}
