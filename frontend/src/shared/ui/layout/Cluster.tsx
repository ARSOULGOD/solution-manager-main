/**
 * Wrapping horizontal group — toolbars, action rows, chip rows.
 * Items wrap rather than overflow the page body (G16).
 */
import { Box } from '@mui/material';
import type { ReactNode } from 'react';

export interface ClusterProps {
  children: ReactNode;
  gap?: number;
}

export function Cluster({ children, gap = 2 }: ClusterProps) {
  return (
    <Box
      data-layout="cluster"
      sx={{
        display: 'flex',
        flexDirection: 'row',
        flexWrap: 'wrap',
        alignItems: 'center',
        gap,
      }}
    >
      {children}
    </Box>
  );
}
