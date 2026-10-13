/**
 * The per-solution glyph set, keyed by a package's open `icon` string
 * (task 033's `SolutionPackageSummary.icon`). Inlined `SvgIcon`s, not
 * `@mui/icons-material` — that package is not a project dependency (same
 * reasoning as `toneIcons.tsx` and `Button`'s danger marker, task 011).
 *
 * Lives in `shared/ui` rather than in the Solutions feature (task 039,
 * where it started) because the drawer's solution list (`SidebarNav`,
 * task 024/112) needs the same glyphs and `shared/ui` may not import a
 * feature. Barrelled from `shared/ui/index.ts` exactly as `ToneIcon` is,
 * so both consumers draw one solution's identity from one glyph.
 */
import { SvgIcon } from '@mui/material';
import type { SvgIconProps } from '@mui/material';

export function BoltIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M13 2 4 14h7l-1 8 9-12h-7l1-8Z" />
    </SvgIcon>
  );
}

export function FilesIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <rect x="3" y="3" width="12" height="12" rx="1.5" opacity={0.5} />
      <rect x="9" y="9" width="12" height="12" rx="1.5" />
    </SvgIcon>
  );
}

export function BeakerIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M9 2h6v2h-1v5.5l4.6 8.1A2 2 0 0 1 16.9 21H7.1a2 2 0 0 1-1.7-3.4L10 9.5V4H9V2Zm2 2v6l-1.2 2.1h4.4L13 10V4h-2Z" />
    </SvgIcon>
  );
}

export function PackageGlyphIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 2 3 7v10l9 5 9-5V7l-9-5Zm0 2.24L18.5 8 12 11.53 5.5 8 12 4.24ZM5 9.6l6 3.33v7.14l-6-3.33V9.6Zm14 0v7.14l-6 3.33v-7.14L19 9.6Z" />
    </SvgIcon>
  );
}

const GLYPHS: Record<string, (props: SvgIconProps) => ReturnType<typeof SvgIcon>> = {
  bolt: BoltIcon,
  files: FilesIcon,
  beaker: BeakerIcon,
};

/** The fixture's `icon` field is an open `string` (task 033) — fall back to a generic package glyph for a value not in the known set, and for a package that names no glyph at all. */
export function SolutionGlyph({ name, ...rest }: SvgIconProps & { name?: string | undefined }) {
  const Glyph = (name === undefined ? undefined : GLYPHS[name]) ?? PackageGlyphIcon;
  return <Glyph {...rest} />;
}
