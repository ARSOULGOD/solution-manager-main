/**
 * Assignments tab (task 092, `D10` 🔒, `SM-D23`, UI §A.12). An assignment
 * is the triple (principal, role, scope); roles never reference a
 * principal. Two reads of the same data: **by principal** (a table) and
 * **by scope** (pick a node, see who reaches it). A grant made above the
 * selected node is shown as **inherited**, never as if granted directly —
 * showing it as direct is how someone removes an assignment and is
 * surprised access here did not change. Fixture-backed — `AdminRepository`
 * is `CONTRACT: TBD` (§16 Q4/Q5); Phase 7 stays unscheduled.
 *
 * An assignment at the ROOT scope (`[]`) is installer/database-managed
 * (`D19` 🔒, `FE-D49`). It renders, confers and is inspectable here; the
 * console has no flow that creates one, so the row says so.
 */
import { Box, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useQueries } from '@tanstack/react-query';
import { useState } from 'react';
import type { ReactElement } from 'react';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import type { RbacAssignment } from '@shared/testdoubles/data/rbac';
import { Alert, Button, Chip, Paper, Skeleton, ToggleGroup } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { Stack } from '@shared/ui/layout';

import type { AssignmentsViewProps } from './AssignmentsView.types';
import { NewAssignmentDialog } from './NewAssignmentDialog';
import { PrincipalTag } from './PrincipalTag';
import { isScopePrefix, scopePathString, scopePathsEqual } from './scopePath';
import { ScopeTree } from './ScopeTree';

type Direction = 'principal' | 'scope';

function describeError(error: unknown): string {
  return error instanceof ApiError ? error.message : t('errorGeneric');
}

export function AssignmentsView({ repository }: AssignmentsViewProps): ReactElement {
  const [direction, setDirection] = useState<Direction>('principal');
  const [selectedScope, setSelectedScope] = useState<readonly string[] | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);

  const [assignmentsQuery, principalsQuery, rolesQuery, scopeTreeQuery] = useQueries({
    queries: [
      {
        queryKey: queryKeys.admin.assignments(),
        queryFn: ({ signal }: { signal: AbortSignal }) => repository.assignments(signal),
      },
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

  const queries = [assignmentsQuery, principalsQuery, rolesQuery, scopeTreeQuery];

  if (queries.some((query) => query.isPending)) {
    return (
      <Stack gap={2}>
        <Skeleton width={220} height={28} />
        <Skeleton width="100%" height={200} />
      </Stack>
    );
  }

  const failed = queries.find((query) => query.isError);
  if (
    failed !== undefined ||
    !assignmentsQuery.data ||
    !principalsQuery.data ||
    !rolesQuery.data ||
    !scopeTreeQuery.data
  ) {
    return (
      <ErrorPanel
        title={t('adminAssignmentsErrorTitle')}
        detail={describeError(failed?.error)}
        onRetry={() => {
          void assignmentsQuery.refetch();
          void principalsQuery.refetch();
          void rolesQuery.refetch();
          void scopeTreeQuery.refetch();
        }}
      />
    );
  }

  const assignments = assignmentsQuery.data;
  const principals = principalsQuery.data;
  const roles = rolesQuery.data;
  const scopeTree = scopeTreeQuery.data;

  const principalById = new Map(principals.map((principal) => [principal.id, principal]));
  const roleById = new Map(roles.map((role) => [role.id, role]));
  // The root's path is `[]`, not `[scopeTree.name]` (`FE-D46`) — the
  // synthetic "All organizations" node's NAME is a label, never a path
  // segment, and `ScopeTree` emits `[]` when it is selected. Defaulting to
  // the label asked "who reaches the scope named *All organizations*?",
  // which is a scope that does not exist, so an installer-managed root
  // assignment was not inspectable here at all (`FE-D49`).
  const scope = selectedScope ?? [];
  // Only ACTIVE assignments confer anything — a revoked row stays in the
  // by-principal table for audit but must never appear as reaching a scope.
  const reaching = assignments.filter(
    (assignment) => assignment.status === 'active' && isScopePrefix(assignment.scope, scope),
  );

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
            {t('adminAssignmentsHeading')}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {t('adminAssignmentsSubheading')}
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
          <ToggleGroup<Direction>
            value={direction}
            onChange={setDirection}
            aria-label={t('adminAssignmentsDirLabel')}
            options={[
              { value: 'principal', label: t('adminAssignmentsByPrincipal') },
              { value: 'scope', label: t('adminAssignmentsByScope') },
            ]}
          />
          <Button size="sm" onClick={() => setDialogOpen(true)}>
            {t('adminAssignmentsNew')}
          </Button>
        </Box>
      </Box>

      {direction === 'principal' ? (
        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>{t('adminAssignmentsColPrincipal')}</TableCell>
                <TableCell>{t('adminAssignmentsColPrincipalType')}</TableCell>
                <TableCell>{t('adminAssignmentsColRole')}</TableCell>
                <TableCell>{t('adminAssignmentsColScope')}</TableCell>
                <TableCell>{t('adminAssignmentsColStatus')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {assignments.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5}>{t('adminAssignmentsEmpty')}</TableCell>
                </TableRow>
              ) : (
                assignments.map((assignment: RbacAssignment) => {
                  const principal = principalById.get(assignment.principalId);
                  const role = roleById.get(assignment.roleId);
                  return (
                    <TableRow key={assignment.id}>
                      <TableCell>
                        {principal ? (
                          <PrincipalTag principal={principal} />
                        ) : (
                          assignment.principalId
                        )}
                      </TableCell>
                      <TableCell>
                        {/* §7: the list says which KIND of principal this
                            is, in a column of its own — not only inside
                            the principal cell's own tag. */}
                        <Chip
                          tone={principal?.kind === 'group' ? 'info' : 'neutral'}
                          label={
                            principal?.kind === 'group'
                              ? t('adminAssignmentsPrincipalTypeGroup')
                              : t('adminAssignmentsPrincipalTypeUser')
                          }
                        />
                      </TableCell>
                      <TableCell>
                        <Chip
                          tone={role?.kind === 'built-in' ? 'info' : 'neutral'}
                          label={role?.name ?? assignment.roleId}
                        />
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                          {scopePathString(assignment.scope)}
                        </Typography>
                        {/* `D19` 🔒 / `FE-D49`: a root grant is
                            installer/database-managed. It renders and
                            confers here, but no console flow creates one,
                            so it is labelled rather than left looking like
                            something an administrator made. */}
                        {assignment.scope.length === 0 && (
                          <Box sx={{ mt: 0.5 }}>
                            <Chip tone="info" label={t('adminAssignmentsSystemManaged')} />
                            <Typography
                              variant="caption"
                              color="text.secondary"
                              component="p"
                              sx={{ mt: 0.25 }}
                            >
                              {t('adminAssignmentsSystemManagedHint')}
                            </Typography>
                          </Box>
                        )}
                      </TableCell>
                      <TableCell>
                        <Chip
                          tone={assignment.status === 'active' ? 'ok' : 'idle'}
                          label={
                            assignment.status === 'active'
                              ? t('adminGroupAssignmentsStatusActive')
                              : t('adminAssignmentsRevoked')
                          }
                        />
                      </TableCell>
                    </TableRow>
                  );
                })
              )}
            </TableBody>
          </Table>
        </Box>
      ) : (
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'flex-start' }}>
          <Box sx={{ flex: '0 0 300px' }}>
            <Paper>
              <Typography variant="overline" component="p" sx={{ mb: 1 }}>
                {t('adminAssignmentsPickScope')}
              </Typography>
              <ScopeTree node={scopeTree} selected={scope} onSelect={setSelectedScope} />
            </Paper>
          </Box>
          <Box sx={{ flex: 1, minWidth: 320 }}>
            <Paper>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                {t('adminAssignmentsReachHeading', { scope: scopePathString(scope) })}
              </Typography>
              <Stack gap={1}>
                {reaching.length === 0 ? (
                  <Typography variant="body2" color="text.secondary">
                    {t('adminAssignmentsNoneReach')}
                  </Typography>
                ) : (
                  reaching.map((assignment) => {
                    const principal = principalById.get(assignment.principalId);
                    const role = roleById.get(assignment.roleId);
                    const direct = scopePathsEqual(assignment.scope, scope);
                    return (
                      <Box
                        key={assignment.id}
                        data-confer={assignment.id}
                        sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1.5 }}
                      >
                        <Box
                          sx={{
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between',
                            gap: 1,
                          }}
                        >
                          {principal ? (
                            <PrincipalTag principal={principal} showSub={false} />
                          ) : (
                            assignment.principalId
                          )}
                          <Chip
                            tone={direct ? 'ok' : 'idle'}
                            label={
                              direct
                                ? t('adminAssignmentsGrantDirect')
                                : t('adminAssignmentsGrantInherited')
                            }
                          />
                        </Box>
                        <Typography variant="caption" color="text.secondary">
                          {t('adminAssignmentsGrantedAt', {
                            role: role?.name ?? assignment.roleId,
                            scope: scopePathString(assignment.scope),
                          })}
                        </Typography>
                      </Box>
                    );
                  })
                )}
                <Alert severity="neutral">{t('adminAssignmentsInheritNote')}</Alert>
              </Stack>
            </Paper>
          </Box>
        </Box>
      )}

      {dialogOpen && (
        <NewAssignmentDialog
          repository={repository}
          principals={principals}
          roles={roles}
          scopeTree={scopeTree}
          assignments={assignments}
          onClose={() => setDialogOpen(false)}
        />
      )}
    </Stack>
  );
}
