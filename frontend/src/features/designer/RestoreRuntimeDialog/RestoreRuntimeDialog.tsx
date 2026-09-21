/**
 * "Restore the runtime" — the other route out of the blocked banner (task
 * 059, Design §10/§15, UI §A.8; `architecture.md` §7.2). Unlike "Accept
 * the runtime", this does not change the declaration — it instructs the
 * operator-owned pipeline to redeploy the DECLARED version, which restarts
 * every replica. `HighConsequenceDialog` (task 016) is built for exactly
 * this: confirm even when authorised, consequence banner mandatory.
 */
import { useMutation } from '@tanstack/react-query';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import { Alert, HighConsequenceDialog } from '@shared/ui';

import type { RestoreRuntimeDialogProps } from './RestoreRuntimeDialog.types';

export function RestoreRuntimeDialog({
  open,
  instanceId,
  declaredVersion,
  repository,
  onCancel,
  onRestored,
}: RestoreRuntimeDialogProps): ReactElement {
  const restore = useMutation({
    mutationFn: () => repository.restoreRuntime(instanceId),
    onSuccess: () => onRestored(),
  });

  return (
    <HighConsequenceDialog
      open={open}
      title={t('restoreRuntimeDialogTitle')}
      consequence="outage"
      confirmLabel={
        restore.isPending
          ? t('restoreRuntimeDialogPending')
          : t('restoreRuntimeDialogConfirmAction', { declared: declaredVersion })
      }
      dirty={restore.isPending}
      onConfirm={() => restore.mutate()}
      onCancel={onCancel}
    >
      <p>{t('restoreRuntimeDialogExplain', { declared: declaredVersion })}</p>
      {restore.isError && <Alert severity="err">{t('restoreRuntimeDialogErrorDetail')}</Alert>}
    </HighConsequenceDialog>
  );
}
