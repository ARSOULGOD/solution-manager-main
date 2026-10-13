/**
 * Title, sub-line, right-hand action slot (FE-015). The action slot is a
 * wrapping cluster so buttons wrap rather than disappear — omitting an
 * action is how FE-D9 signals *unauthorised*, so dropping one to save
 * space would look like a permission denial (G13).
 */
import { Box, Typography } from '@mui/material';

import { Cluster } from '../layout/Cluster';

import type { PageHeaderProps } from './PageHeader.types';

export function PageHeader({ title, sub, actions }: PageHeaderProps) {
  return (
    <Box
      data-page-header
      sx={(theme) => ({
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'stretch',
        gap: 2,
        minWidth: 0,
        [theme.breakpoints.up('md')]: {
          flexDirection: 'row',
          alignItems: 'flex-start',
          justifyContent: 'space-between',
        },
      })}
    >
      <Box sx={{ minWidth: 0, flex: '1 1 auto' }}>
        <Typography variant="h4" component="h1" sx={{ overflowWrap: 'anywhere' }}>
          {title}
        </Typography>
        {sub !== undefined && (
          <Typography
            variant="body2"
            color="text.secondary"
            sx={{ mt: 1, overflowWrap: 'anywhere' }}
          >
            {sub}
          </Typography>
        )}
      </Box>
      {actions !== undefined && (
        <Cluster>
          <Box data-page-header-actions sx={{ display: 'contents' }}>
            {actions}
          </Box>
        </Cluster>
      )}
    </Box>
  );
}
