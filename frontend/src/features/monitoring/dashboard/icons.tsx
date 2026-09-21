/**
 * Inline `SvgIcon`s for `MonitoringFirstRun` only (task 089, UI §A.11.6) —
 * `@mui/icons-material` is not a project dependency (same reasoning as
 * `features/home/icons.tsx` and `shared/ui/internal/toneIcons.tsx`).
 */
import { SvgIcon } from '@mui/material';
import type { SvgIconProps } from '@mui/material';

export function ActivityIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M3 12h4l2-7 4 14 2-7h6" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </SvgIcon>
  );
}

export function BoltIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M13 2 4 14h6l-1 8 9-12h-6l1-8Z" />
    </SvgIcon>
  );
}

export function ServerIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <rect x="3" y="4" width="18" height="7" rx="1.5" />
      <rect x="3" y="13" width="18" height="7" rx="1.5" />
    </SvgIcon>
  );
}

export function ChartIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M4 20V10h3v10H4Zm6.5 0V4h3v16h-3ZM17 20v-7h3v7h-3Z" />
    </SvgIcon>
  );
}

export function SearchIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M15.5 14h-.79l-.28-.27a6.5 6.5 0 1 0-.7.7l.27.28v.79l5 4.99L20.49 19l-4.99-5Zm-6 0A4.5 4.5 0 1 1 14 9.5 4.5 4.5 0 0 1 9.5 14Z" />
    </SvgIcon>
  );
}
