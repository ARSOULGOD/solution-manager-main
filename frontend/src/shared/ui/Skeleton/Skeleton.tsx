/**
 * Skeleton at the dimensions of the content it replaces (FE-019,
 * dynamic-metrics §11.2) so nothing shifts when data arrives.
 */
import { Skeleton as MuiSkeleton } from '@mui/material';

import type { SkeletonProps } from './Skeleton.types';

export function Skeleton({ width, height }: SkeletonProps) {
  return (
    <MuiSkeleton
      variant="rectangular"
      width={width}
      height={height}
      animation="wave"
      data-skeleton
      sx={{ maxWidth: '100%' }}
    />
  );
}
