/**
 * Bulk restart confirmation (task 043, `SM-D15`, `SM-D22`, `FE-D18` clause B
 * (blocked) · UI §A.6). Enabled only with a selection; states, per
 * instance, name/replica count/expected unavailability plus the rule that
 * writing configuration is not the same as applying it.
 *
 * ⚠️ **The action itself does not execute in v1** — multi-instance apply
 * has no protocol (open item 1) and single-instance apply is
 * `APPLY_INDETERMINATE` by default (C11). `onConfirm` only closes the
 * dialog: no mutation, no toast, no outcome badge. Wire the call when open
 * item 1 closes.
 */
import { Box, Typography } from '@mui/material';

import type { Instance } from '@shared/api/contracts/instances';
import { t } from '@shared/i18n';
import { ConfirmDialog } from '@shared/ui';

export interface BulkRestartDialogProps {
  open: boolean;
  instances: readonly Instance[];
  onConfirm: () => void;
  onCancel: () => void;
}

export function BulkRestartDialog({ open, instances, onConfirm, onCancel }: BulkRestartDialogProps) {
  return (
    <ConfirmDialog
      open={open}
      title={t('instancesBulkRestartTitle')}
      consequence="outage"
      onConfirm={onConfirm}
      onCancel={onCancel}
      confirmLabel={t('instancesBulkRestartConfirm', { count: instances.length })}
    >
      <Typography variant="body2">{t('instancesBulkRestartRule')}</Typography>
      <Box component="ul" data-bulk-restart-list sx={{ pl: 2.25, my: 1.5, listStyle: 'disc' }}>
        {instances.map((instance) => (
          <Typography key={instance.instanceId} component="li" variant="body2" sx={{ mb: 0.5 }}>
            <Box component="span" sx={{ fontFamily: 'monospace', fontWeight: 600 }}>
              {instance.name}
            </Box>
            {' · '}
            {t('replicaCount', { count: instance.placements.length })}
            {' · '}
            {t('instancesBulkRestartUnavailability')}
          </Typography>
        ))}
      </Box>
      <Typography variant="caption" color="text.secondary">
        {t('instancesBulkRestartClosingNote')}
      </Typography>
    </ConfirmDialog>
  );
}
