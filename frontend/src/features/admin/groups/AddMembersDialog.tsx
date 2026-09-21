/**
 * Add members to an existing group (Groups management §5).
 *
 * Three rules the picker enforces by CONSTRUCTION rather than by rejection
 * (`selectableMembers`): a current member is not offered, so no duplicate
 * membership row can be produced; a deactivated user is not offered, so
 * one can never be newly added; and only users of the group's own
 * organization are offered, so a membership row can never cross the
 * organization boundary a group's whole safety story rests on.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import type { ReactElement } from 'react';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import type { UserGroup } from '@shared/testdoubles/data/groups';
import type { LocalUser } from '@shared/testdoubles/data/rbac';
import { Alert, Button, Dialog } from '@shared/ui';
import { Stack } from '@shared/ui/layout';
import { pushOutcome } from '@shared/ui/Toaster';

import type { AdminRepository } from '../adminRepository';

import { selectableMembers } from './groupValidation';
import { MemberPicker } from './MemberPicker';

export interface AddMembersDialogProps {
  repository: AdminRepository;
  group: UserGroup;
  users: readonly LocalUser[];
  currentMemberIds: readonly string[];
  onClose: () => void;
}

export function AddMembersDialog({
  repository,
  group,
  users,
  currentMemberIds,
  onClose,
}: AddMembersDialogProps): ReactElement {
  const queryClient = useQueryClient();
  const [selectedIds, setSelectedIds] = useState<readonly string[]>([]);

  const candidates = selectableMembers(users, group.organization, currentMemberIds);

  const add = useMutation({
    mutationFn: () => repository.addGroupMembers(group.id, [...selectedIds]),
    onSuccess: ({ correlationId }) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.groupMembers(group.id) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.groups() });
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.principals() });
      // A membership write changes what its members hold, so every cached
      // effective-permission answer is stale — invalidate the whole
      // namespace rather than guessing which principal/scope pairs moved.
      void queryClient.invalidateQueries({ queryKey: ['admin', 'effective-permissions'] });
      pushOutcome(`group-members:${group.id}`, {
        title: t('adminGroupAddMembersToast', { group: group.name }),
        detail: t('adminGroupAddMembersDetail', { count: selectedIds.length }),
        correlationId,
        severity: 'ok',
      });
      onClose();
    },
  });

  return (
    <Dialog
      open
      title={t('adminGroupAddMembersTitle', { group: group.name })}
      onClose={onClose}
      dirty={selectedIds.length > 0}
      actions={
        <>
          <Button variant="text" onClick={onClose}>
            {t('cancel')}
          </Button>
          <Button onClick={() => add.mutate()} disabled={selectedIds.length === 0 || add.isPending}>
            {add.isPending
              ? t('adminGroupAddMembersSubmitPending')
              : t('adminGroupAddMembersSubmit')}
          </Button>
        </>
      }
    >
      <Stack gap={2}>
        <MemberPicker
          candidates={candidates}
          selectedIds={selectedIds}
          onChange={setSelectedIds}
          emptyMessage={t('adminGroupAddMembersNone')}
        />
        {add.isError && (
          <Alert severity="err">
            {add.error instanceof ApiError ? add.error.message : t('adminGroupAddMembersError')}
          </Alert>
        )}
      </Stack>
    </Dialog>
  );
}
