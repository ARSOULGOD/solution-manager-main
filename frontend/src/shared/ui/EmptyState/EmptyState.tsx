/**
 * Empty state (FE-019, FE-D10). Copy must name *why* it is empty and what
 * to do; this component is never mounted from an error path — errors use
 * `Alert` / `ErrorPanel`.
 */
import { Box, Typography } from '@mui/material';

import type { EmptyStateProps } from './EmptyState.types';

export function EmptyState({ icon, title, body, action }: EmptyStateProps) {
  return (
    <Box
      data-empty-state
      sx={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        textAlign: 'center',
        gap: 2,
        py: 6,
        px: 3,
        minWidth: 0,
      }}
    >
      {icon !== undefined && (
        <Box
          aria-hidden
          sx={(theme) => ({
            width: 56,
            height: 56,
            borderRadius: '50%',
            display: 'grid',
            placeItems: 'center',
            backgroundColor: theme.palette.action.hover,
          })}
        >
          {icon}
        </Box>
      )}
      <Typography variant="h6" component="h2">
        {title}
      </Typography>
      <Typography
        variant="body2"
        color="text.secondary"
        sx={{ maxWidth: 480, overflowWrap: 'anywhere' }}
      >
        {body}
      </Typography>
      {action}
    </Box>
  );
}
