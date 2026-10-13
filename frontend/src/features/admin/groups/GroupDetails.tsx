/**
 * A group's detail view (Groups management §5) — Overview, Members and
 * Role assignments in one dialog, because the three answer one question
 * ("what does this group actually confer, and to whom?") and splitting
 * them across screens is how an operator ends up removing a member and not
 * noticing the assignment that made it matter.
 *
 * Two removals, both confirmed and both reversible-by-record:
 * - removing a member takes away only what they held THROUGH this group;
 *   their own direct assignments are untouched, and the copy says so;
 * - removing an assignment REVOKES it, leaving the row readable for audit.
 *
 * A member who was in the group and has since been deactivated is shown,
 * marked, and never silently dropped — their access is already nil, and
 * reactivating the account restores it.
 */
import { Box, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useMutation, useQueries, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import type { ReactElement } from 'react';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { formatDateTime } from '@shared/format';
import { t } from '@shared/i18n';
import type { UserGroup } from '@shared/testdoubles/data/groups';
import type { LocalUser, RbacAssignment, RbacRole, ScopeNode } from '@shared/testdoubles/data/rbac';
import { Alert, Button, Chip, ConfirmDialog, Dialog, Skeleton } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { Stack } from '@shared/ui/layout';
import { pushOutcome } from '@shared/ui/Toaster';

import type { AdminRepository } from '../adminRepository';
import { scopeLevelLabel, scopeLevelOf, scopePathString } from '../assignments/scopePath';

import { AddMembersDialog } from './AddMembersDialog';
import { AssignRoleDialog } from './AssignRoleDialog';
import { GROUP_PERMISSIONS } from './groupPermissions';
import { useGroupPermissions } from './useGroupPermissions';

export interface GroupDetailsProps {
  repository: AdminRepository;
  group: UserGroup;
  users: readonly LocalUser[];
  roles: readonly RbacRole[];
  scopeTree: ScopeNode;
  onClose: () => void;
  onEdit: () => void;
  /** Which panel to open on — the row menu's "Manage members" lands here. */
  initialAction?: 'members' | 'assignments';
}

function Field({ label, value }: { label: string; value: string }): ReactElement {
  return (
    <Box sx={{ minWidth: 180 }}>
      <Typography variant="caption" component="dt" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="body2" component="dd" sx={{ m: 0, overflowWrap: 'anywhere' }}>
        {value}
      </Typography>
    </Box>
  );
}

function describeError(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

export function GroupDetails({
  repository,
  group,
  users,
  roles,
  scopeTree,
  onClose,
  onEdit,
  initialAction,
}: GroupDetailsProps): ReactElement {
  const queryClient = useQueryClient();
  const can = useGroupPermissions();
  const [addOpen, setAddOpen] = useState(initialAction === 'members');
  const [assignOpen, setAssignOpen] = useState(initialAction === 'assignments');
  const [removingUserId, setRemovingUserId] = useState<string | null>(null);
  const [revokingAssignmentId, setRevokingAssignmentId] = useState<string | null>(null);

  const [membersQuery, assignmentsQuery] = useQueries({
    queries: [
      {
        queryKey: queryKeys.admin.groupMembers(group.id),
        queryFn: ({ signal }: { signal: AbortSignal }) => repository.groupMembers(group.id, signal),
      },
      {
        queryKey: queryKeys.admin.groupAssignments(group.id),
        queryFn: ({ signal }: { signal: AbortSignal }) =>
          repository.groupAssignments(group.id, signal),
      },
    ],
  });

  function invalidateAccess(): void {
    void queryClient.invalidateQueries({ queryKey: queryKeys.admin.groups() });
    void queryClient.invalidateQueries({ queryKey: ['admin', 'effective-permissions'] });
  }

  const removeMember = useMutation({
    mutationFn: (userId: string) => repository.removeGroupMember(group.id, userId),
    onSuccess: (_result, userId) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.groupMembers(group.id) });
      invalidateAccess();
      pushOutcome(`group-member:${group.id}:${userId}`, {
        title: t('adminGroupMemberRemovedToast', {
          user: users.find((user) => user.id === userId)?.name ?? userId,
          group: group.name,
        }),
        correlationId: `group-member-${group.id}-${userId}`,
        severity: 'ok',
      });
      setRemovingUserId(null);
    },
  });

  const revokeAssignment = useMutation({
    mutationFn: (assignmentId: string) => repository.revokeAssignment(assignmentId),
    onSuccess: (_result, assignmentId) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.groupAssignments(group.id) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.assignments() });
      invalidateAccess();
      pushOutcome(`assignment:${assignmentId}`, {
        title: t('adminGroupAssignmentRemovedToast', { group: group.name }),
        correlationId: `assignment-${assignmentId}`,
        severity: 'ok',
      });
      setRevokingAssignmentId(null);
    },
  });

  const members = membersQuery.data ?? [];
  const assignments = assignmentsQuery.data ?? [];
  const userById = new Map(users.map((user) => [user.id, user]));
  const roleById = new Map(roles.map((role) => [role.id, role]));
  const memberIds = members.map((member) => member.userId);

  const removingUser = removingUserId === null ? undefined : userById.get(removingUserId);
  const revoking =
    revokingAssignmentId === null
      ? undefined
      : assignments.find((assignment) => assignment.id === revokingAssignmentId);

  const editable = group.source === 'local';

  return (
    <>
      <Dialog
        open
        title={t('adminGroupDetailsTitle', { name: group.name })}
        onClose={onClose}
        maxWidth="md"
        actions={
          <>
            {can(GROUP_PERMISSIONS.edit) && editable && (
              <Button variant="text" onClick={onEdit}>
                {t('adminGroupsActionEdit')}
              </Button>
            )}
            <Button onClick={onClose}>{t('close')}</Button>
          </>
        }
      >
        <Stack gap={3}>
          {group.source === 'idp' && <Alert severity="info">{t('adminGroupIdpReadOnly')}</Alert>}

          <Box component="section" aria-labelledby={`overview-${group.id}`}>
            <Typography id={`overview-${group.id}`} variant="overline" component="h3">
              {t('adminGroupDetailsOverview')}
            </Typography>
            <Box component="dl" sx={{ display: 'flex', flexWrap: 'wrap', gap: 2, m: 0, mt: 1 }}>
              <Field label={t('adminGroupFieldName')} value={group.name} />
              <Field
                label={t('adminGroupFieldDescription')}
                value={group.description ?? t('adminGroupsNoDescription')}
              />
              <Field label={t('adminGroupFieldOrganization')} value={group.organization} />
              <Field
                label={t('adminGroupFieldStatus')}
                value={
                  group.status === 'active'
                    ? t('adminGroupsStatusActive')
                    : t('adminGroupsStatusDeactivated')
                }
              />
              <Field
                label={t('adminGroupFieldSource')}
                value={
                  group.source === 'idp' ? t('adminGroupsSourceIdp') : t('adminGroupsSourceLocal')
                }
              />
              <Field label={t('adminGroupFieldCreatedBy')} value={group.createdBy} />
              <Field
                label={t('adminGroupFieldCreatedAt')}
                value={formatDateTime(group.createdAt, { timeStyle: 'short' })}
              />
              <Field
                label={t('adminGroupFieldUpdatedBy')}
                value={group.updatedBy ?? t('adminGroupFieldUnknown')}
              />
              <Field
                label={t('adminGroupFieldUpdatedAt')}
                value={formatDateTime(group.updatedAt, { timeStyle: 'short' })}
              />
            </Box>
          </Box>

          <Box component="section" aria-labelledby={`members-${group.id}`}>
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 1,
                flexWrap: 'wrap',
              }}
            >
              <Typography id={`members-${group.id}`} variant="overline" component="h3">
                {t('adminGroupDetailsMembers')}
              </Typography>
              {can(GROUP_PERMISSIONS.memberManage) && editable && (
                <Button size="sm" variant="outlined" onClick={() => setAddOpen(true)}>
                  {t('adminGroupMembersAdd')}
                </Button>
              )}
            </Box>
            {membersQuery.isPending ? (
              <Skeleton width="100%" height={120} />
            ) : membersQuery.isError ? (
              <ErrorPanel
                title={t('adminGroupDetailsErrorTitle')}
                detail={describeError(membersQuery.error, t('errorGeneric'))}
                onRetry={() => void membersQuery.refetch()}
              />
            ) : members.length === 0 ? (
              <Alert severity="neutral">{t('adminGroupMembersEmpty')}</Alert>
            ) : (
              <Box sx={{ overflowX: 'auto' }}>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>{t('adminGroupMembersColUser')}</TableCell>
                      <TableCell>{t('adminGroupMembersColEmail')}</TableCell>
                      <TableCell>{t('adminGroupMembersColStatus')}</TableCell>
                      <TableCell>{t('adminGroupMembersColAddedAt')}</TableCell>
                      <TableCell>{t('adminGroupMembersColAddedBy')}</TableCell>
                      <TableCell align="right">{t('adminGroupMembersColActions')}</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {members.map((member) => {
                      const user = userById.get(member.userId);
                      const deactivated = user?.status === 'deactivated';
                      return (
                        <TableRow key={member.userId}>
                          <TableCell>{user?.name ?? member.userId}</TableCell>
                          <TableCell>{user?.email ?? t('adminGroupFieldUnknown')}</TableCell>
                          <TableCell>
                            <Chip
                              tone={deactivated ? 'warn' : 'ok'}
                              label={
                                deactivated
                                  ? t('adminGroupMemberDeactivated')
                                  : t('adminGroupMemberActive')
                              }
                            />
                          </TableCell>
                          <TableCell>
                            {formatDateTime(member.addedAt, { timeStyle: 'short' })}
                          </TableCell>
                          <TableCell>{member.addedBy}</TableCell>
                          <TableCell align="right">
                            {can(GROUP_PERMISSIONS.memberManage) && editable && (
                              <Button
                                variant="text"
                                size="sm"
                                disabled={removeMember.isPending}
                                onClick={() => setRemovingUserId(member.userId)}
                              >
                                {t('adminGroupMembersRemove')}
                              </Button>
                            )}
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </Box>
            )}
          </Box>

          <Box component="section" aria-labelledby={`assignments-${group.id}`}>
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 1,
                flexWrap: 'wrap',
              }}
            >
              <Typography id={`assignments-${group.id}`} variant="overline" component="h3">
                {t('adminGroupDetailsAssignments')}
              </Typography>
              {can(GROUP_PERMISSIONS.roleAssign) && (
                <Button size="sm" variant="outlined" onClick={() => setAssignOpen(true)}>
                  {t('adminGroupAssignmentsAssign')}
                </Button>
              )}
            </Box>
            {assignmentsQuery.isPending ? (
              <Skeleton width="100%" height={120} />
            ) : assignmentsQuery.isError ? (
              <ErrorPanel
                title={t('adminGroupDetailsErrorTitle')}
                detail={describeError(assignmentsQuery.error, t('errorGeneric'))}
                onRetry={() => void assignmentsQuery.refetch()}
              />
            ) : assignments.length === 0 ? (
              <Alert severity="neutral">{t('adminGroupAssignmentsEmpty')}</Alert>
            ) : (
              <Box sx={{ overflowX: 'auto' }}>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>{t('adminGroupAssignmentsColRole')}</TableCell>
                      <TableCell>{t('adminGroupAssignmentsColScopeType')}</TableCell>
                      <TableCell>{t('adminGroupAssignmentsColOrganization')}</TableCell>
                      <TableCell>{t('adminGroupAssignmentsColSubOrganization')}</TableCell>
                      <TableCell>{t('adminGroupAssignmentsColEnvironment')}</TableCell>
                      <TableCell>{t('adminGroupAssignmentsColAssignedBy')}</TableCell>
                      <TableCell>{t('adminGroupAssignmentsColAssignedAt')}</TableCell>
                      <TableCell>{t('adminGroupAssignmentsColStatus')}</TableCell>
                      <TableCell align="right">{t('adminGroupAssignmentsColActions')}</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {assignments.map((assignment: RbacAssignment) => {
                      const active = assignment.status === 'active';
                      return (
                        <TableRow key={assignment.id}>
                          <TableCell>
                            {roleById.get(assignment.roleId)?.name ?? assignment.roleId}
                          </TableCell>
                          <TableCell>{scopeLevelLabel(scopeLevelOf(assignment.scope))}</TableCell>
                          <TableCell>
                            {assignment.scope[0] ?? t('adminGroupFieldUnknown')}
                          </TableCell>
                          <TableCell>
                            {assignment.scope[1] ?? t('adminGroupFieldUnknown')}
                          </TableCell>
                          <TableCell>
                            {assignment.scope[2] ?? t('adminGroupFieldUnknown')}
                          </TableCell>
                          <TableCell>{assignment.assignedBy}</TableCell>
                          <TableCell>
                            {formatDateTime(assignment.assignedAt, { timeStyle: 'short' })}
                          </TableCell>
                          <TableCell>
                            <Chip
                              tone={active ? 'ok' : 'idle'}
                              label={
                                active
                                  ? t('adminGroupAssignmentsStatusActive')
                                  : t('adminGroupAssignmentsStatusRevoked')
                              }
                            />
                          </TableCell>
                          <TableCell align="right">
                            {active && can(GROUP_PERMISSIONS.roleAssign) && (
                              <Button
                                variant="text"
                                size="sm"
                                disabled={revokeAssignment.isPending}
                                onClick={() => setRevokingAssignmentId(assignment.id)}
                              >
                                {t('adminGroupAssignmentRemove')}
                              </Button>
                            )}
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </Box>
            )}
            {removeMember.isError && (
              <Alert severity="err">
                {describeError(removeMember.error, t('adminGroupMemberRemoveError'))}
              </Alert>
            )}
            {revokeAssignment.isError && (
              <Alert severity="err">
                {describeError(revokeAssignment.error, t('adminGroupAssignmentRemoveError'))}
              </Alert>
            )}
          </Box>
        </Stack>
      </Dialog>

      {addOpen && (
        <AddMembersDialog
          repository={repository}
          group={group}
          users={users}
          currentMemberIds={memberIds}
          onClose={() => setAddOpen(false)}
        />
      )}

      {assignOpen && (
        <AssignRoleDialog
          repository={repository}
          group={group}
          roles={roles}
          scopeTree={scopeTree}
          existingAssignments={assignments}
          onClose={() => setAssignOpen(false)}
        />
      )}

      {removingUser !== undefined && (
        <ConfirmDialog
          open
          consequence="high"
          title={t('adminGroupMemberRemoveTitle', {
            user: removingUser.name,
            group: group.name,
          })}
          confirmLabel={t('adminGroupMemberRemoveConfirm')}
          onCancel={() => setRemovingUserId(null)}
          onConfirm={() => removeMember.mutate(removingUser.id)}
        >
          {t('adminGroupMemberRemoveBody')}
        </ConfirmDialog>
      )}

      {revoking !== undefined && (
        <ConfirmDialog
          open
          consequence="high"
          title={t('adminGroupAssignmentRemoveTitle', {
            role: roleById.get(revoking.roleId)?.name ?? revoking.roleId,
            scope: scopePathString(revoking.scope),
            group: group.name,
          })}
          confirmLabel={t('adminGroupAssignmentRemoveConfirm')}
          onCancel={() => setRevokingAssignmentId(null)}
          onConfirm={() => revokeAssignment.mutate(revoking.id)}
        >
          {t('adminGroupAssignmentRemoveBody')}
        </ConfirmDialog>
      )}
    </>
  );
}
