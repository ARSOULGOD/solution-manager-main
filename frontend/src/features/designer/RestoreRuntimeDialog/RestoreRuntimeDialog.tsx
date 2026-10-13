/**
 * "Restore the runtime" — the other route out of the blocked banner (task
 * 059, Design §10/§15, UI §A.8; `architecture.md` §7.2). Unlike "Accept
 * the runtime", this does not change the declaration — it re-applies the
 * current declaration to the host, overwriting drift, which restarts
 * every replica of the placement. `HighConsequenceDialog` (task 016) is
 * built for exactly this: confirm even when authorised, consequence
 * banner mandatory.
 *
 * Rewired 2026-10-09 against the real `restoreRuntimeConfig` contract:
 * `{placementId, strategy: 'manual'}` — always `manual` here (no restart
 * strategy picker; `rolling-no-validation`/`restart-all` need
 * `instance.restart.overrideSafety`/`outageConfirmedBy`, out of this
 * dialog's scope). The `202 ApplyAccepted` response is admitted, not
 * awaited — this dialog does not poll `/apply/observe`.
 */
import { useMutation } from '@tanstack/react-query';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import { Alert, HighConsequenceDialog } from '@shared/ui';

import type { RestoreRuntimeDialogProps } from './RestoreRuntimeDialog.types';

export function RestoreRuntimeDialog({
  open,
  instanceId,
  placementId,
  declaredVersion,
  repository,
  onCancel,
  onRestored,
}: RestoreRuntimeDialogProps): ReactElement {
  const restore = useMutation({
    mutationFn: () => {
      if (placementId === undefined) {
        throw new Error('restoreRuntime: no placementId');
      }
      return repository.restoreRuntime(instanceId, { placementId, strategy: 'manual' });
    },
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
      onConfirm={() => {
        if (placementId !== undefined) {
          restore.mutate();
        }
      }}
      onCancel={onCancel}
    >
      <p>{t('restoreRuntimeDialogExplain', { declared: declaredVersion })}</p>
      {restore.isError && <Alert severity="err">{t('restoreRuntimeDialogErrorDetail')}</Alert>}
    </HighConsequenceDialog>
  );
}
