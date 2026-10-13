/**
 * Inline `SvgIcon`s for the Home screen only (task 038, UI §A.4) —
 * `@mui/icons-material` is not a project dependency (same reasoning as
 * `app/shell/icons.tsx` and `shared/ui/internal/toneIcons.tsx`).
 */
import { SvgIcon } from '@mui/material';
import type { SvgIconProps } from '@mui/material';

export function PackageIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 2 3 7v10l9 5 9-5V7l-9-5Zm0 2.24L18.5 8 12 11.53 5.5 8 12 4.24ZM5 9.6l6 3.33v7.14l-6-3.33V9.6Zm14 0v7.14l-6 3.33v-7.14L19 9.6Z" />
    </SvgIcon>
  );
}

export function GridIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <rect x="3" y="3" width="8" height="8" rx="1.5" />
      <rect x="13" y="3" width="8" height="8" rx="1.5" />
      <rect x="3" y="13" width="8" height="8" rx="1.5" />
      <rect x="13" y="13" width="8" height="8" rx="1.5" />
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

export function MapPinIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 21s7-5.5 7-11a7 7 0 1 0-14 0c0 5.5 7 11 7 11Z" />
    </SvgIcon>
  );
}

export function UploadIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 3 7 8h3v6h4V8h3l-5-5ZM5 18h14v2H5v-2Z" />
    </SvgIcon>
  );
}

export function ChevronRightIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6-6-6Z" />
    </SvgIcon>
  );
}

export function LockIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 17a2 2 0 0 0 2-2 2 2 0 0 0-2-2 2 2 0 0 0-2 2 2 2 0 0 0 2 2Zm6-9a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2h1V6a5 5 0 0 1 10 0v2h1ZM12 3a3 3 0 0 0-3 3v2h6V6a3 3 0 0 0-3-3Z" />
    </SvgIcon>
  );
}
