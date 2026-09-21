/**
 * Warning-override reason dialog (task 057, Dec `UI-D16`, Design §10/§16,
 * UI §A.8 override reason dialog; reference `WarnReasonModal` in
 * `docs/design/frontend/reference/app/designer_panels.jsx`). `SaveBar`
 * (task 055) opens this instead of saving directly whenever semantic
 * (recommendation-tier) warnings are outstanding — Save's own disabled
 * rule never gates on them, by design (task 055's report), so this is the
 * only place that reason gets captured.
 *
 * **Minimum-words rule is a deliberate deviation from the reference.** The
 * reference's own confirm-disabled check is `reason.trim().length < 4` —
 * four *characters* — while its own helper text reads "Minimum a few
 * words", so the two contradict each other. This implements what the copy
 * actually promises: at least three non-empty, whitespace-separated words.
 */
import { Box, Typography } from '@mui/material';
import { useState, type ReactElement } from 'react';

import { t } from '@shared/i18n';
import { Alert, Button, Dialog, TextArea } from '@shared/ui';

import type { OverrideReasonDialogProps } from './OverrideReasonDialog.types';

const MIN_REASON_WORDS = 3;

function isValidReason(reason: string): boolean {
  return reason.trim().split(/\s+/).filter(Boolean).length >= MIN_REASON_WORDS;
}

export function OverrideReasonDialog({
  open,
  warnings,
  onCancel,
  onConfirm,
}: OverrideReasonDialogProps): ReactElement {
  const [reason, setReason] = useState('');
  const valid = isValidReason(reason);

  const handleCancel = () => {
    setReason('');
    onCancel();
  };

  const handleConfirm = () => {
    onConfirm(reason);
    setReason('');
  };

  return (
    <Dialog
      open={open}
      title={t('overrideReasonDialogTitle')}
      onClose={handleCancel}
      dirty={reason.trim().length > 0}
      actions={
        <>
          <Button variant="text" onClick={handleCancel}>
            {t('cancel')}
          </Button>
          <Button disabled={!valid} onClick={handleConfirm}>
            {t('overrideReasonDialogConfirmAction')}
          </Button>
        </>
      }
    >
      <Alert
        severity="warn"
        title={t('overrideReasonDialogAlertTitle', { count: warnings.length })}
      >
        {t('overrideReasonDialogExplain')}
      </Alert>

      <Box component="ul" sx={{ my: 2, pl: 3 }}>
        {warnings.map((warning) => (
          <Typography
            component="li"
            variant="body2"
            key={`${warning.sectionIndex}-${warning.fieldKey}`}
          >
            <strong>{warning.label}</strong> — {warning.message}
          </Typography>
        ))}
      </Box>

      <TextArea
        label={t('overrideReasonDialogReasonLabel')}
        value={reason}
        onChange={(event) => setReason(event.target.value)}
        helperText={t('overrideReasonDialogReasonHelp')}
      />
    </Dialog>
  );
}
