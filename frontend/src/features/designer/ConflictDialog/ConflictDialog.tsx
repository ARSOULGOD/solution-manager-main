/**
 * `409` conflict dialog (task 056, Dec `FE-D18` clause A, `I6` · Design
 * §10.1, §16 · UI §A.8 conflict dialog). `SaveBar` (task 055) calls
 * `onSaveError` on every failed save, including a `409` — this is the UI
 * that seam hooks into.
 *
 * **No invented resolution.** The `409` body shape is `API CONTRACT — TBD`
 * (§16 Q9) — no actor, no timestamp — and the contract specifies no
 * conflict-resolution mechanism at all: no "overwrite anyway", no "take
 * theirs", no per-field merge. Those three render disabled with a stated
 * "not specified" reason rather than being omitted, so an operator sees
 * what does not exist yet instead of wondering whether it was missed.
 * **Refetch & review** is the only working path: it reloads the current
 * record through `ConfigRepository.loadDesigner` — a real, specified call
 * — and hands the result back to the caller, which resets its own draft
 * baseline. That split (`onRefetched`, not a close-on-success side effect)
 * is the same one `SaveBar.onSaved` and `LoadPackageDialog.onLoaded`
 * already use, so a refetch failure leaves the caller's state untouched.
 */
import { Box } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import { Alert, Button, Dialog, DiffView, Tooltip } from '@shared/ui';

import type { ConflictDialogProps } from './ConflictDialog.types';

/** Same nested-interactive-a11y reasoning as `SaveBar.tooltipIfDisabled`: the explanatory tooltip only ever wraps a DISABLED button. */
function disabledResolution(label: string): ReactElement {
  return (
    <Tooltip title={t('conflictDialogNotSpecifiedTooltip')}>
      <Button variant="outlined" disabled>
        {label}
      </Button>
    </Tooltip>
  );
}

export function ConflictDialog({
  open,
  instanceId,
  repository,
  rows,
  onClose,
  onRefetched,
}: ConflictDialogProps): ReactElement {
  const refetch = useMutation({
    mutationFn: () => repository.loadDesigner(instanceId),
    onSuccess: (load) => {
      onRefetched(load);
    },
  });

  return (
    <Dialog
      open={open}
      title={t('conflictDialogTitle')}
      onClose={onClose}
      maxWidth="md"
      fullScreenBelow="sm"
      actions={
        <>
          {disabledResolution(t('conflictDialogOverwriteAction'))}
          {disabledResolution(t('conflictDialogTakeTheirsAction'))}
          {disabledResolution(t('conflictDialogMergeAction'))}
          <Button disabled={refetch.isPending} onClick={() => refetch.mutate()}>
            {refetch.isPending
              ? t('conflictDialogRefetchPending')
              : t('conflictDialogRefetchAction')}
          </Button>
        </>
      }
    >
      <Alert severity="warn" title={t('conflictDialogAlertTitle')}>
        {t('conflictDialogExplain')}
      </Alert>

      <Box sx={{ mt: 2 }}>
        <DiffView
          rows={rows}
          labels={[t('conflictDialogDiffLabelMine'), t('conflictDialogDiffLabelTheirs')]}
        />
      </Box>

      {refetch.isError && (
        <Box sx={{ mt: 2 }}>
          <Alert severity="err" title={t('conflictDialogRefetchErrorTitle')}>
            {t('conflictDialogRefetchErrorDetail')}
          </Alert>
        </Box>
      )}
    </Dialog>
  );
}
