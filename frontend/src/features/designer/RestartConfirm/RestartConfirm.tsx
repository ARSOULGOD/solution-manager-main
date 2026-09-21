/**
 * "Apply requires a restart" (task 061, Design §10.1 — copy prescribed
 * verbatim; reference `designer.jsx`'s own `RestartConfirm`). Calls
 * stages first, then performs the separately authenticated explicit restart
 * the operator has confirmed, and hands whatever `ApplyOutcome` it
 * settles to up via `onApplied` — never a "succeeded" one (`applyRepository.ts`'s
 * own header: no such variant exists). What that outcome means is
 * `ApplyOutcomePanel`'s job, not this dialog's.
 */
import { Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import { Alert, Button, Dialog } from '@shared/ui';

import type { RestartConfirmProps } from './RestartConfirm.types';

export function RestartConfirm({
  open,
  instanceId,
  strategy,
  replicas,
  repository,
  onCancel,
  onApplied,
}: RestartConfirmProps): ReactElement {
  const count = replicas.length || 1;

  const apply = useMutation({
    mutationFn: async () => {
      const outcome = await repository.stage(instanceId, strategy.key);
      if (repository.restart === undefined) {
        throw new Error('The configured data plane does not expose an explicit restart action.');
      }
      const restart = await repository.restart(instanceId);
      return outcome.kind === 'INDETERMINATE'
        ? { ...outcome, replicas: [restart.replica], restartObserved: true }
        : outcome;
    },
    onSuccess: (outcome) => onApplied(outcome),
  });

  const body =
    strategy.key === 'manual'
      ? t('restartConfirmBodyManual')
      : t('restartConfirmBodyWithStrategy', { strategyLabel: strategy.label.toLowerCase() });

  return (
    <Dialog
      open={open}
      title={t('restartConfirmTitle')}
      onClose={onCancel}
      dirty={apply.isPending}
      actions={
        <>
          <Button variant="text" onClick={onCancel} disabled={apply.isPending}>
            {t('cancel')}
          </Button>
          <Button
            variant="dangerContained"
            onClick={() => apply.mutate()}
            disabled={apply.isPending}
          >
            {apply.isPending ? t('restartConfirmPending') : t('restartConfirmAction', { count })}
          </Button>
        </>
      }
    >
      <Alert severity="warn" title={t('restartConfirmAlertTitle', { count })}>
        {body}
      </Alert>
      <Typography variant="caption" color="text.secondary" sx={{ mt: 1.75, display: 'block' }}>
        {t('restartConfirmObservedCaveat')}
      </Typography>
      {apply.isError && (
        <Alert severity="err" fullWidth>
          {t('restartConfirmErrorDetail')}
        </Alert>
      )}
    </Dialog>
  );
}
