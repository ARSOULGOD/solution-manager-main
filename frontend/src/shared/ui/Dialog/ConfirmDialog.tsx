/**
 * Confirm + high-consequence confirm (FE-016, FE-D9). `consequence` drives
 * both the banner copy and the danger button — a high-consequence action
 * cannot be confirmed without that banner rendering.
 */
import { t } from '@shared/i18n';

import { Alert } from '../Alert';
import { Button } from '../Button';

import { Dialog } from './Dialog';
import type { ConfirmDialogProps, Consequence, HighConsequenceDialogProps } from './Dialog.types';

function consequenceCopy(consequence: Consequence): string {
  return consequence === 'outage' ? t('consequenceOutage') : t('consequenceHigh');
}

export function ConfirmDialog({
  open,
  title,
  children,
  consequence,
  onConfirm,
  onCancel,
  confirmLabel,
  dirty = false,
}: ConfirmDialogProps) {
  return (
    <Dialog
      open={open}
      title={title}
      onClose={onCancel}
      dirty={dirty}
      actions={
        <>
          <Button variant="text" onClick={onCancel}>
            {t('cancel')}
          </Button>
          <Button
            variant={consequence !== undefined ? 'dangerContained' : 'contained'}
            onClick={onConfirm}
          >
            {confirmLabel}
          </Button>
        </>
      }
    >
      {consequence !== undefined && (
        <div data-consequence={consequence}>
          <Alert severity="warn">{consequenceCopy(consequence)}</Alert>
        </div>
      )}
      {children}
    </Dialog>
  );
}

export function HighConsequenceDialog(props: HighConsequenceDialogProps) {
  return <ConfirmDialog {...props} />;
}
