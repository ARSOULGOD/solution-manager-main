/**
 * "Apply requires a restart" (task 061, Design §10.1 — copy prescribed
 * verbatim; reference `designer.jsx`'s own `RestartConfirm`). Calls
 * `ApplyRepository.stage()` itself, exactly like `AcceptRuntimeDialog`
 * calls its own repository method, and hands whatever `ApplyOutcome` it
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
  placementId,
  generation,
  strategy,
  replicas,
  repository,
  onCancel,
  onApplied,
}: RestartConfirmProps): ReactElement {
  const count = replicas.length || 1;

  const apply = useMutation({
    mutationFn: () => {
      if (placementId === undefined || generation === undefined) {
        throw new Error('apply requires a live placement and a saved configuration generation');
      }
      return repository.stage(instanceId, placementId, generation, strategy.key);
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
            disabled={apply.isPending || placementId === undefined || generation === undefined}
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
