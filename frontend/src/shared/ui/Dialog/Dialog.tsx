/**
 * Trapped, labelled, escapable dialog (FE-016). MUI's Dialog already
 * traps focus, restores it to the trigger, sets `aria-modal`, and closes
 * on Escape (verified against `@mui/material/Dialog` source). This wrapper
 * wires `aria-labelledby` from the title and refuses a scrim-click close
 * while `dirty` so an in-progress form is not discarded by an outside click.
 *
 * `fullScreenBelow` (task 108, `FE-D29`) drives MUI's own `fullScreen`
 * prop. `useMediaQuery` is called unconditionally on every render — the
 * `?? 'xs'` fallback queries `theme.breakpoints.down('xs')`, which never
 * matches any real width (the `xs` floor is 0), so an omitted
 * `fullScreenBelow` always resolves `fullScreen` to `false` without a
 * conditional hook call (Rules of Hooks).
 */
import {
  Dialog as MuiDialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton as MuiIconButton,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import { useId } from 'react';

import { t } from '@shared/i18n';

import type { DialogProps } from './Dialog.types';

export function Dialog({
  open,
  title,
  children,
  onClose,
  dirty = false,
  maxWidth = 'sm',
  actions,
  fullScreenBelow,
}: DialogProps) {
  const titleId = useId();
  const theme = useTheme();
  const isBelowFullScreenBreakpoint = useMediaQuery(theme.breakpoints.down(fullScreenBelow ?? 'xs'));
  const fullScreen = fullScreenBelow !== undefined && isBelowFullScreenBreakpoint;

  return (
    <MuiDialog
      open={open}
      onClose={(_event, reason) => {
        if (reason === 'backdropClick' && dirty) {
          return;
        }
        onClose();
      }}
      aria-labelledby={titleId}
      maxWidth={maxWidth}
      fullWidth
      fullScreen={fullScreen}
    >
      <DialogTitle id={titleId} sx={{ display: 'flex', alignItems: 'center', pr: 6 }}>
        {title}
        <MuiIconButton
          aria-label={t('close')}
          onClick={onClose}
          sx={{ position: 'absolute', right: 8, top: 8 }}
        >
          <span aria-hidden>×</span>
        </MuiIconButton>
      </DialogTitle>
      <DialogContent>{children}</DialogContent>
      {actions !== undefined && <DialogActions>{actions}</DialogActions>}
    </MuiDialog>
  );
}
