/**
 * Distinguishes the two ways an operator lands on `/login` already
 * carrying a reason (task 037, task 031's `RequireAuth`): the server no
 * longer recognised the session (`arrival=session`, `warn`) vs the
 * client's own idle timer expired (`arrival=idle`, `info`). Neither is an
 * error the operator caused. The idle copy's "your edits were saved"
 * promise is `FE-D25` — it exists specifically so this sentence is true.
 */
import type { ArrivalReason } from '@shared/auth/AuthProvider';
import { t } from '@shared/i18n';
import { Alert } from '@shared/ui';

export interface LoginArrivalAlertProps {
  arrival: ArrivalReason;
  /** From `RuntimeConfig.idleTimeoutMinutes` — the idle copy states the real configured value. */
  idleTimeoutMinutes: number;
}

export function LoginArrivalAlert({ arrival, idleTimeoutMinutes }: LoginArrivalAlertProps) {
  if (arrival === 'session') {
    return (
      <Alert severity="warn" title={t('loginSessionTitle')} fullWidth>
        {t('loginSessionBody')}
      </Alert>
    );
  }

  if (arrival === 'idle') {
    return (
      <Alert
        severity="info"
        title={t('loginIdleTitle', { count: idleTimeoutMinutes })}
        fullWidth
      >
        {t('loginIdleBody')}
      </Alert>
    );
  }

  return null;
}
