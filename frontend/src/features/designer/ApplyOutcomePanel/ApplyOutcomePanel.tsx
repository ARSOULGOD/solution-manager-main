/**
 * The outcome-handling panel (task 061, Design §10.1/§16/§25 — "the
 * load-bearing part"). A `dp-remote` apply resolves `INDETERMINATE` by
 * default (`applyRepository.ts`'s own header: no "succeeded" variant
 * exists). This panel never derives success or failure from the HTTP
 * response — it shows the observed per-replica state and blocks a retry
 * until the operator explicitly confirms actual (`confirmActual()`,
 * `onConfirm`), then offers retry only from that confirmed state
 * (`onRetry`) — an automatic retry without an idempotency key would be a
 * second write.
 *
 * `DIVERGED` is deliberately not rendered here. The `CONFIG_DIVERGED`
 * remediation banner/diff/rollback flow is task 062's, not this task's —
 * FE-061's own Deps (060, 016, 032) don't reach a `DiffView`-based
 * rollback, and building it here would be scope creep into a different
 * ticket's Acceptance line.
 */
import { Box, Typography } from '@mui/material';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import { Alert, Button } from '@shared/ui';

import { ReplicaTable } from '../ReplicaTable';

import type { ApplyOutcomePanelProps } from './ApplyOutcomePanel.types';

export function ApplyOutcomePanel({
  outcome,
  onConfirm,
  onRetry,
}: ApplyOutcomePanelProps): ReactElement | null {
  if (
    outcome.kind === 'NOT_STARTED' ||
    outcome.kind === 'IN_FLIGHT' ||
    outcome.kind === 'DIVERGED'
  ) {
    return null;
  }

  if (outcome.kind === 'INDETERMINATE') {
    if (outcome.restartObserved === true) {
      return (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          <Alert severity="info" title="Restart observed">
            <Typography variant="body2">
              The explicit operator restart returned and the container was observed again. Effective runtime configuration is not yet verified.
            </Typography>
            <Typography variant="body2" sx={{ mt: 0.5 }}>
              {outcome.reason}
            </Typography>
            <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5, display: 'block' }}>
              {t('applyOutcomeObservedAt', { observedAt: outcome.observedAt })}
            </Typography>
          </Alert>

          <ReplicaTable replicas={outcome.replicas} />
        </Box>
      );
    }
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
        <Alert severity="warn" title={t('applyOutcomeIndeterminateTitle')}>
          <Typography variant="body2">{t('applyOutcomeIndeterminateBody')}</Typography>
          <Typography variant="body2" sx={{ mt: 0.5 }}>
            {outcome.reason}
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5, display: 'block' }}>
            {t('applyOutcomeObservedAt', { observedAt: outcome.observedAt })}
          </Typography>
        </Alert>

        <ReplicaTable replicas={outcome.replicas} />

        <Box>
          <Button onClick={onConfirm}>{t('applyOutcomeConfirmAction')}</Button>
        </Box>
      </Box>
    );
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      <Alert severity="info" title={t('applyOutcomeConfirmedTitle')}>
        {t('applyOutcomeConfirmedBody', { confirmedAt: outcome.confirmedAt })}
      </Alert>

      <ReplicaTable replicas={outcome.replicas} />

      <Box>
        <Button variant="outlined" onClick={onRetry}>
          {t('applyOutcomeRetryAction')}
        </Button>
        <Typography variant="caption" color="text.secondary" sx={{ mt: 0.75, display: 'block' }}>
          {t('applyOutcomeRetryHint')}
        </Typography>
      </Box>
    </Box>
  );
}
