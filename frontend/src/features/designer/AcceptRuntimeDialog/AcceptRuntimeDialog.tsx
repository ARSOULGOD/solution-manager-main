/**
 * "Accept the runtime" — adopt what the host's own configuration file maps
 * as the new declaration (task 059, Design §10/§15, UI §A.8 accept
 * runtime). Rewired 2026-10-09 against the real `acceptRuntimeConfig`
 * contract: `{baseStateVersion, placementId}` in, `AcceptedRuntimeConfig`
 * out.
 *
 * **Why this replaces the rename-mapping UI entirely, not just its data
 * source.** The original design (task 059's own objective, and the
 * reference's non-functional `AcceptRuntimeModal`) assumed a server-computed
 * diff with renamed fields the operator maps by hand. The real operation
 * has no such shape: it reads the placement's file once, maps what it can,
 * and reports what it could NOT map as `notAccepted[]` — each entry a path
 * and a closed reason (`secret-not-read`, `undeclared-key`,
 * `sm-owned-overlay`, `inactive-field`, `yaml-field-not-accepted`), never a
 * value (`I6` — a secret's value is never read back, and `comparison`
 * reports only `same`/`differs`). There is nothing to map by hand: a
 * `notAccepted` field is either reverted by the next apply (the stored
 * generation still has its old value) or was never SM's concern (the
 * overlay/yaml cases). So this dialog explains the operation, runs it, and
 * lists what it reports — it does not ask the operator to resolve anything.
 */
import { Box, Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import { Alert, Button, Dialog } from '@shared/ui';

import type { NotAcceptedField } from '../configRepository';

import type { AcceptRuntimeDialogProps } from './AcceptRuntimeDialog.types';

function reasonMessage(field: NotAcceptedField): string {
  switch (field.reason) {
    case 'secret-not-read':
      return t('acceptRuntimeDialogReasonSecretNotRead', { path: field.path });
    case 'undeclared-key':
      return t('acceptRuntimeDialogReasonUndeclaredKey', { path: field.path });
    case 'sm-owned-overlay':
      return t('acceptRuntimeDialogReasonSmOwnedOverlay', { path: field.path });
    case 'inactive-field':
      return t('acceptRuntimeDialogReasonInactiveField', { path: field.path });
    case 'yaml-field-not-accepted':
      return t('acceptRuntimeDialogReasonYamlFieldNotAccepted', { path: field.path });
  }
}

export function AcceptRuntimeDialog({
  open,
  instanceId,
  placementId,
  baseStateVersion,
  repository,
  onClose,
  onAccepted,
}: AcceptRuntimeDialogProps): ReactElement {
  const accept = useMutation({
    mutationFn: () => {
      if (placementId === undefined) {
        throw new Error('acceptRuntime: no placementId');
      }
      return repository.acceptRuntime(instanceId, { baseStateVersion, placementId });
    },
    onSuccess: (result) => onAccepted(result),
  });

  const confirmDisabled = placementId === undefined || accept.isPending;
  const result = accept.data;

  return (
    <Dialog
      open={open}
      title={t('acceptRuntimeDialogTitle')}
      onClose={onClose}
      maxWidth="sm"
      fullScreenBelow="sm"
      actions={
        <>
          <Button variant="text" onClick={onClose}>
            {t('cancel')}
          </Button>
          <Button disabled={confirmDisabled} onClick={() => accept.mutate()}>
            {accept.isPending ? t('acceptRuntimeDialogPending') : t('acceptRuntimeDialogConfirmAction')}
          </Button>
        </>
      }
    >
      <Typography variant="body2" sx={{ mb: 2 }}>
        {t('acceptRuntimeDialogExplain')}
      </Typography>

      {result !== undefined && (
        <Box sx={{ mt: 2 }}>
          {result.unchanged && (
            <Alert severity="ok">{t('acceptRuntimeDialogUnchanged')}</Alert>
          )}
          <Typography variant="subtitle2" sx={{ mt: 1.5, mb: 0.5 }}>
            {t('acceptRuntimeDialogNotAcceptedHeading')}
          </Typography>
          {result.notAccepted.length === 0 ? (
            <Typography variant="body2">{t('acceptRuntimeDialogNotAcceptedEmpty')}</Typography>
          ) : (
            <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
              {result.notAccepted.map((field) => (
                <Typography component="li" variant="body2" key={field.path}>
                  {reasonMessage(field)}
                </Typography>
              ))}
            </Box>
          )}
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
