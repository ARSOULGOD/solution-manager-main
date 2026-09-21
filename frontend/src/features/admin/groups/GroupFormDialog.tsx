/**
 * Create or edit a group (Groups management §3/§4). One component for both,
 * because the two forms differ in exactly two ways and duplicating them is
 * how the validation rules drift apart:
 *
 * - **Create** offers the organization and initial members.
 * - **Edit** shows the organization READ-ONLY. Changing it after creation
 *   would invalidate every membership row (users belong to one
 *   organization) and every role assignment (a group may only be granted
 *   inside its own organization), so this build does not support it —
 *   §4's "otherwise, display Organization as read-only" branch.
 *
 * Changing the organization on the create form clears members who do not
 * belong to the new one and says so, rather than silently submitting a
 * selection the server would reject.
 *
 * An IdP-sourced group is read-only here (§12): its name and membership
 * belong to the provider.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import type { ReactElement } from 'react';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import type { UserGroup } from '@shared/testdoubles/data/groups';
import type { LocalUser } from '@shared/testdoubles/data/rbac';
import { Alert, Button, Dialog, Select, TextField } from '@shared/ui';
import { Stack } from '@shared/ui/layout';
import { pushOutcome } from '@shared/ui/Toaster';

import type { AdminRepository } from '../adminRepository';

import {
  hasErrors,
  partitionMembersByOrganization,
  selectableMembers,
  validateGroupForm,
} from './groupValidation';
import type { GroupFormErrors } from './groupValidation';
import { MemberPicker } from './MemberPicker';

export interface GroupFormDialogProps {
  repository: AdminRepository;
  /** Absent = create. Present = edit that group. */
  group?: UserGroup;
  organizations: readonly string[];
  users: readonly LocalUser[];
  existingGroups: readonly UserGroup[];
  /** Current membership of the edited group — the edit form's starting selection. */
  initialMemberIds?: readonly string[];
  onClose: () => void;
}

function describeError(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

export function GroupFormDialog({
  repository,
  group,
  organizations,
  users,
  existingGroups,
  initialMemberIds = [],
  onClose,
}: GroupFormDialogProps): ReactElement {
  const queryClient = useQueryClient();
  const editing = group !== undefined;
  const readOnly = group?.source === 'idp';

  const [name, setName] = useState(group?.name ?? '');
  const [description, setDescription] = useState(group?.description ?? '');
  const [organization, setOrganization] = useState(group?.organization ?? '');
  const [memberIds, setMemberIds] = useState<readonly string[]>(initialMemberIds);
  const [droppedCount, setDroppedCount] = useState(0);
  // Errors are shown after a submit attempt or a blur — never on an
  // untouched field, which would mark an empty form invalid on open.
  const [showErrors, setShowErrors] = useState(false);

  const values = { name, description, organization, memberIds };
  const errors: GroupFormErrors = validateGroupForm(values, {
    existingGroups,
    ...(group !== undefined ? { editingGroupId: group.id } : {}),
  });
  const visibleErrors: GroupFormErrors = showErrors ? errors : {};

  const dirty =
    name !== (group?.name ?? '') ||
    description !== (group?.description ?? '') ||
    organization !== (group?.organization ?? '') ||
    memberIds.length !== initialMemberIds.length ||
    memberIds.some((id) => !initialMemberIds.includes(id));

  const save = useMutation({
    mutationFn: () =>
      editing
        ? repository.updateGroup(group.id, {
            name: name.trim(),
            description: description.trim(),
            memberIds: [...memberIds],
          })
        : repository.createGroup({
            name: name.trim(),
            description: description.trim(),
            organization,
            memberIds: [...memberIds],
          }),
    onSuccess: ({ group: saved, correlationId }) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.groups() });
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.principals() });
      if (editing) {
        void queryClient.invalidateQueries({
          queryKey: queryKeys.admin.groupMembers(saved.id),
        });
      }
      pushOutcome(`group:${saved.id}`, {
        title: editing
          ? t('adminGroupUpdatedToast', { name: saved.name })
          : t('adminGroupCreatedToast', { name: saved.name }),
        detail: editing
          ? t('adminGroupUpdatedDetail')
          : t('adminGroupCreatedDetail', { organization: saved.organization }),
        correlationId,
        severity: 'ok',
      });
      onClose();
    },
  });

  function changeOrganization(next: string): void {
    setOrganization(next);
    const { kept, dropped } = partitionMembersByOrganization(memberIds, users, next);
    setMemberIds(kept);
    setDroppedCount(dropped.length);
  }

  function submit(): void {
    setShowErrors(true);
    if (hasErrors(errors) || save.isPending) return;
    save.mutate();
  }

  const candidates = selectableMembers(users, organization);
  // On the edit form the current members must stay selectable (so they can
  // be deselected), so the candidate list is the union of the eligible set
  // and whoever is already selected.
  const selectedButNotCandidate = users.filter(
    (user) => memberIds.includes(user.id) && !candidates.some((row) => row.id === user.id),
  );
  const pickerCandidates = [...candidates, ...selectedButNotCandidate];

  return (
    <Dialog
      open
      title={editing ? t('adminGroupEditTitle') : t('adminGroupCreateTitle')}
      onClose={onClose}
      dirty={dirty}
      actions={
        <>
          <Button variant="text" onClick={onClose}>
            {t('cancel')}
          </Button>
          <Button onClick={submit} disabled={readOnly || save.isPending}>
            {save.isPending
              ? editing
                ? t('adminGroupEditSubmitPending')
                : t('adminGroupCreateSubmitPending')
              : editing
                ? t('adminGroupEditSubmit')
                : t('adminGroupCreateSubmit')}
          </Button>
        </>
      }
    >
      <Stack gap={2}>
        {readOnly && <Alert severity="info">{t('adminGroupIdpReadOnly')}</Alert>}

        <TextField
          label={t('adminGroupNameLabel')}
          required
          value={name}
          disabled={readOnly}
          onChange={(event) => setName(event.target.value)}
          onBlur={() => setShowErrors(true)}
          {...(visibleErrors.name !== undefined ? { error: visibleErrors.name } : {})}
        />

        <TextField
          label={t('adminGroupDescriptionLabel')}
          value={description}
          disabled={readOnly}
          onChange={(event) => setDescription(event.target.value)}
          {...(visibleErrors.description !== undefined
            ? { error: visibleErrors.description }
            : { helperText: t('adminGroupDescriptionHint') })}
        />

        {editing ? (
          <TextField
            label={t('adminGroupOrganizationLabel')}
            value={organization}
            readOnly
            onChange={() => undefined}
            helperText={t('adminGroupOrganizationLockedHint')}
          />
        ) : (
          <Select
            label={t('adminGroupOrganizationLabel')}
            required
            value={organization}
            onChange={changeOrganization}
            options={organizations.map((org) => ({ value: org, label: org }))}
            {...(visibleErrors.organization !== undefined
              ? { error: visibleErrors.organization }
              : { helperText: t('adminGroupOrganizationHint') })}
          />
        )}

        {droppedCount > 0 && (
          <Alert severity="warn">
            {t('adminGroupMembersDropped', { count: droppedCount, organization })}
          </Alert>
        )}

        {organization === '' ? (
          <Alert severity="neutral">{t('adminGroupMembersPickOrganizationFirst')}</Alert>
        ) : (
          <MemberPicker
            candidates={pickerCandidates}
            selectedIds={memberIds}
            onChange={setMemberIds}
            disabled={readOnly}
            emptyMessage={t('adminGroupMembersNoneAvailable')}
            searchLabel={t('adminGroupMembersLabel')}
          />
        )}

        {save.isError && (
          <Alert severity="err">
            {describeError(
              save.error,
              editing ? t('adminGroupEditError') : t('adminGroupCreateError'),
            )}
          </Alert>
        )}
      </Stack>
    </Dialog>
  );
}
