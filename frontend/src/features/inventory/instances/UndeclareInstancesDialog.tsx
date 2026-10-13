/**
 * Undeclare confirmation (task 117). The inverse of `DeclareInstanceDialog`
 * and nothing more.
 *
 * **The copy carries the whole point of this action.** An operator reading
 * "Remove" on an instance row will reasonably assume it stops or deletes
 * the workload — every other console they have used works that way. It
 * does not, and cannot: Solution Manager is a config/governance plane and
 * the customer's own CI/CD owns lifecycle (`SM-D16`, `I1`). So the dialog
 * states both halves explicitly — what IS removed (SM's record, config of
 * record, staged changes, history) and what is NOT (the running process,
 * which keeps running unmanaged). A confirmation that left that ambiguous
 * would be worse than no action at all.
 *
 * Consequence is `high`, not `outage`: nothing restarts and nothing stops,
 * so claiming an outage would be the same category of lie in the other
 * direction. What makes it high is the config history, which does not come
 * back when the instance is re-declared.
 */
import { Box, Typography } from '@mui/material';

import type { Instance } from '@shared/api/contracts/instances';
import { t } from '@shared/i18n';
import { Alert, ConfirmDialog } from '@shared/ui';

export interface UndeclareInstancesDialogProps {
  open: boolean;
  instances: readonly Instance[];
  onConfirm: () => void;
  onCancel: () => void;
  /** A partial failure, named in place rather than delivered only as a toast (design §16). */
  errorMessage?: string;
  submitting?: boolean;
}

export function UndeclareInstancesDialog({
  open,
  instances,
  onConfirm,
  onCancel,
  errorMessage,
  submitting = false,
}: UndeclareInstancesDialogProps) {
  const count = instances.length;

  return (
    <ConfirmDialog
      open={open}
      title={t('instancesUndeclareTitle', { count })}
      consequence="high"
      confirmLabel={t('instancesUndeclareConfirm', { count })}
      onConfirm={onConfirm}
      onCancel={onCancel}
      dirty={submitting}
    >
      <Typography variant="body2" sx={{ mt: 1 }}>
        {t('instancesUndeclareLead')}
      </Typography>

      <Box component="ul" data-undeclare-list sx={{ pl: 2.25, my: 1.5, listStyle: 'disc' }}>
        {instances.map((instance) => (
          <Typography component="li" variant="body2" key={instance.instanceId}>
            <strong>{instance.name}</strong> ·{' '}
            {t('replicaCount', { count: instance.placements.length })}
          </Typography>
        ))}
      </Box>

      <Box sx={{ mt: 1 }}>
        <Alert severity="info">{t('instancesUndeclareNotStopped')}</Alert>
      </Box>

      <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 1.5 }}>
        {t('instancesUndeclareReversible')}
      </Typography>

      {errorMessage !== undefined && (
        <Box sx={{ mt: 1.5 }}>
          <Alert severity="err" title={t('instancesUndeclareFailedTitle')}>
            {errorMessage}
          </Alert>
        </Box>
      )}
    </ConfirmDialog>
  );
}
