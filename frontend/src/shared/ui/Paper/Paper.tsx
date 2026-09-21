/**
 * Surface primitive (UI §A.1). Elevation and padding come from the
 * named `surface` prop — callers do not copy a max-width or padding class.
 *
 * Task 129 adds `fill`, for a card that must occupy a sized container
 * rather than sit at its natural height — a dashboard widget in a grid
 * cell. It stays a named boolean for the same reason `surface` is: the
 * contract is that a caller picks a described behaviour, not that it
 * hand-rolls layout through an `sx` escape hatch.
 */
import { Paper as MuiPaper } from '@mui/material';

import type { PaperProps } from './Paper.types';

export function Paper({ children, surface = 'padded', fill = false, dashed = false }: PaperProps) {
  const padded = surface !== 'flat';
  const hoverable = surface === 'hoverable';

  return (
    <MuiPaper
      data-surface={surface}
      data-fill={fill ? '' : undefined}
      data-dashed={dashed ? '' : undefined}
      elevation={0}
      sx={(theme) => ({
        minWidth: 0,
        ...(padded ? { p: 3 } : { p: 0 }),
        // `overflow: hidden` so the header rule and the rounded corners
        // agree — the reference's own `.widget` carries both.
        ...(fill
          ? { height: '100%', display: 'flex', flexDirection: 'column', overflow: 'hidden' }
          : {}),
        border: `1px ${dashed ? 'dashed' : 'solid'} ${theme.palette.divider}`,
        ...(hoverable
          ? {
              transition: theme.transitions.create(['box-shadow', 'border-color']),
              '&:hover': {
                boxShadow: theme.shadows[8],
              },
            }
          : {}),
      })}
    >
      {children}
    </MuiPaper>
  );
}
