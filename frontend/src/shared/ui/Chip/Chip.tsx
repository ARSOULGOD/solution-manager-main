/**
 * The base chip every tone-carrying badge in the app renders through
 * (design §9, UI §A.1). Colour comes from `theme.palette.tone` (task 003's
 * palette factory extended for this task) — never a literal here, per
 * `FE-D5`.
 */
import { Box, Chip as MuiChip, useTheme } from '@mui/material';
import type { ReactElement } from 'react';

import type { ChipProps } from './Chip.types';

// MUI's Chip clones its `icon` element to inject its own `.MuiChip-icon`
// class (verified against `@mui/material/Chip` source, 2026-08-20) — the
// clone lands on this component's own props, so `className` has to be
// accepted and forwarded, not just implied, or the injected class never
// reaches the rendered DOM node.
function Dot({ color, className }: { color: string; className?: string }) {
  return (
    <Box
      component="span"
      aria-hidden
      className={className}
      sx={{
        width: 8,
        height: 8,
        borderRadius: '50%',
        backgroundColor: color,
        display: 'inline-block',
      }}
    />
  );
}

export function Chip({ label, tone, icon, dot = false, mono = false }: ChipProps) {
  const theme = useTheme();
  const { main, bg } = theme.palette.tone[tone];
  const leading = icon ?? (dot ? <Dot color={main} /> : undefined);

  return (
    <MuiChip
      label={label}
      size="small"
      {...(leading !== undefined ? { icon: leading as ReactElement } : {})}
      sx={{
        backgroundColor: bg,
        color: main,
        border: `1px solid ${main}`,
        fontWeight: theme.typography.fontWeightMedium,
        '& .MuiChip-icon': { color: main },
        '& .MuiChip-label': {
          color: main,
          ...(mono ? { fontFamily: 'monospace' } : {}),
        },
      }}
    />
  );
}
