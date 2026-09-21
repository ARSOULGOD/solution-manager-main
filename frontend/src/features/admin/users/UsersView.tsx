/**
 * Users tab (task 094, `D13` 🔒 deferred, `D19` 🔒, `FE-D38`, `FE-D50`, UI §A.12).
 * Local accounts, explicitly a stopgap an identity provider will replace.
 * **Deactivate, never delete** — an account that authored configuration
 * history stays attributable. Creating a user does **not** assign a role
 * (see `AddUserDialog`). A banner states what an IdP migration removes
 * (Add user, Deactivate) and what survives (the list).
 *
 * **The bootstrap super admin is REPRESENTED here, never created here**
 * (`D19` 🔒, `FE-D50`). `AdminRepository` still has no
 * `bootstrapFirstAdmin()` and must not grow one. Its row is marked, and
 * its Deactivate control is **disabled with the reason** when it is the
 * last active one — `FE-D9`'s capability-limit branch, not its RBAC
 * branch: the operator holds the permission, the *system* refuses.
 * Password hashing, forced replacement at first sign-in, the audit record
 * and session invalidation are install-time/backend obligations and are
 * labelled as such on screen.
 *
 * Fixture-backed — `AdminRepository` is `CONTRACT: TBD`; Phase 7 stays
 * unscheduled.
 */
import { Box, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import type { ReactElement } from 'react';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import type { LocalUser } from '@shared/testdoubles/data/rbac';
import { Alert, Button, Chip, Skeleton } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { Stack } from '@shared/ui/layout';

import { AddUserDialog } from './AddUserDialog';
import type { UsersViewProps } from './UsersView.types';

function describeError(error: unknown): string {
  return error instanceof ApiError ? error.message : t('errorGeneric');
}

function UserCell({ user }: { user: LocalUser }): ReactElement {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
      <Typography component="span" variant="body2" sx={{ fontWeight: 600 }}>
        {user.name}
      </Typography>
      <Typography component="span" variant="caption" color="text.secondary" noWrap>
        {user.email}
      </Typography>
    </Box>
  );
}

export function UsersView({ repository }: UsersViewProps): ReactElement {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);

  const usersQuery = useQuery({
    queryKey: queryKeys.admin.users(),
    queryFn: ({ signal }) => repository.users(signal),
  });

  const setActive = useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) =>
      repository.setUserActive(id, active),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.users() });
    },
  });

  if (usersQuery.isPending) {
    return (
      <Stack gap={2}>
        <Skeleton width={220} height={28} />
        <Skeleton width="100%" height={200} />
      </Stack>
    );
  }

  if (usersQuery.isError) {
    return (
      <ErrorPanel
        title={t('adminUsersErrorTitle')}
        detail={describeError(usersQuery.error)}
        onRetry={() => void usersQuery.refetch()}
      />
    );
  }

  const users = usersQuery.data;
  const activeBootstrapAdmins = users.filter(
    (user) => user.isBootstrapAdmin === true && user.status === 'active',
  ).length;

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
            {t('adminUsersHeading')}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {t('adminUsersSubheading')}
          </Typography>
        </Box>
        <Button size="sm" onClick={() => setDialogOpen(true)}>
          {t('adminUsersAdd')}
        </Button>
      </Box>

      <Alert severity="warn" title={t('adminUsersIdpBannerTitle')} fullWidth>
        {t('adminUsersIdpBannerBody')}
      </Alert>

      <Box sx={{ overflowX: 'auto' }}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>{t('adminUsersColUser')}</TableCell>
              <TableCell>{t('adminUsersColOrganization')}</TableCell>
              <TableCell>{t('adminUsersColStatus')}</TableCell>
              <TableCell>{t('adminUsersColLastSignIn')}</TableCell>
              <TableCell>{t('adminUsersColCreatedBy')}</TableCell>
              <TableCell align="right">
                <Box
                  component="span"
                  sx={{
                    position: 'absolute',
                    width: 1,
                    height: 1,
                    p: 0,
                    m: -1,
                    overflow: 'hidden',
                    clip: 'rect(0, 0, 0, 0)',
                    whiteSpace: 'nowrap',
                    border: 0,
                  }}
                >
                  {t('adminUsersColActions')}
                </Box>
              </TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {users.map((user) => {
              const active = user.status === 'active';
              // `D19` 🔒: an install with no ACTIVE bootstrap super admin
              // cannot be recovered from this console, so the last one's
              // Deactivate is refused here as well as `409`-ed by the
              // server. Reactivation is never blocked.
              const lastBootstrapAdmin =
                user.isBootstrapAdmin === true && active && activeBootstrapAdmins <= 1;
              return (
                <TableRow key={user.id}>
                  <TableCell>
                    <UserCell user={user} />
                    {user.isBootstrapAdmin === true && (
                      <Chip tone="info" label={t('adminUsersBootstrapChip')} />
                    )}
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" color="text.secondary">
                      {user.organization}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Chip
                      tone={active ? 'ok' : 'idle'}
                      label={
                        active ? t('adminUsersStatusActive') : t('adminUsersStatusDeactivated')
                      }
                    />
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" color="text.secondary">
                      {active ? user.lastSignIn : t('adminUsersNeverSignedIn')}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" color="text.secondary">
                      {user.createdBy}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">
                    <Button
                      variant="text"
                      size="sm"
                      disabled={setActive.isPending || lastBootstrapAdmin}
                      {...(lastBootstrapAdmin
                        ? { title: t('adminUsersLastBootstrapAdminReason') }
                        : {})}
                      onClick={() => setActive.mutate({ id: user.id, active: !active })}
                    >
                      {active ? t('adminUsersDeactivate') : t('adminUsersReactivate')}
                    </Button>
                    {lastBootstrapAdmin && (
                      <Typography variant="caption" color="text.secondary" component="p">
                        {t('adminUsersLastBootstrapAdminReason')}
                      </Typography>
                    )}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </Box>

      <Typography variant="caption" color="text.secondary">
        {t('adminUsersNoDeleteNote')}
      </Typography>

      <Alert severity="neutral" title={t('adminUsersBootstrapChip')}>
        {t('adminUsersBootstrapNote')}
      </Alert>
      <Alert severity="neutral">{t('adminUsersBootstrapBackendNote')}</Alert>

      {dialogOpen && <AddUserDialog repository={repository} onClose={() => setDialogOpen(false)} />}
    </Stack>
  );
}
