/**
 * "Accept the runtime" — re-render the declaration against the observed
 * version's schema (task 059, Objective: "the field-level diff doing the
 * load-bearing work"; `architecture.md` §7.2's four-way classification;
 * Design §10/§15, UI §A.8 accept runtime). `DiffView` (task 022) already
 * renders the "needs mapping" caption per `rename` row — this dialog adds
 * only the mapping input and the one rule `architecture.md` §7.2 states:
 * **an unmapped rename must block completion**, because it silently drops
 * a value.
 *
 * **Deliberate expansion beyond the reference.** The reference's own
 * `AcceptRuntimeModal` shows four static category counts and a confirm
 * button with no real mapping step — this task's own Objective line names
 * the diff as "load-bearing," so this renders the real `DiffView` against
 * the real rows and genuinely blocks confirmation on an unmapped rename,
 * rather than reproducing the reference's non-functional mockup.
 */
import { Box, Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { useState, type ReactElement } from 'react';

import { t } from '@shared/i18n';
import { Alert, Button, Dialog, DiffView, TextField } from '@shared/ui';

import type { AcceptRuntimeDialogProps } from './AcceptRuntimeDialog.types';

export function AcceptRuntimeDialog({
  open,
  instanceId,
  toVersion,
  repository,
  rows,
  onClose,
  onAccepted,
}: AcceptRuntimeDialogProps): ReactElement {
  const [renameMap, setRenameMap] = useState<Record<string, string>>({});
  const renameRows = rows.filter((row) => row.kind === 'rename');
  const allMapped = renameRows.every((row) => (renameMap[row.path] ?? '').trim().length > 0);

  const accept = useMutation({
    mutationFn: () => repository.acceptRuntime(instanceId, toVersion, renameMap),
    onSuccess: (result) => onAccepted(result),
  });

  const confirmDisabled = !allMapped || accept.isPending;

  return (
    <Dialog
      open={open}
      title={t('acceptRuntimeDialogTitle', { toVersion })}
      onClose={onClose}
      maxWidth="md"
      fullScreenBelow="sm"
      actions={
        <>
          <Button variant="text" onClick={onClose}>
            {t('cancel')}
          </Button>
          <Button disabled={confirmDisabled} onClick={() => accept.mutate()}>
            {accept.isPending
              ? t('acceptRuntimeDialogPending')
              : t('acceptRuntimeDialogConfirmAction', { toVersion })}
          </Button>
        </>
      }
    >
      <Typography variant="body2" sx={{ mb: 2 }}>
        {t('acceptRuntimeDialogExplain')}
      </Typography>

      <DiffView
        rows={rows}
        labels={[t('acceptRuntimeDialogOldLabel'), t('acceptRuntimeDialogNewLabel')]}
      />

      {renameRows.length > 0 && (
        <Box sx={{ mt: 2 }}>
          <Alert severity="info" title={t('acceptRuntimeDialogMappingHeading')}>
            {t('acceptRuntimeDialogMappingExplain')}
          </Alert>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, mt: 1.5 }}>
            {renameRows.map((row) => (
              <TextField
                key={row.path}
                label={t('acceptRuntimeDialogMappingLabel', { path: row.path })}
                value={renameMap[row.path] ?? ''}
                onChange={(event) =>
                  setRenameMap((current) => ({ ...current, [row.path]: event.target.value }))
                }
              />
            ))}
          </Box>
        </Box>
      )}

      {accept.isError && (
        <Box sx={{ mt: 2 }}>
          <Alert severity="err">{t('acceptRuntimeDialogErrorDetail')}</Alert>
        </Box>
      )}
    </Dialog>
  );
}
