/**
 * Persistent last-mutation record on the instance header (FE-021).
 * A toast is acknowledgement; this badge is the record (UI §A.7).
 */
import { Box, Typography } from '@mui/material';

import { t } from '@shared/i18n';

import { Alert } from '../Alert';

import type { OutcomeBadgeProps } from './Toaster.types';

export function OutcomeBadge({ title, correlationId, severity }: OutcomeBadgeProps) {
  return (
    <Box data-outcome-badge data-correlation-id={correlationId} sx={{ maxWidth: 360, minWidth: 0 }}>
      <Alert severity={severity} title={title}>
        <Typography
          variant="caption"
          sx={{ fontFamily: 'JetBrains Mono, ui-monospace, monospace' }}
        >
          {t('correlationId', { id: correlationId })}
        </Typography>
      </Alert>
    </Box>
  );
}
