/**
 * Groups tab (Groups management §2, `D10` 🔒, `SM-D23`, `FE-D46`).
 *
 * A group is a named collection of local users that HOLDS ROLE
 * ASSIGNMENTS, so an administrator grants once to the set instead of once
 * per person. It is owned by exactly one **organization** — the top of the
 * hierarchy: Organization → Sub-organization → Environment.
 *
 * **Deactivate, never delete**, for the same reason the Users screen does
 * it: a group that once conferred access is the record explaining why
 * something happened, and its assignments and audit references must stay
 * attributable. Nothing on this screen hard-deletes anything — a removed
 * assignment is revoked, a removed member is a membership row that ends.
 *
 * Search and the two filters compose; an empty result set from filters is
 * a DIFFERENT state from an empty group list, and says so (`FE-D10`:
 * "empty is never a silent success").
 *
 * Fixture-backed — `AdminRepository` is `CONTRACT: TBD` (§16 Q4/Q5).
 */
import {
  Box,
  SvgIcon,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { useMutation, useQueries, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import type { ReactElement } from 'react';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import { formatDateTime } from '@shared/format';
import { t } from '@shared/i18n';
import type { UserGroup } from '@shared/testdoubles/data/groups';
import {
  Alert,
  Button,
  Chip,
  ConfirmDialog,
  EmptyState,
  FilterBar,
  IconButton,
  Menu,
  PageHeader,
  Select,
  Skeleton,
} from '@shared/ui';
import type { MenuEntry } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { Stack } from '@shared/ui/layout';
import { pushOutcome } from '@shared/ui/Toaster';

import { GroupDetails } from './GroupDetails';
import { GroupFormDialog } from './GroupFormDialog';
import { GROUP_PERMISSIONS } from './groupPermissions';
import type { GroupStatusFilter, GroupsViewProps } from './GroupsView.types';
import { useGroupPermissions } from './useGroupPermissions';

const ALL_ORGANIZATIONS = '__all__';

// The row-overflow glyph. Hand-written rather than pulled from an icon
// package for the same reason `AdminShell`'s own `ExpanderIcon` is —
// `@mui/icons-material` is not a dependency of this project.
function MoreIcon() {
  return (
    <SvgIcon aria-hidden focusable="false" fontSize="small">
      <circle cx="5" cy="12" r="2" />
      <circle cx="12" cy="12" r="2" />
      <circle cx="19" cy="12" r="2" />
    </SvgIcon>
  );
}

function describeError(error: unknown): string {
  return error instanceof ApiError ? error.message : t('errorGeneric');
}

interface OpenPanel {
  groupId: string;
  action?: 'members' | 'assignments';
}

export function GroupsView({ repository }: GroupsViewProps): ReactElement {
  const queryClient = useQueryClient();
  const can = useGroupPermissions();

  const [query, setQuery] = useState('');
  const [organizationFilter, setOrganizationFilter] = useState(ALL_ORGANIZATIONS);
  const [statusFilter, setStatusFilter] = useState<GroupStatusFilter>('all');
  const [createOpen, setCreateOpen] = useState(false);
  const [editingGroupId, setEditingGroupId] = useState<string | null>(null);
  const [openPanel, setOpenPanel] = useState<OpenPanel | null>(null);
  const [statusTarget, setStatusTarget] = useState<UserGroup | null>(null);

  const [groupsQuery, usersQuery, rolesQuery, scopeTreeQuery, editMembersQuery] = useQueries({
    queries: [
      {
        queryKey: queryKeys.admin.groups(),
        queryFn: ({ signal }: { signal: AbortSignal }) => repository.groups(signal),
      },
      {
        queryKey: queryKeys.admin.users(),
        queryFn: ({ signal }: { signal: AbortSignal }) => repository.users(signal),
      },
      {
        queryKey: queryKeys.admin.roles(),
        queryFn: ({ signal }: { signal: AbortSignal }) => repository.roles(signal),
      },
      {
        queryKey: queryKeys.admin.scopeTree(),
        queryFn: ({ signal }: { signal: AbortSignal }) => repository.scopeTree(signal),
      },
      {
        queryKey: queryKeys.admin.groupMembers(editingGroupId ?? ''),
        queryFn: ({ signal }: { signal: AbortSignal }) =>
          repository.groupMembers(editingGroupId as string, signal),
        enabled: editingGroupId !== null,
      },
    ],
  });

  const setActive = useMutation({
    mutationFn: ({ groupId, active }: { groupId: string; active: boolean }) =>
      repository.setGroupActive(groupId, active),
    onSuccess: ({ group, correlationId }) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.groups() });
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.principals() });
      void queryClient.invalidateQueries({ queryKey: ['admin', 'effective-permissions'] });
      pushOutcome(`group:${group.id}`, {
        title:
          group.status === 'active'
            ? t('adminGroupsReactivatedToast', { name: group.name })
            : t('adminGroupsDeactivatedToast', { name: group.name }),
        correlationId,
        severity: 'ok',
      });
      setStatusTarget(null);
    },
  });

  const groups = useMemo(() => groupsQuery.data ?? [], [groupsQuery.data]);
  const scopeTree = scopeTreeQuery.data;

  const organizations = useMemo(
    () =>
      [
        ...new Set([
          ...(scopeTree === undefined ? [] : organizationNamesFromTree(scopeTree)),
          ...groups.map((group) => group.organization),
        ]),
      ].sort((a, b) => a.localeCompare(b)),
    [groups, scopeTree],
  );

  const visible = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase();
    return groups.filter((group) => {
      if (organizationFilter !== ALL_ORGANIZATIONS && group.organization !== organizationFilter) {
        return false;
      }
      if (statusFilter !== 'all' && group.status !== statusFilter) return false;
      if (needle === '') return true;
      return (
        group.name.toLocaleLowerCase().includes(needle) ||
        (group.description ?? '').toLocaleLowerCase().includes(needle)
      );
    });
  }, [groups, query, organizationFilter, statusFilter]);

  const setupQueries = [groupsQuery, usersQuery, rolesQuery, scopeTreeQuery];

  if (setupQueries.some((q) => q.isPending)) {
    return (
      <Stack gap={2}>
        <Skeleton width={260} height={32} />
        <Skeleton width="100%" height={240} />
      </Stack>
    );
  }

  const failed = setupQueries.find((q) => q.isError);
  if (failed !== undefined || !usersQuery.data || !rolesQuery.data || !scopeTree) {
    return (
      <ErrorPanel
        title={t('adminGroupsErrorTitle')}
        detail={describeError(failed?.error)}
        onRetry={() => {
          for (const q of setupQueries) void q.refetch();
        }}
      />
    );
  }

  const users = usersQuery.data;
  const roles = rolesQuery.data;

  // Gated on the ratified `group.view` (`D18` 🔒, `FE-D47`) — see
  // `groupPermissions.ts` for the whole mapping. An unauthorized operator
  // gets no control at all (omit, never puzzle-disable — `FE-D9`).
  if (!can(GROUP_PERMISSIONS.view)) {
    return (
      <Alert severity="warn" title={t('adminAccessNoneTitle')} fullWidth>
        {t('adminAccessNoneBody')}
      </Alert>
    );
  }

  const openedGroup = groups.find((group) => group.id === openPanel?.groupId);
  const editingGroup = groups.find((group) => group.id === editingGroupId);
  function rowActions(group: UserGroup): MenuEntry[] {
    const entries: MenuEntry[] = [
      { label: t('adminGroupsActionView'), onClick: () => setOpenPanel({ groupId: group.id }) },
    ];
    if (can(GROUP_PERMISSIONS.edit) && group.source === 'local') {
      entries.push({
        label: t('adminGroupsActionEdit'),
        onClick: () => setEditingGroupId(group.id),
      });
    }
    if (can(GROUP_PERMISSIONS.memberManage) && group.source === 'local') {
      entries.push({
        label: t('adminGroupsActionMembers'),
        onClick: () => setOpenPanel({ groupId: group.id, action: 'members' }),
      });
    }
    if (can(GROUP_PERMISSIONS.roleAssign)) {
      entries.push({
        label: t('adminGroupsActionAssignments'),
        onClick: () => setOpenPanel({ groupId: group.id, action: 'assignments' }),
      });
    }
    if (can(GROUP_PERMISSIONS.deactivate)) {
      entries.push({
        label:
          group.status === 'active'
            ? t('adminGroupsActionDeactivate')
            : t('adminGroupsActionReactivate'),
        danger: group.status === 'active',
        onClick: () => setStatusTarget(group),
      });
    }
    return entries;
  }

  return (
    <Stack gap={2}>
      <PageHeader
        title={t('adminGroupsHeading')}
        sub={t('adminGroupsSubheading')}
        actions={
          can(GROUP_PERMISSIONS.create) ? (
            <Button size="sm" onClick={() => setCreateOpen(true)}>
              {t('adminGroupsAdd')}
            </Button>
          ) : undefined
        }
      />

      <Alert severity="warn" title={t('adminGroupsIdpBannerTitle')} fullWidth>
        {t('adminGroupsIdpBannerBody')}
      </Alert>

      <FilterBar
        resultCount={visible.length}
        search={{ label: t('adminGroupsSearchLabel'), value: query, onChange: setQuery }}
      >
        <Box sx={{ minWidth: 200 }}>
          <Select
            label={t('adminGroupsFilterOrganization')}
            value={organizationFilter}
            onChange={setOrganizationFilter}
            options={[
              { value: ALL_ORGANIZATIONS, label: t('adminGroupsFilterOrganizationAll') },
              ...organizations.map((org) => ({ value: org, label: org })),
            ]}
          />
        </Box>
        <Box sx={{ minWidth: 180 }}>
          <Select
            label={t('adminGroupsFilterStatus')}
            value={statusFilter}
            onChange={(next) => setStatusFilter(next as GroupStatusFilter)}
            options={[
              { value: 'all', label: t('adminGroupsFilterStatusAll') },
              { value: 'active', label: t('adminGroupsFilterStatusActive') },
              { value: 'deactivated', label: t('adminGroupsFilterStatusDeactivated') },
            ]}
          />
        </Box>
      </FilterBar>

      {groups.length === 0 ? (
        <EmptyState
          title={t('adminGroupsEmptyTitle')}
          body={t('adminGroupsEmptyBody')}
          action={
            can(GROUP_PERMISSIONS.create) ? (
              <Button onClick={() => setCreateOpen(true)}>{t('adminGroupsAdd')}</Button>
            ) : undefined
          }
        />
      ) : visible.length === 0 ? (
        <EmptyState
          title={t('adminGroupsNoResultsTitle')}
          body={t('adminGroupsNoResultsBody')}
          action={
            <Button
              variant="outlined"
              onClick={() => {
                setQuery('');
                setOrganizationFilter(ALL_ORGANIZATIONS);
                setStatusFilter('all');
              }}
            >
              {t('adminGroupsClearFilters')}
            </Button>
          }
        />
      ) : (
        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>{t('adminGroupsColName')}</TableCell>
                <TableCell>{t('adminGroupsColDescription')}</TableCell>
                <TableCell>{t('adminGroupsColOrganization')}</TableCell>
                <TableCell>{t('adminGroupsColMembers')}</TableCell>
                <TableCell>{t('adminGroupsColAssignments')}</TableCell>
                <TableCell>{t('adminGroupsColStatus')}</TableCell>
                <TableCell>{t('adminGroupsColCreatedBy')}</TableCell>
                <TableCell>{t('adminGroupsColUpdated')}</TableCell>
                <TableCell align="right">{t('adminGroupsColActions')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {visible.map((group) => {
                const active = group.status === 'active';
                return (
                  <TableRow key={group.id}>
                    <TableCell>
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
                        <Typography component="span" variant="body2" sx={{ fontWeight: 600 }}>
                          {group.name}
                        </Typography>
                        {group.source === 'idp' && (
                          <Chip tone="info" label={t('adminGroupsSourceIdp')} />
                        )}
                      </Box>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" color="text.secondary">
                        {group.description ?? t('adminGroupsNoDescription')}
                      </Typography>
                    </TableCell>
                    <TableCell>{group.organization}</TableCell>
                    <TableCell>
                      {t('adminGroupsMemberCount', { count: group.memberCount })}
                    </TableCell>
                    <TableCell>
                      {t('adminGroupsAssignmentCount', { count: group.assignmentCount })}
                    </TableCell>
                    <TableCell>
                      {/* Status carries a word, never colour alone (§14). */}
                      <Chip
                        tone={active ? 'ok' : 'idle'}
                        label={
                          active ? t('adminGroupsStatusActive') : t('adminGroupsStatusDeactivated')
                        }
                      />
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" color="text.secondary">
                        {group.createdBy}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" color="text.secondary">
                        {formatDateTime(group.updatedAt, { timeStyle: 'short' })}
                      </Typography>
                    </TableCell>
                    <TableCell align="right">
                      {/* An icon-only trigger needs a NAME, and it must
                          identify the row — nine identical "Actions"
                          buttons are unusable from a screen reader. */}
                      <Menu
                        trigger={
                          <IconButton
                            size="sm"
                            aria-label={t('adminGroupsRowActions', { name: group.name })}
                          >
                            <MoreIcon />
                          </IconButton>
                        }
                        entries={rowActions(group)}
                      />
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Box>
      )}

      <Typography variant="caption" color="text.secondary">
        {t('adminGroupsNoDeleteNote')}
      </Typography>

      {createOpen && (
        <GroupFormDialog
          repository={repository}
          organizations={organizations}
          users={users}
          existingGroups={groups}
          onClose={() => setCreateOpen(false)}
        />
      )}

      {editingGroup !== undefined && (
        <GroupFormDialog
          repository={repository}
          group={editingGroup}
          organizations={organizations}
          users={users}
          existingGroups={groups}
          initialMemberIds={(editMembersQuery.data ?? []).map((member) => member.userId)}
          onClose={() => setEditingGroupId(null)}
        />
      )}

      {openedGroup !== undefined && (
        <GroupDetails
          repository={repository}
          group={openedGroup}
          users={users}
          roles={roles}
          scopeTree={scopeTree}
          {...(openPanel?.action !== undefined ? { initialAction: openPanel.action } : {})}
          onClose={() => setOpenPanel(null)}
          onEdit={() => {
            setOpenPanel(null);
            setEditingGroupId(openedGroup.id);
          }}
        />
      )}

      {statusTarget !== null && (
        <ConfirmDialog
          open
          consequence="high"
          title={
            statusTarget.status === 'active'
              ? t('adminGroupsDeactivateTitle', { name: statusTarget.name })
              : t('adminGroupsReactivateTitle', { name: statusTarget.name })
          }
          confirmLabel={
            statusTarget.status === 'active'
              ? t('adminGroupsDeactivateConfirm')
              : t('adminGroupsReactivateConfirm')
          }
          onCancel={() => setStatusTarget(null)}
          onConfirm={() =>
            setActive.mutate({
              groupId: statusTarget.id,
              active: statusTarget.status !== 'active',
            })
          }
        >
          {statusTarget.status === 'active'
            ? t('adminGroupsDeactivateBody')
            : t('adminGroupsReactivateBody')}
        </ConfirmDialog>
      )}

      {setActive.isError && <Alert severity="err">{t('adminGroupsStatusError')}</Alert>}
    </Stack>
  );
}

/**
 * Organizations offered on the create form and filter. The scope tree is
 * authoritative (`D20`: its children ARE the organizations); existing
 * group ownership is unioned in so stale server data remains inspectable.
 */
function organizationNamesFromTree(scopeTree: {
  children?: readonly { name: string }[];
}): string[] {
  return (scopeTree.children ?? []).map((child) => child.name);
}
