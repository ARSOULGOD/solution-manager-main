/**
 * Column count follows the *container's* width, not the viewport
 * (task 105: a widget in a 3-column dashboard and the same widget stacked
 * are one component). Breakpoints come from the token layer.
 */
import { Box } from '@mui/material';
import type { SxProps, Theme } from '@mui/material/styles';
import type { ReactNode } from 'react';

import {
  breakpointKeys,
  breakpointValues,
  columnsAtWidth,
  containerQuery,
  type BreakpointKey,
} from '@shared/theme/breakpoints';

export interface GridProps {
  children: ReactNode;
  columns?: Partial<Record<BreakpointKey, number>>;
  gap?: number;
}

const DEFAULT_COLUMNS: Partial<Record<BreakpointKey, number>> = {
  xs: 1,
  sm: 2,
  md: 2,
  lg: 3,
  xl: 4,
};

export function Grid({ children, columns = DEFAULT_COLUMNS, gap = 2 }: GridProps) {
  const xs = columns.xs ?? 1;
  // A container query never matches the element that declares the
  // container — it matches against the nearest ancestor container. The
  // `inline-size` container and the `@container` rules that key off it
  // must therefore live on two different elements, or the query never
  // fires and the grid is stuck at its `xs` column count forever.
  const trackSx: SxProps<Theme> = {
    display: 'grid',
    gap,
    minWidth: 0,
    gridTemplateColumns: `repeat(${String(xs)}, minmax(0, 1fr))`,
  };

  for (const key of breakpointKeys) {
    if (key === 'xs') continue;
    const count = columnsAtWidth(breakpointValues[key], columns);
    Object.assign(trackSx, {
      [containerQuery(breakpointValues[key])]: {
        gridTemplateColumns: `repeat(${String(count)}, minmax(0, 1fr))`,
      },
    });
  }

  return (
    <Box data-layout="grid" sx={{ containerType: 'inline-size', minWidth: 0 }}>
      <Box data-layout="grid-track" sx={trackSx}>
        {children}
      </Box>
    </Box>
  );
}
