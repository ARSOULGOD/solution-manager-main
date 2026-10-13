/**
 * The 403 page (task 045, proposed `FE-D27`, design §14/§15, UI §A.12.7).
 * States that the action is not permitted and shows the **server's**
 * correlation id — it never names the missing permission. Naming it would
 * tell an unauthorised caller exactly which verb to acquire, and the
 * recovery path (ask an administrator, quote the correlation id) is
 * identical either way. If a security review later permits naming it,
 * that is a copy change in this one component.
 *
 * `correlationId` is required, not optional: this renders only from a
 * server-returned 403, never a client-side guess, so there is always one
 * to show. No call site wires a real 403 into this yet (none of the two
 * `grantable` verbs have a live apply/bind flow in this phase) — this
 * component is built and tested standalone, ready for the task that adds
 * one.
 *
 * Renders inside `AppShell`'s own `<main>` (task 036), so — unlike
 * `NotFound.tsx`, which predates that wiring — this does not render a
 * second `<main>` landmark of its own.
 */
import { Box, Typography } from '@mui/material';
import { useNavigate } from 'react-router';

import { t } from '@shared/i18n';
import { Alert, Button, PageHeader } from '@shared/ui';
import { PageWidth, Stack } from '@shared/ui/layout';

export interface ForbiddenProps {
  correlationId: string;
}

export function Forbidden({ correlationId }: ForbiddenProps) {
  const navigate = useNavigate();

  return (
    <PageWidth>
      <Stack gap={3}>
        <PageHeader title={t('forbiddenTitle')} />
        <Alert severity="neutral">
          <Typography variant="body2">{t('forbiddenBody')}</Typography>
          <Typography variant="body2" sx={{ mt: 1 }}>
            {t('forbiddenCorrelationId', { correlationId })}
          </Typography>
        </Alert>
        <Box>
          <Button variant="outlined" onClick={() => void navigate('/')}>
            {t('forbiddenBackHome')}
          </Button>
        </Box>
      </Stack>
    </PageWidth>
  );
}
