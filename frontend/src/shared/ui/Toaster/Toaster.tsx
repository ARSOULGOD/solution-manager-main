/**
 * Mutation acknowledgement (FE-021, FE-D13). Lives in `aria-live="polite"`.
 * Auto-dismiss ~6s. Every toast carries the server correlation id. Errors
 * that require action are *not* delivered only as a toast — they render
 * as an `Alert` in place (design §16).
 */
import { Box, IconButton as MuiIconButton, Stack } from '@mui/material';
import { useEffect } from 'react';

import { t } from '@shared/i18n';

import { Alert } from '../Alert';

import type { ToastRecord } from './Toaster.types';
import { useToastStore } from './toastStore';

const AUTO_DISMISS_MS = 6000;

export function Toaster() {
  const toasts = useToastStore((state) => state.toasts);
  const dismiss = useToastStore((state) => state.dismiss);

  return (
    <Stack
      role="region"
      aria-live="polite"
      aria-label={t('toastRegion')}
      spacing={1}
      sx={{
        position: 'fixed',
        right: 16,
        bottom: 16,
        zIndex: 1400,
        maxWidth: 420,
        width: 'min(420px, calc(100% - 32px))',
      }}
    >
      {toasts.map((toast) => (
        <ToastItem key={toast.id} toast={toast} onDismiss={dismiss} />
      ))}
    </Stack>
  );
}

function ToastItem({ toast, onDismiss }: { toast: ToastRecord; onDismiss: (id: string) => void }) {
  useEffect(() => {
    const timer = window.setTimeout(() => onDismiss(toast.id), AUTO_DISMISS_MS);
    return () => window.clearTimeout(timer);
  }, [toast.id, onDismiss]);

  return (
    <Box data-toast data-correlation-id={toast.correlationId}>
      <Alert
        severity={toast.severity}
        title={toast.title}
        action={
          <MuiIconButton aria-label={t('dismiss')} onClick={() => onDismiss(toast.id)} size="small">
            <span aria-hidden>×</span>
          </MuiIconButton>
        }
      >
        {toast.detail}
        <Box
          component="div"
          sx={{ fontFamily: 'JetBrains Mono, ui-monospace, monospace', mt: 0.5 }}
        >
          {t('correlationId', { id: toast.correlationId })}
        </Box>
      </Alert>
    </Box>
  );
}
