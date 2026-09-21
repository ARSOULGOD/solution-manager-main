/**
 * Page measure as a component, not a copied max-width class (FE-015,
 * UI §B.5). Caps at 1240px (`default`) or 1560px (`wide`); below `lg` the
 * cap is larger than the viewport so the page is fluid. Gutters step
 * 40px → 24px → 16px with the theme breakpoints.
 */
import { Box } from '@mui/material';
import type { ReactNode } from 'react';

import { breakpointValues } from '@shared/theme/breakpoints';

export type PageWidthSize = 'default' | 'wide';

export interface PageWidthProps {
  children: ReactNode;
  /** `default` = 1240px cap; `wide` = 1560px (inventory, solutions, Designer, dashboards). */
  size?: PageWidthSize;
}

const MAX_WIDTH: Record<PageWidthSize, number> = {
  default: breakpointValues.lg,
  wide: breakpointValues.xl,
};

export function PageWidth({ children, size = 'default' }: PageWidthProps) {
  return (
    <Box
      data-layout="page-width"
      data-page-width={size}
      sx={(theme) => ({
        width: '100%',
        maxWidth: MAX_WIDTH[size],
        minWidth: 0,
        mx: 'auto',
        boxSizing: 'border-box',
        px: '16px',
        [theme.breakpoints.up('sm')]: { px: '24px' },
        [theme.breakpoints.up('lg')]: { px: '40px' },
      })}
    >
      {children}
    </Box>
  );
}
