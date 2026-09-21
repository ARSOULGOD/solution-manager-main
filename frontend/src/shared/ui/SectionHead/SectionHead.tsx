/**
 * Section title with an optional small right-hand caption (UI §A.1).
 */
import { Typography } from '@mui/material';

import { Cluster } from '../layout/Cluster';

import type { SectionHeadProps } from './SectionHead.types';

export function SectionHead({ title, caption }: SectionHeadProps) {
  return (
    <Cluster>
      <Typography
        variant="h6"
        component="h2"
        sx={{ minWidth: 0, overflowWrap: 'anywhere', flex: '1 1 auto' }}
      >
        {title}
      </Typography>
      {caption !== undefined && (
        <Typography variant="caption" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>
          {caption}
        </Typography>
      )}
    </Cluster>
  );
}
