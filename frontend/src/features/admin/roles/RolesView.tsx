/**
 * Role definitions (task 091, `D10` 🔒, UI §A.12). A role is a named
 * permission set; assignment is a separate screen (FE-092) and roles
 * never reference a principal. Built-in roles are read-only, inspectable
 * and cloneable; custom roles are editable. Everything here is
 * fixture-backed — `AdminRepository` is `CONTRACT: TBD` (§16 Q4/Q5).
 */
import { Box, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import type { ReactElement } from 'react';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import type { RbacRole } from '@shared/testdoubles/data/rbac';
import { Button, Chip, Paper, Skeleton, ToneIcon } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { Grid, Stack } from '@shared/ui/layout';

import { RoleEditor } from './RoleEditor';
import type { RoleEditorMode } from './RoleEditor.types';
import { expandRolePermissions, includesHighConsequence } from './rolePermissions';
import type { RolesViewProps } from './RolesView.types';

interface EditorState {
  role: RbacRole | null;
  mode: RoleEditorMode;
}

function describeError(error: unknown): string {
  return error instanceof ApiError ? error.message : t('errorGeneric');
}

function RoleCard({ role, onOpen }: { role: RbacRole; onOpen: () => void }): ReactElement {
  const grantSet = expandRolePermissions(role.permissionIds);
  const countLabel =
    role.permissionIds === '*'
      ? t('adminRoleCardAllPerms')
      : t('adminRoleCardPermCount', { count: grantSet.length });
  return (
    <Paper surface="hoverable">
      <Box
        component="button"
        type="button"
        onClick={onOpen}
        aria-label={role.name}
        sx={{
          all: 'unset',
          display: 'block',
          width: '100%',
          cursor: 'pointer',
          boxSizing: 'border-box',
        }}
      >
        <Stack gap={1}>
          <Box
            sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1 }}
          >
            <Typography variant="subtitle1" component="span">
              {role.name}
            </Typography>
            <Chip
              tone={role.kind === 'built-in' ? 'info' : 'neutral'}
              label={
                role.kind === 'built-in' ? t('adminRoleKindBuiltIn') : t('adminRoleKindCustom')
              }
            />
          </Box>
          <Typography variant="body2" color="text.secondary">
            {role.description}
          </Typography>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
            <Chip tone="neutral" label={countLabel} />
            {includesHighConsequence(grantSet) && (
              <Box
                component="span"
                sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.25 }}
              >
                <ToneIcon tone="warn" fontSize="small" />
                <Typography variant="caption" component="span">
                  {t('adminRoleCardInclHighConsequence')}
                </Typography>
              </Box>
            )}
          </Box>
        </Stack>
      </Box>
    </Paper>
  );
}

export function RolesView({ repository }: RolesViewProps): ReactElement {
  const [editor, setEditor] = useState<EditorState | null>(null);
  const rolesQuery = useQuery({
    queryKey: queryKeys.admin.roles(),
    queryFn: ({ signal }) => repository.roles(signal),
  });

  if (rolesQuery.isPending) {
    return (
      <Stack gap={2}>
        <Skeleton width={220} height={28} />
        <Grid gap={2}>
          {[0, 1, 2, 3].map((row) => (
            <Skeleton key={row} width="100%" height={140} />
          ))}
        </Grid>
      </Stack>
    );
  }

  if (rolesQuery.isError) {
    return (
      <ErrorPanel
        title={t('adminRolesErrorTitle')}
        detail={describeError(rolesQuery.error)}
        onRetry={() => void rolesQuery.refetch()}
      />
    );
  }

  const builtIns = rolesQuery.data.filter((role) => role.kind === 'built-in');
  const customs = rolesQuery.data.filter((role) => role.kind === 'custom');

  const openRole = (role: RbacRole): void =>
    setEditor({ role, mode: role.kind === 'built-in' ? 'inspect' : 'edit' });

  return (
    <Stack gap={2}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-start',
          justifyContent: 'space-between',
          gap: 2,
          flexWrap: 'wrap',
        }}
      >
        <Box>
          <Typography variant="h6" component="h2">
            {t('adminRolesHeading')}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {t('adminRolesSubheading')}
          </Typography>
        </Box>
        <Button size="sm" onClick={() => setEditor({ role: null, mode: 'create' })}>
          {t('adminRolesNewCustomRole')}
        </Button>
      </Box>

      <Typography variant="overline">{t('adminRolesBuiltInSection')}</Typography>
      <Grid gap={2}>
        {builtIns.map((role) => (
          <RoleCard key={role.id} role={role} onOpen={() => openRole(role)} />
        ))}
      </Grid>

      <Typography variant="overline">{t('adminRolesCustomSection')}</Typography>
      <Grid gap={2}>
        {customs.map((role) => (
          <RoleCard key={role.id} role={role} onOpen={() => openRole(role)} />
        ))}
      </Grid>

      {editor !== null && (
        <RoleEditor
          key={`${editor.mode}:${editor.role?.id ?? 'new'}`}
          mode={editor.mode}
          role={editor.role}
          repository={repository}
          onClose={() => setEditor(null)}
          onClone={() =>
            setEditor((current) =>
              current === null ? null : { role: current.role, mode: 'clone' },
            )
          }
        />
      )}
    </Stack>
  );
}
