/**
 * Access check — effective permissions (task 093, `D10` 🔒, UI §A.12).
 * Given a principal and a scope: exactly what they hold here, and which
 * assignment conferred each grant. This is the "why was I denied?"
 * screen — deny-by-default is only operable if it is debuggable, so a
 * grant made above the selected scope reads as **inherited**, never as if
 * granted directly, and the naming of the conferring assignment is the
 * point of the screen.
 *
 * A user's effective permissions are the deduplicated UNION of three
 * sources (Groups management §8): their own direct assignments, the
 * assignments of every active group they belong to, and anything a parent
 * scope confers on this one. Each row therefore names EVERY source, not
 * only the winning one — removing one assignment does not remove access
 * another still grants, and an operator who cannot see that removes the
 * wrong one. Semantics are additive allow only: the ratified model has no
 * deny rule and this screen invents none.
 *
 * A deactivated user or group grants nothing; its assignment rows stay
 * readable for audit and the screen says which case it is looking at.
 *
 * The frontend never decides what is granted — the server does, on every
 * request (design §15). This view renders `repository.effectivePermissions`
 * and nothing more. Fixture-backed: `AdminRepository` is `CONTRACT: TBD`
 * (§16 Q4/Q5); Phase 7 stays unscheduled.
 */
import { Box, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useQueries, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import type { ReactElement } from 'react';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { PERMISSION_CATALOG } from '@shared/authz/catalog';
import type { PermissionDef } from '@shared/authz/permissions';
import { t } from '@shared/i18n';
import { Alert, Button, Chip, Paper, Select, Skeleton, ToneIcon } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { Stack } from '@shared/ui/layout';

import { PrincipalTag, ScopeTree, scopePathString } from '../assignments';
import { scopeLevelLabel, scopeLevelOf } from '../assignments/scopePath';

import type { AccessCheckViewProps } from './AccessCheckView.types';
import { firstDeniedPermissionId, groupEffectiveGrants } from './effectiveGrants';
import type { EffectiveGrantSource } from './effectiveGrants';

const PERMISSION_BY_ID = new Map<string, PermissionDef>(
  PERMISSION_CATALOG.map((entry) => [entry.id, entry]),
);

function describeError(error: unknown): string {
  return error instanceof ApiError ? error.message : t('errorGeneric');
}

/**
 * Where one grant came from, in the two dimensions that matter and are
 * independent of each other (§8): WHOSE assignment it is (the principal's
 * own, or a group they belong to) and WHERE it was made (here, or at an
 * ancestor level that this scope inherits from).
 */
function GrantSource({
  source,
  roleName,
}: {
  source: EffectiveGrantSource;
  roleName: string;
}): ReactElement {
  const direct = source.grantType === 'direct';
  const level = scopeLevelLabel(scopeLevelOf(source.grantingScope));
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.25, minWidth: 0 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, flexWrap: 'wrap' }}>
        <Typography component="span" variant="body2">
          {roleName}
        </Typography>
        <Chip
          tone={source.via.kind === 'group' ? 'info' : 'neutral'}
          label={
            source.via.kind === 'group'
              ? t('adminAccessSourceGroup', { group: source.via.groupName })
              : t('adminAccessSourceDirect')
          }
        />
        <Chip
          tone={direct ? 'ok' : 'idle'}
          label={direct ? t('adminAccessGrantDirect') : t('adminAccessGrantInherited')}
        />
      </Box>
      <Typography variant="caption" color="text.secondary">
        {direct
          ? t('adminAccessGrantedAtLevel', { level })
          : t('adminAccessInheritedFrom', { level })}
        {' · '}
        {t('adminAccessGrantedAt', { scope: scopePathString(source.grantingScope) })}
      </Typography>
    </Box>
  );
}

export function AccessCheckView({ repository }: AccessCheckViewProps): ReactElement {
  const [selectedPrincipalId, setSelectedPrincipalId] = useState<string | null>(null);
  const [selectedScope, setSelectedScope] = useState<readonly string[] | null>(null);

  const [principalsQuery, rolesQuery, scopeTreeQuery] = useQueries({
    queries: [
      {
        queryKey: queryKeys.admin.principals(),
        queryFn: ({ signal }: { signal: AbortSignal }) => repository.principals(signal),
      },
      {
        queryKey: queryKeys.admin.roles(),
        queryFn: ({ signal }: { signal: AbortSignal }) => repository.roles(signal),
      },
      {
        queryKey: queryKeys.admin.scopeTree(),
        queryFn: ({ signal }: { signal: AbortSignal }) => repository.scopeTree(signal),
      },
    ],
  });

  const principals = principalsQuery.data;
  const scopeTree = scopeTreeQuery.data;
  const principalId = selectedPrincipalId ?? principals?.[0]?.id ?? null;
  // Defaults to `[]` — the synthetic root, all organizations (`FE-D46`).
  const scope: readonly string[] = selectedScope ?? [];

  const grantsQuery = useQuery({
    queryKey: queryKeys.admin.effectivePermissions(principalId ?? '', scope),
    queryFn: ({ signal }) => repository.effectivePermissions(principalId as string, scope, signal),
    // NO `scope.length > 0` guard: `[]` is a legal scope now — the global
    // one — and testing for a non-empty path would silently suppress the
    // query at exactly the scope a Solace Super-admin is granted at
    // (`FE-D46`).
    enabled: principalId !== null,
  });

  const setupQueries = [principalsQuery, rolesQuery, scopeTreeQuery];
  if (setupQueries.some((query) => query.isPending)) {
    return (
      <Stack gap={2}>
        <Skeleton width={220} height={28} />
        <Skeleton width="100%" height={220} />
      </Stack>
    );
  }

  const failed = setupQueries.find((query) => query.isError);
  if (failed !== undefined || !principals || !rolesQuery.data || !scopeTree) {
    return (
      <ErrorPanel
        title={t('adminAccessErrorTitle')}
        detail={describeError(failed?.error)}
        onRetry={() => {
          void principalsQuery.refetch();
          void rolesQuery.refetch();
          void scopeTreeQuery.refetch();
        }}
      />
    );
  }

  const roles = rolesQuery.data;
  const roleById = new Map(roles.map((role) => [role.id, role]));
  const principal = principals.find((candidate) => candidate.id === principalId);
  const principalOptions = principals.map((candidate) => ({
    value: candidate.id,
    label: `${candidate.name} · ${
      candidate.kind === 'group' ? t('adminPrincipalKindGroup') : t('adminPrincipalKindUser')
    }`,
  }));

  const rows = groupEffectiveGrants(grantsQuery.data ?? []);
  const sampleDenied = firstDeniedPermissionId(rows.map((row) => row.permissionId));

  return (
    <Stack gap={2}>
      <Box>
        <Typography variant="h6" component="h2">
          {t('adminAccessHeading')}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {t('adminAccessSubheading')}
        </Typography>
      </Box>

      <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'flex-start' }}>
        <Box sx={{ flex: '0 0 300px' }}>
          <Paper>
            <Stack gap={2}>
              <Select
                label={t('adminAccessPrincipalLabel')}
                value={principalId ?? ''}
                onChange={setSelectedPrincipalId}
                options={principalOptions}
              />
              <Box>
                <Typography variant="overline" component="p" sx={{ mb: 1 }}>
                  {t('adminAccessScopeLabel')}
                </Typography>
                <Box sx={{ maxHeight: 260, overflow: 'auto' }}>
                  <ScopeTree node={scopeTree} selected={scope} onSelect={setSelectedScope} />
                </Box>
              </Box>
            </Stack>
          </Paper>
        </Box>

        <Box sx={{ flex: 1, minWidth: 320 }}>
          <Paper>
            <Stack gap={1.5}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
                {principal ? <PrincipalTag principal={principal} showSub={false} /> : null}
                <Typography variant="body2" color="text.secondary">
                  {t('adminAccessAtScope')}
                </Typography>
                <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600 }}>
                  {scopePathString(scope)}
                </Typography>
              </Box>

              {grantsQuery.isError ? (
                <Alert
                  severity="err"
                  title={t('adminAccessGrantsErrorTitle')}
                  action={
                    <Button size="sm" variant="text" onClick={() => void grantsQuery.refetch()}>
                      {t('retry')}
                    </Button>
                  }
                >
                  {describeError(grantsQuery.error)}
                </Alert>
              ) : grantsQuery.isPending ? (
                <Skeleton width="100%" height={160} />
              ) : rows.length === 0 ? (
                <Alert severity="warn" title={t('adminAccessNoneTitle')}>
                  {principal !== undefined && principal.status !== 'active'
                    ? t('adminAccessPrincipalDeactivated')
                    : `${t('adminAccessNoneBody')} ${t('adminAccessDeniedNoGrant')}`}
                </Alert>
              ) : (
                <Box sx={{ overflowX: 'auto' }}>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>{t('adminAccessColPermission')}</TableCell>
                        <TableCell>{t('adminAccessColConferredBy')}</TableCell>
                        <TableCell>{t('adminAccessColSource')}</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {rows.map((row) => {
                        const highConsequence =
                          PERMISSION_BY_ID.get(row.permissionId)?.highConsequence === true;
                        const direct = row.grantType === 'direct';
                        return (
                          <TableRow key={row.permissionId}>
                            <TableCell>
                              <Box
                                sx={{
                                  display: 'flex',
                                  alignItems: 'center',
                                  gap: 0.75,
                                  flexWrap: 'wrap',
                                }}
                              >
                                <Typography
                                  component="span"
                                  variant="body2"
                                  sx={{ fontFamily: 'monospace' }}
                                >
                                  {row.permissionId}
                                </Typography>
                                {highConsequence && (
                                  <Box
                                    component="span"
                                    sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.25 }}
                                  >
                                    <ToneIcon tone="warn" fontSize="small" />
                                    <Typography component="span" variant="caption">
                                      {t('adminRoleHighConsequence')}
                                    </Typography>
                                  </Box>
                                )}
                              </Box>
                            </TableCell>
                            <TableCell>
                              <Box
                                sx={{
                                  display: 'flex',
                                  alignItems: 'center',
                                  gap: 0.75,
                                  flexWrap: 'wrap',
                                }}
                              >
                                <Typography component="span" variant="body2">
                                  {roleById.get(row.roleId)?.name ?? row.roleId}
                                </Typography>
                                <Chip
                                  tone={direct ? 'ok' : 'idle'}
                                  label={
                                    direct
                                      ? t('adminAccessGrantDirect')
                                      : t('adminAccessGrantInherited')
                                  }
                                />
                              </Box>
                              <Typography variant="caption" color="text.secondary">
                                {t('adminAccessGrantedAt', {
                                  scope: scopePathString(row.grantingScope),
                                })}
                              </Typography>
                            </TableCell>
                            <TableCell>
                              {/* EVERY source, not only the winning one —
                                  removing one assignment does not remove
                                  access that another still confers. */}
                              <Stack gap={1}>
                                {row.sources.map((source) => (
                                  <GrantSource
                                    key={`${source.roleId}-${source.grantingScope.join('/')}-${
                                      source.via.kind === 'group' ? source.via.groupId : 'direct'
                                    }`}
                                    source={source}
                                    roleName={roleById.get(source.roleId)?.name ?? source.roleId}
                                  />
                                ))}
                              </Stack>
                            </TableCell>
                          </TableRow>
                        );
                      })}
                    </TableBody>
                  </Table>
                </Box>
              )}

              <Alert severity="neutral">
                <Typography variant="body2">{t('adminAccessDenialExplained')}</Typography>
                {sampleDenied !== undefined && (
                  <Typography variant="body2" sx={{ mt: 0.5 }}>
                    {t('adminAccessDenialExample', { permission: sampleDenied })}
                  </Typography>
                )}
              </Alert>
            </Stack>
          </Paper>
        </Box>
      </Box>
    </Stack>
  );
}
