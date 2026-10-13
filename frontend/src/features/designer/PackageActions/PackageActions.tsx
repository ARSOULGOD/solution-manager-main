/**
 * Package-declared actions (task 058, Dec `UI-D22` `I6` `FE-D23`, Design
 * §10, UI §A.8 package actions; reference `designer.jsx`'s own "Actions"
 * card). `action.kind` is closed at `test-connection ·
 * manage-catalog-entry` (`UI-D22`) — the package names only a `key` and its
 * `inputs` (design.md §3.5), never a label, so display copy is keyed off
 * `kind` here rather than trusting an authored string.
 *
 * **Server-side only, verdict only** (`UI-D22`, `I6`): `ActionsRepository.run`
 * (task 033) takes only `instanceId` and the action's own `key` — no field
 * VALUE ever crosses this boundary, so a resolved secret cannot reach the
 * request body even by mistake (`actionsRepository.typecheck.test.ts` is
 * the compile-time proof). The response's `message` is server-composed and
 * rendered verbatim, exactly like `OverrideReasonDialog` already renders a
 * validation's own `message` — it is data, not UI copy, so it never goes
 * through `t()`.
 *
 * Each row owns its own mutation rather than one shared across the list,
 * so running one action never shows a stale pending/verdict state on a
 * sibling row.
 */
import { Box, Typography, useTheme } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import type { ReactElement } from 'react';

import type { ActionKind, ViewAction } from '@contracts/view-model/types';
import { t } from '@shared/i18n';
import { Alert, Button, Paper } from '@shared/ui';

import type { ActionsRepository } from '../actionsRepository';

import type { PackageActionsProps } from './PackageActions.types';

// Narrowed to these three literal keys (not the broad `MessageKey` union) so
// `t()`'s plural-aware overload still resolves a plain optional-params call.
type ActionLabelKey = 'packageActionsTestConnectionLabel' | 'packageActionsManageCatalogEntryLabel';

const ACTION_LABEL_KEY: Record<ActionKind, ActionLabelKey> = {
  'test-connection': 'packageActionsTestConnectionLabel',
  'manage-catalog-entry': 'packageActionsManageCatalogEntryLabel',
};

interface ActionRowProps {
  instanceId: string;
  action: ViewAction;
  repository: ActionsRepository;
  disabled: boolean;
}

function ActionRow({ instanceId, action, repository, disabled }: ActionRowProps): ReactElement {
  const theme = useTheme();
  const label = t(ACTION_LABEL_KEY[action.kind]);
  const run = useMutation({
    mutationFn: () => repository.run(instanceId, action.key),
  });

  const rowDisabled = disabled || !action.enabled || run.isPending;

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
      <Box
        sx={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 1.5,
        }}
      >
        <Box>
          <Typography variant="body2" sx={{ fontWeight: theme.typography.fontWeightMedium }}>
            {label}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {t('packageActionsDescription', { inputs: action.inputs.join(', ') })}
          </Typography>
        </Box>
        <Button variant="outlined" disabled={rowDisabled} onClick={() => run.mutate()}>
          {run.isPending ? t('packageActionsRunPending') : label}
        </Button>
      </Box>

      {run.isSuccess ? (
        <Alert severity={run.data.ok ? 'ok' : 'err'}>{run.data.message}</Alert>
      ) : null}
      {run.isError ? <Alert severity="err">{t('packageActionsErrorDetail')}</Alert> : null}
    </Box>
  );
}

export function PackageActions({
  instanceId,
  sections,
  repository,
  disabled = false,
}: PackageActionsProps): ReactElement | null {
  const actions = sections.flatMap((section) => section.actions);
  if (actions.length === 0) {
    return null;
  }

  return (
    <Paper surface="padded">
      <Typography variant="overline" color="text.secondary" sx={{ display: 'block', mb: 1.5 }}>
        {t('packageActionsSectionTitle')}
      </Typography>
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        {actions.map((action) => (
          <ActionRow
            key={action.key}
            instanceId={instanceId}
            action={action}
            repository={repository}
            disabled={disabled}
          />
        ))}
      </Box>
    </Paper>
  );
}
