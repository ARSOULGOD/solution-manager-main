/**
 * Page measure as a component, not a copied max-width class (FE-015,
 * UI §B.5). Caps at 1240px (`default`), 1560px (`wide`), 1680px
 * (`instance` — Instance Governance/Monitoring) or 1920px (`designer` —
 * Instance Designer, which needs the most working room for the
 * Composer's resizable panes). Below `lg` the cap is larger than the
 * viewport so the page is fluid. Gutters step 40px → 24px → 16px with
 * the theme breakpoints.
 *
 * `instance`/`designer` are plain layout constants scoped to this
 * component's own size vocabulary — same precedent as `reflowFloorPx`
 * in `breakpoints.ts` — not a second set of MUI breakpoints (that file's
 * own warning is about responsive/media-query logic, which these are
 * never used for).
 */
import { Box } from '@mui/material';
import type { ReactNode } from 'react';

import { breakpointValues } from '@shared/theme/breakpoints';

export type PageWidthSize = 'default' | 'wide' | 'instance' | 'designer';

export interface PageWidthProps {
  children: ReactNode;
  /**
   * `default` = 1240px cap; `wide` = 1560px (inventory, solutions,
   * connections, admin, dashboards); `instance` = 1680px (Instance
   * Governance/Monitoring); `designer` = 1920px (Instance Designer).
   */
  size?: PageWidthSize;
}

const MAX_WIDTH: Record<PageWidthSize, number> = {
  default: breakpointValues.lg,
  wide: breakpointValues.xl,
  instance: 1680,
  designer: 1920,
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
