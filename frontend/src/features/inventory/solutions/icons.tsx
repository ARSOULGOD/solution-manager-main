/**
 * Inline `SvgIcon`s for the Solutions catalog only (task 039, UI §A.5) —
 * `@mui/icons-material` is not a project dependency (same reasoning as
 * `features/home/icons.tsx`). Provenance and review-state icons reuse the
 * shared `ToneIcon` vocabulary (`ok`/`warn`/`err`) instead of one-off
 * glyphs, so a signed/reviewed package reads through the same colour and
 * shape language as everywhere else in the app.
 *
 * The per-solution glyphs (`SolutionGlyph`, `PackageGlyphIcon`) used to
 * live here. They moved to `shared/ui/internal/solutionGlyphs.tsx` when
 * the drawer's solution list needed them too — `shared/ui` may not import
 * a feature, so one shared set beats two drifting copies.
 */
import { SvgIcon } from '@mui/material';
import type { SvgIconProps } from '@mui/material';

export function ServerIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <rect x="3" y="4" width="18" height="7" rx="1.5" />
      <rect x="3" y="13" width="18" height="7" rx="1.5" />
    </SvgIcon>
  );
}

export function ActivityIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M3 12h3l2 7 4-16 2 7h3v2h-4.5l-1.3-4.5L8.7 20 6.5 12H3v-2Z" transform="translate(0,1)" />
    </SvgIcon>
  );
}

export function ArrowRightIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 4 10.6 5.4 16.2 11H4v2h12.2l-5.6 5.6L12 20l8-8-8-8Z" />
    </SvgIcon>
  );
}

/** Same glyph as `features/home/icons.tsx`'s `UploadIcon` — a feature cannot import another feature's module (boundaries), so this is its own copy. */
export function UploadIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 3 7 8h3v6h4V8h3l-5-5ZM5 18h14v2H5v-2Z" />
    </SvgIcon>
  );
}

/** Same glyph as `features/home/icons.tsx`'s `LockIcon` — see `UploadIcon`'s note above. */
export function LockIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 17a2 2 0 0 0 2-2 2 2 0 0 0-2-2 2 2 0 0 0-2 2 2 2 0 0 0 2 2Zm6-9a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2h1V6a5 5 0 0 1 10 0v2h1ZM12 3a3 3 0 0 0-3 3v2h6V6a3 3 0 0 0-3-3Z" />
    </SvgIcon>
  );
}
