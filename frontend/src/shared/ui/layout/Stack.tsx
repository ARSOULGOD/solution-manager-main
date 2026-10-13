/**
 * Vertical rhythm. Features use this instead of a one-off column flex.
 */
import { Box } from '@mui/material';
import type { ReactNode } from 'react';

export interface StackProps {
  children: ReactNode;
  /** Theme spacing units (4px). Default 2 = 8px grid. */
  gap?: number;
}

export function Stack({ children, gap = 2 }: StackProps) {
  return (
    <Box data-layout="stack" sx={{ display: 'flex', flexDirection: 'column', gap }}>
      {children}
    </Box>
  );
}
