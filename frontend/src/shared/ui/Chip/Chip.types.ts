import type { ReactNode } from 'react';

/** `purple` is reserved for the `PROD` env tone (§A.13.1) — no task-013 vocabulary uses it yet. */
export type ChipTone = 'ok' | 'warn' | 'err' | 'info' | 'idle' | 'neutral' | 'purple';

export interface ChipProps {
  label: string;
  tone: ChipTone;
  /** A leading icon. Mutually exclusive with `dot` — `icon` wins if both are given. */
  icon?: ReactNode;
  /** A small leading dot instead of an icon, for chips with no vocabulary icon of their own. */
  dot?: boolean;
  /**
   * Renders the label in the monospace face — for a chip whose label is an
   * IDENTIFIER rather than a word (a package version, a build tag). The
   * reference does exactly this for the package-version badge
   * (`docs/design/frontend/reference/app/instances.jsx:93`:
   * `<Chip tone="neutral" sm><span className="mono">pkg v{…}</span></Chip>`).
   */
  mono?: boolean;
}
