/**
 * Assign a role to a group at a scope (Groups management §5/§6).
 *
 * The same triple the Assignments screen creates — (principal, role,
 * scope) — reached through `repository.createAssignment`, not a
 * group-specific write path, so a group assignment IS an assignment and
 * shows up on the Assignments screen without a second code path.
 *
 * The organization is LOCKED to the group's own: a group owned by one
 * organization must never receive access to another (§6). The remaining
 * levels are the dependent `ScopeSelector`, so a cross-sub-organization
 * combination is unrepresentable rather than merely refused.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import type { ReactElement } from 'react';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import type { UserGroup } from '@shared/testdoubles/data/groups';
import type { RbacAssignment, RbacRole, ScopeNode } from '@shared/testdoubles/data/rbac';
import { Alert, Button, Dialog, Select } from '@shared/ui';
import { Stack } from '@shared/ui/layout';
import { pushOutcome } from '@shared/ui/Toaster';

import type { AdminRepository } from '../adminRepository';
import { scopePathString, scopePathsEqual } from '../assignments/scopePath';
import { isScopeComplete, ScopeSelector } from '../assignments/ScopeSelector';
import type { SelectableScopeType } from '../assignments/ScopeSelector';

export interface AssignRoleDialogProps {
  repository: AdminRepository;
  group: UserGroup;
  roles: readonly RbacRole[];
  scopeTree: ScopeNode;
  /** The group's existing assignments — the duplicate check reads them. */
  existingAssignments: readonly RbacAssignment[];
  onClose: () => void;
}

export function AssignRoleDialog({
  repository,
  group,
  roles,
  scopeTree,
  existingAssignments,
  onClose,
}: AssignRoleDialogProps): ReactElement {
  const queryClient = useQueryClient();
  const [roleId, setRoleId] = useState('');
  const [scopeType, setScopeType] = useState<SelectableScopeType>('organization');
  const [scope, setScope] = useState<readonly string[]>([group.organization]);

  const duplicate = existingAssignments.some(
    (assignment) =>
      assignment.status === 'active' &&
      assignment.roleId === roleId &&
      scopePathsEqual(assignment.scope, scope),
  );
  const complete = isScopeComplete(scope, scopeType);
  const deactivated = group.status !== 'active';

  const assign = useMutation({
    mutationFn: () =>
      repository.createAssignment({ principalId: group.id, roleId, scope: [...scope] }),
    onSuccess: ({ assignment, correlationId }) => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.admin.groupAssignments(group.id),
      });
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.assignments() });
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.groups() });
      void queryClient.invalidateQueries({ queryKey: ['admin', 'effective-permissions'] });
      pushOutcome(`assignment:${assignment.id}`, {
        title: t('adminGroupAssignToast', { group: group.name }),
        detail: t('adminGroupAssignDetail', {
          role: roles.find((role) => role.id === assignment.roleId)?.name ?? assignment.roleId,
          scope: scopePathString(assignment.scope),
        }),
        correlationId,
        severity: 'ok',
      });
      onClose();
    },
  });

  return (
    <Dialog
      open
      title={t('adminGroupAssignTitle', { group: group.name })}
      onClose={onClose}
      dirty={roleId !== ''}
      actions={
        <>
          <Button variant="text" onClick={onClose}>
            {t('cancel')}
          </Button>
          <Button
            onClick={() => assign.mutate()}
            disabled={roleId === '' || !complete || duplicate || deactivated || assign.isPending}
          >
            {assign.isPending ? t('adminGroupAssignSubmitPending') : t('adminGroupAssignSubmit')}
          </Button>
        </>
      }
    >
      <Stack gap={2}>
        {deactivated && <Alert severity="warn">{t('adminGroupAssignDeactivatedBlocked')}</Alert>}

        <Select
          label={t('adminGroupAssignmentsColRole')}
          required
          value={roleId}
          onChange={setRoleId}
          options={roles.map((role) => ({ value: role.id, label: role.name }))}
        />

        <ScopeSelector
          scopeTree={scopeTree}
          value={scope}
          onChange={setScope}
          scopeType={scopeType}
          onScopeTypeChange={setScopeType}
          lockedOrganization={group.organization}
          disabled={deactivated}
        />

        {!complete && <Alert severity="neutral">{t('adminScopeIncomplete')}</Alert>}
        {duplicate && <Alert severity="warn">{t('adminAssignmentsDuplicate')}</Alert>}
        {assign.isError && (
          <Alert severity="err">
            {assign.error instanceof ApiError ? assign.error.message : t('adminGroupAssignError')}
          </Alert>
        )}
      </Stack>
    </Dialog>
  );
}
