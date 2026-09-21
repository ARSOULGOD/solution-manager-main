/**
 * Inline `SvgIcon`s for the shell chrome only — `@mui/icons-material` is
 * not a project dependency (same reasoning as `Button`'s danger marker,
 * task 011, and `shared/ui/internal/toneIcons.tsx`, task 013).
 */
import { SvgIcon } from '@mui/material';
import type { SvgIconProps } from '@mui/material';

export function SunIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10zm0-5a1 1 0 0 1 1 1v1a1 1 0 1 1-2 0V3a1 1 0 0 1 1-1zm0 17a1 1 0 0 1 1 1v1a1 1 0 1 1-2 0v-1a1 1 0 0 1 1-1zm10-7a1 1 0 0 1-1 1h-1a1 1 0 1 1 0-2h1a1 1 0 0 1 1 1zM4 12a1 1 0 0 1-1 1H2a1 1 0 1 1 0-2h1a1 1 0 0 1 1 1zm14.36-6.36a1 1 0 0 1 0 1.42l-.7.7a1 1 0 1 1-1.42-1.42l.7-.7a1 1 0 0 1 1.42 0zM7.06 17.66a1 1 0 0 1 0 1.42l-.7.7a1 1 0 1 1-1.42-1.42l.7-.7a1 1 0 0 1 1.42 0zm11.3 1.42a1 1 0 0 1-1.42 0l-.7-.7a1 1 0 1 1 1.42-1.42l.7.7a1 1 0 0 1 0 1.42zM6.36 6.36a1 1 0 0 1-1.42 0l-.7-.7a1 1 0 0 1 1.42-1.42l.7.7a1 1 0 0 1 0 1.42z" />
    </SvgIcon>
  );
}

export function MoonIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M20.74 14.51a8.5 8.5 0 0 1-11.25-11.25A9 9 0 1 0 20.74 14.5z" />
    </SvgIcon>
  );
}

export function HelpIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm.9 15.5h-1.8v-1.8h1.8v1.8zm1.86-6.98-.81.83c-.65.66-1.05 1.2-1.05 2.15h-1.8v-.45c0-.7.28-1.34.85-1.9l1.12-1.13c.33-.32.51-.77.51-1.25 0-.97-.79-1.77-1.77-1.77a1.77 1.77 0 0 0-1.77 1.77H8.3a3.6 3.6 0 0 1 3.59-3.6 3.6 3.6 0 0 1 3.6 3.6c0 .78-.32 1.49-.83 2z" />
    </SvgIcon>
  );
}

export function CopyIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M16 1H4a2 2 0 0 0-2 2v14h2V3h12V1zm3 4H8a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zm0 16H8V7h11v14z" />
    </SvgIcon>
  );
}

export function MenuIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M3 6h18v2H3V6zm0 5h18v2H3v-2zm0 5h18v2H3v-2z" />
    </SvgIcon>
  );
}

// Account-menu glyphs (task: account popover polish). Stroke-drawn to
// match the reference's Lucide-style `Ic` set — `reference/app/icons.jsx`
// draws `mapPin`, `shield` and `logout` at stroke 1.75, and these are
// those exact paths.
const STROKE = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.75,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
} as const;

export function MapPinIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 21s7-5.5 7-11a7 7 0 1 0-14 0c0 5.5 7 11 7 11Z" {...STROKE} />
      <circle cx="12" cy="10" r="2.5" {...STROKE} />
    </SvgIcon>
  );
}

export function ShieldIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 3 5 6v5c0 4.5 3 7.5 7 9 4-1.5 7-4.5 7-9V6l-7-3Z" {...STROKE} />
      <path d="m9 12 2 2 4-4" {...STROKE} />
    </SvgIcon>
  );
}

export function LogoutIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M14 21H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8" {...STROKE} />
      <path d="M18 16l4-4-4-4M22 12H10" {...STROKE} />
    </SvgIcon>
  );
}
