/**
 * "Roll back to the last uniform generation" (task 062, Dec `B/D12`
 * `SM-D22`, Design §10.1 (R1 finding 5 — a rollback is a config change
 * like any other and gets no lighter confirmation for being a revert;
 * R2 finding 5 confirms it), UI §A.8 rollback; reference `designer.jsx`'s
 * own rollback modal). Calls `ApplyRepository.rollback()` itself, exactly
 * like `RestartConfirm` (task 061) calls `stage()`, and hands whatever
 * `ApplyOutcome` it settles to up via `onApplied` for the same
 * `ApplyOutcomePanel` (task 061) to render — a rollback resolves
 * `INDETERMINATE` too (`applyRepository.ts`'s mock: no generation
 * ordering exists for `dp-remote` here either).
 *
 * **The diff renders before confirmation is even possible** — the
 * confirm button and the `DiffView` are both always present in this
 * dialog's own markup, never gated behind a second step, so there is no
 * code path that reaches the confirm action without the diff already on
 * screen.
 */
import { Box, Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import { Alert, Button, Dialog, DiffView } from '@shared/ui';

import type { RollbackDialogProps } from './RollbackDialog.types';

export function RollbackDialog({
  open,
  instanceId,
  currentGeneration,
  targetGeneration,
  rows,
  repository,
  onCancel,
  onApplied,
}: RollbackDialogProps): ReactElement {
  const rollback = useMutation({
    mutationFn: () => repository.rollback(instanceId, targetGeneration),
    onSuccess: (outcome) => onApplied(outcome),
  });

  return (
    <Dialog
      open={open}
      title={t('rollbackDialogTitle')}
      onClose={onCancel}
      maxWidth="md"
      fullScreenBelow="sm"
      dirty={rollback.isPending}
      actions={
        <>
          <Button variant="text" onClick={onCancel} disabled={rollback.isPending}>
            {t('cancel')}
          </Button>
          <Button
            variant="dangerContained"
            onClick={() => rollback.mutate()}
            disabled={rollback.isPending}
          >
            {rollback.isPending ? t('rollbackDialogPending') : t('rollbackDialogConfirmAction')}
          </Button>
        </>
      }
    >
      <Alert severity="warn" title={t('rollbackDialogAlertTitle')}>
        {t('rollbackDialogAlertBody', { targetGeneration })}
      </Alert>

      <Typography variant="overline" color="text.secondary" sx={{ display: 'block', mt: 2, mb: 1 }}>
        {t('rollbackDialogGenerationHeading', { currentGeneration, targetGeneration })}
      </Typography>

      <DiffView
        rows={rows}
        labels={[
          t('rollbackDialogDiffLabelCurrent', { generation: currentGeneration }),
          t('rollbackDialogDiffLabelTarget', { generation: targetGeneration }),
        ]}
      />

      {rollback.isError && (
        <Box sx={{ mt: 2 }}>
          <Alert severity="err">{t('rollbackDialogErrorDetail')}</Alert>
        </Box>
      )}
    </Dialog>
  );
}
