/**
 * Rail-plus-content that stacks when its container is narrower than
 * `collapseBelow` (default `lg`). The shell overlay-drawer behaviour is
 * task 106; this primitive is the layout pattern only.
 */
import { Box } from '@mui/material';
import type { ReactNode } from 'react';

import { breakpointValues, containerQuery, type BreakpointKey } from '@shared/theme/breakpoints';

export interface SidebarProps {
  rail: ReactNode;
  children: ReactNode;
  collapseBelow?: Exclude<BreakpointKey, 'xs'>;
  gap?: number;
}

export function Sidebar({ rail, children, collapseBelow = 'lg', gap = 2 }: SidebarProps) {
  const stackedUntil = breakpointValues[collapseBelow];
  const wide = containerQuery(stackedUntil);

  return (
    // A container query never matches the element that declares the
    // container — it matches against the nearest ancestor container. The
    // `inline-size` container and the `@container` rule that keys off it
    // must therefore live on two different elements (see Grid.tsx), or
    // the rail never sits beside the content and just stacks forever.
    <Box data-layout="sidebar" sx={{ containerType: 'inline-size', minWidth: 0 }}>
      <Box
        data-layout="sidebar-track"
        sx={{
          display: 'flex',
          flexDirection: 'column',
          gap,
          minWidth: 0,
          [wide]: {
            flexDirection: 'row',
            alignItems: 'stretch',
          },
        }}
      >
        <Box
          data-layout="sidebar-rail"
          sx={{
            width: '100%',
            flexShrink: 0,
            minWidth: 0,
            [wide]: {
              width: 'var(--drawer-w)',
            },
          }}
        >
          {rail}
        </Box>
        <Box
          data-layout="sidebar-content"
          sx={{
            flex: '1 1 auto',
            minWidth: 0,
          }}
        >
          {children}
        </Box>
      </Box>
    </Box>
  );
}
