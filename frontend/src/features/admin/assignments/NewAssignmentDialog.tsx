/**
 * Create an assignment — the triple (principal, role, scope) (task 092,
 * `D10` 🔒, `SM-D23`, Groups management §7).
 *
 * **Principal type first.** A user and a group are both principals, but
 * they are not interchangeable when choosing one: a list mixing forty
 * users and four groups makes the group you want unfindable, and it hides
 * the one rule that only applies to groups. So the type is picked, then
 * the matching picker is shown.
 *
 * Four rules the dialog enforces before the round trip (all of them also
 * enforced server-side — a client-only rule is a race, not a rule):
 *
 * 1. no duplicate for the same principal + role + EXACT scope;
 * 2. no assignment to a deactivated user or group;
 * 3. a group may only be granted inside its OWN organization;
 * 4. the scope must be complete for the chosen scope type.
 *
 * The scope is composed with the dependent Organization →
 * Sub-organization → Environment selectors (`ScopeSelector`) rather than
 * the browse tree, so an invalid cross-organization combination is
 * unrepresentable.
 */
import { Box, Typography } from '@mui/material';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import type { ReactElement } from 'react';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { PERMISSION_CATALOG } from '@shared/authz/catalog';
import { t } from '@shared/i18n';
import type {
  RbacAssignment,
  RbacPrincipal,
  RbacRole,
  ScopeNode,
} from '@shared/testdoubles/data/rbac';
import { Alert, Button, Dialog, Select, ToggleGroup } from '@shared/ui';
import { Stack } from '@shared/ui/layout';
import { pushOutcome } from '@shared/ui/Toaster';

import type { AdminRepository } from '../adminRepository';

import { scopePathString, scopePathsEqual } from './scopePath';
import { isScopeComplete, ScopeSelector } from './ScopeSelector';
import type { SelectableScopeType } from './ScopeSelector';

const TWO_SIDED_IDS: Set<string> = new Set(
  PERMISSION_CATALOG.filter((permission) => permission.twoSided === true).map(
    (permission) => permission.id,
  ),
);

function roleGrantsTwoSided(role: RbacRole | undefined): boolean {
  if (role === undefined) return false;
  if (role.permissionIds === '*') return true;
  return role.permissionIds.some((id) => TWO_SIDED_IDS.has(id));
}

export type PrincipalKind = 'user' | 'group';

export interface NewAssignmentDialogProps {
  repository: AdminRepository;
  principals: readonly RbacPrincipal[];
  roles: readonly RbacRole[];
  scopeTree: ScopeNode;
  /** Existing assignments — the duplicate rule reads them. */
  assignments: readonly RbacAssignment[];
  onClose: () => void;
}

export function NewAssignmentDialog({
  repository,
  principals,
  roles,
  scopeTree,
  assignments,
  onClose,
}: NewAssignmentDialogProps): ReactElement {
  const queryClient = useQueryClient();
  const [principalKind, setPrincipalKind] = useState<PrincipalKind>('user');
  const [principalId, setPrincipalId] = useState('');
  const [roleId, setRoleId] = useState('');
  const [scopeType, setScopeType] = useState<SelectableScopeType>('organization');
  const [scope, setScope] = useState<readonly string[]>([]);

  const role = roles.find((candidate) => candidate.id === roleId);
  const principal = principals.find((candidate) => candidate.id === principalId);

  const ofKind = principals.filter((candidate) => candidate.kind === principalKind);

  // A group owned by one organization may never be granted inside another
  // (§6). Locking the organization to the group's own is how that is made
  // unreachable rather than merely refused.
  const lockedOrganization = principal?.kind === 'group' ? principal.organization : undefined;

  const duplicate =
    principalId !== '' &&
    roleId !== '' &&
    assignments.some(
      (assignment) =>
        assignment.status === 'active' &&
        assignment.principalId === principalId &&
        assignment.roleId === roleId &&
        scopePathsEqual(assignment.scope, scope),
    );
  const deactivated = principal !== undefined && principal.status !== 'active';
  const crossOrganization =
    lockedOrganization !== undefined && scope.length > 0 && scope[0] !== lockedOrganization;
  const complete = isScopeComplete(scope, scopeType);

  const blocked = duplicate || deactivated || crossOrganization || !complete;

  const create = useMutation({
    mutationFn: () => repository.createAssignment({ principalId, roleId, scope: [...scope] }),
    onSuccess: ({ assignment, correlationId }) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.assignments() });
      void queryClient.invalidateQueries({ queryKey: ['admin', 'effective-permissions'] });
      if (principal?.kind === 'group') {
        void queryClient.invalidateQueries({
          queryKey: queryKeys.admin.groupAssignments(assignment.principalId),
        });
        void queryClient.invalidateQueries({ queryKey: queryKeys.admin.groups() });
      }
      pushOutcome(`assignment:${assignment.id}`, {
        title: t('adminNewAssignmentCreatedToast'),
        detail: t('adminNewAssignmentCreatedDetail', {
          role: role?.name ?? assignment.roleId,
          principal: principal?.name ?? assignment.principalId,
          scope: scopePathString(assignment.scope),
        }),
        correlationId,
        severity: 'ok',
      });
      onClose();
    },
  });

  function changeKind(next: PrincipalKind): void {
    setPrincipalKind(next);
    // The chosen principal cannot survive a type change, and neither can a
    // scope that was locked to the previous group's organization.
    setPrincipalId('');
    setScope([]);
  }

  function changePrincipal(next: string): void {
    setPrincipalId(next);
    const chosen = principals.find((candidate) => candidate.id === next);
    // Re-root the scope on the group's own organization; a user's scope is
    // unconstrained and simply starts over.
    setScope(
      chosen?.kind === 'group' && chosen.organization !== undefined ? [chosen.organization] : [],
    );
  }

  const principalOptions = ofKind.map((candidate) => ({
    value: candidate.id,
    label:
      candidate.status === 'active'
        ? candidate.name
        : `${candidate.name} · ${t('adminUsersStatusDeactivated')}`,
  }));
  const roleOptions = roles.map((candidate) => ({ value: candidate.id, label: candidate.name }));

  return (
    <Dialog
      open
      title={t('adminNewAssignmentTitle')}
      onClose={onClose}
      dirty={principalId !== '' || roleId !== ''}
      actions={
        <>
          <Button variant="text" onClick={onClose}>
            {t('cancel')}
          </Button>
          <Button
            onClick={() => create.mutate()}
            disabled={principalId === '' || roleId === '' || blocked || create.isPending}
          >
            {t('adminNewAssignmentSubmit')}
          </Button>
        </>
      }
    >
      <Stack gap={2}>
        <Box>
          <Typography variant="overline" component="p" sx={{ mb: 0.5 }}>
            {t('adminAssignmentsPrincipalTypeLabel')}
          </Typography>
          <ToggleGroup<PrincipalKind>
            value={principalKind}
            onChange={changeKind}
            aria-label={t('adminAssignmentsPrincipalTypeLabel')}
            options={[
              { value: 'user', label: t('adminAssignmentsPrincipalTypeUser') },
              { value: 'group', label: t('adminAssignmentsPrincipalTypeGroup') },
            ]}
          />
        </Box>

        {principalOptions.length === 0 ? (
          <Alert severity="neutral">{t('adminAssignmentsNoPrincipalsOfType')}</Alert>
        ) : (
          <Select
            label={t('adminNewAssignmentPrincipalLabel')}
            helperText={t('adminNewAssignmentPrincipalHint')}
            value={principalId}
            onChange={changePrincipal}
            options={principalOptions}
          />
        )}

        <Select
          label={t('adminNewAssignmentRoleLabel')}
          value={roleId}
          onChange={setRoleId}
          options={roleOptions}
        />

        <ScopeSelector
          scopeTree={scopeTree}
          value={scope}
          onChange={setScope}
          scopeType={scopeType}
          onScopeTypeChange={setScopeType}
          {...(lockedOrganization !== undefined ? { lockedOrganization } : {})}
        />

        <Typography variant="caption" color="text.secondary">
          {t('adminNewAssignmentSelected', { scope: scopePathString(scope) })}
        </Typography>

        {!complete && <Alert severity="neutral">{t('adminScopeIncomplete')}</Alert>}
        {deactivated && <Alert severity="warn">{t('adminAssignmentsPrincipalDeactivated')}</Alert>}
        {crossOrganization && lockedOrganization !== undefined && (
          <Alert severity="err">
            {t('adminAssignmentsCrossOrganization', { organization: lockedOrganization })}
          </Alert>
        )}
        {duplicate && <Alert severity="warn">{t('adminAssignmentsDuplicate')}</Alert>}

        {roleGrantsTwoSided(role) && (
          <Alert severity="info" title={t('adminRoleTwoSided')}>
            {t('adminNewAssignmentTwoSided')}
          </Alert>
        )}
        {create.isError && (
          <Alert severity="err">
            {create.error instanceof ApiError ? create.error.message : t('adminNewAssignmentError')}
          </Alert>
        )}
      </Stack>
    </Dialog>
  );
}
