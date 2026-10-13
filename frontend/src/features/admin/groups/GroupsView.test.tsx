import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { UserGroup } from '@shared/testdoubles/data/groups';

import { renderWithProviders, screen, waitFor, within } from '../../../test/renderWithProviders';
import type { AdminRepository } from '../adminRepository';
import { stubAdminRepository } from '../adminRepository.testdouble';

import {
  ALL_GROUP_PERMISSIONS,
  GROUPS,
  GROUP_ASSIGNMENTS,
  MEMBERS,
  PAYMENTS_OPS,
  ROLES,
  SCOPE_TREE,
  USERS,
} from './groupsTestData';
import { GroupsView } from './GroupsView';

function stubRepository(overrides: Partial<AdminRepository> = {}): AdminRepository {
  return stubAdminRepository({
    groups: () => Promise.resolve([...GROUPS]),
    users: () => Promise.resolve([...USERS]),
    roles: () => Promise.resolve([...ROLES]),
    scopeTree: () => Promise.resolve(SCOPE_TREE),
    groupMembers: () => Promise.resolve([...MEMBERS]),
    groupAssignments: () => Promise.resolve([...GROUP_ASSIGNMENTS]),
    ...overrides,
  });
}

function render(
  overrides: Partial<AdminRepository> = {},
  permissions: readonly string[] = ALL_GROUP_PERMISSIONS,
) {
  return renderWithProviders(<GroupsView repository={stubRepository(overrides)} />, {
    permissions: [...permissions],
  });
}

async function openRowMenu(user: ReturnType<typeof userEvent.setup>, name: string): Promise<void> {
  await user.click(await screen.findByRole('button', { name: `Actions for ${name}` }));
}

describe('GroupsView — the list', () => {
  it('shows the header, the primary action and every column', async () => {
    render();

    expect(await screen.findByRole('heading', { name: 'Groups' })).not.toBeNull();
    expect(
      screen.getByText('Manage user groups and assign role-based access collectively.'),
    ).not.toBeNull();
    expect(screen.getAllByRole('button', { name: 'Add group' }).length).toBeGreaterThan(0);

    for (const column of [
      'Group name',
      'Description',
      'Organization',
      'Members',
      'Role assignments',
      'Status',
      'Created by',
      'Last updated',
    ]) {
      expect(screen.getByRole('columnheader', { name: column })).not.toBeNull();
    }
  });

  it('renders each group with its organization, counts and status', async () => {
    render();

    const row = within(
      (await screen.findByText('Payments Operations')).closest('tr') as HTMLElement,
    );
    expect(row.getByText('Acme Financial')).not.toBeNull();
    expect(row.getByText('2 members')).not.toBeNull();
    expect(row.getByText('1 assignment')).not.toBeNull();
    // Status is a WORD, never colour alone.
    expect(row.getByText('Active')).not.toBeNull();

    const deactivated = within(screen.getByText('Trading Auditors').closest('tr') as HTMLElement);
    expect(deactivated.getByText('Deactivated')).not.toBeNull();
  });

  it('never offers a delete — deactivation is the only removal', async () => {
    const user = userEvent.setup();
    render();
    await openRowMenu(user, 'Payments Operations');

    expect(screen.queryByRole('menuitem', { name: /delete/i })).toBeNull();
    expect(screen.getByRole('menuitem', { name: 'Deactivate' })).not.toBeNull();
  });

  it('carries the IdP warning banner', async () => {
    render();
    expect(
      await screen.findByText(/Local groups are a temporary identity-management capability/),
    ).not.toBeNull();
    expect(
      screen.getByText(/externally managed groups and memberships will become read-only/),
    ).not.toBeNull();
  });

  it('shows a loading skeleton, then the list', async () => {
    render();
    expect(await screen.findByRole('heading', { name: 'Groups' })).not.toBeNull();
  });

  it('shows a retryable error panel when groups fail to load', async () => {
    const groups = vi
      .fn<AdminRepository['groups']>()
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValue([...GROUPS]);
    render({ groups });

    expect(await screen.findByText('Groups could not be loaded')).not.toBeNull();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Payments Operations')).not.toBeNull();
  });

  it('shows the EMPTY state when there are no groups at all', async () => {
    render({ groups: () => Promise.resolve([]) });
    expect(await screen.findByText('No groups yet')).not.toBeNull();
    expect(screen.queryByText('No groups match these filters')).toBeNull();
  });
});

describe('GroupsView — search and filters', () => {
  it('searches by name and by description', async () => {
    const user = userEvent.setup();
    render();
    await screen.findByText('Payments Operations');

    await user.type(screen.getByLabelText('Search groups'), 'read-only access');
    await waitFor(() => {
      expect(screen.queryByText('Payments Operations')).toBeNull();
    });
    expect(screen.getByText('Trading Auditors')).not.toBeNull();
  });

  it('filters by organization', async () => {
    const user = userEvent.setup();
    const globexGroup = { ...GROUPS[1]!, organization: 'Globex' };
    render({ groups: () => Promise.resolve([GROUPS[0]!, globexGroup, ...GROUPS.slice(2)]) });
    await screen.findByText('Payments Operations');

    await user.click(screen.getByLabelText('Organization'));
    await user.click(await screen.findByRole('option', { name: 'Globex' }));

    await waitFor(() => {
      expect(screen.queryByText('Payments Operations')).toBeNull();
    });
    expect(screen.getByText('Trading Auditors')).not.toBeNull();
  });

  it('filters by status', async () => {
    const user = userEvent.setup();
    render();
    await screen.findByText('Payments Operations');

    await user.click(screen.getByLabelText('Status'));
    await user.click(await screen.findByRole('option', { name: 'Deactivated' }));

    await waitFor(() => {
      expect(screen.queryByText('Payments Operations')).toBeNull();
    });
    expect(screen.getByText('Trading Auditors')).not.toBeNull();
  });

  it('distinguishes "no groups" from "no groups match" and can clear the filters', async () => {
    const user = userEvent.setup();
    render();
    await screen.findByText('Payments Operations');

    await user.type(screen.getByLabelText('Search groups'), 'zzzz-nothing');
    expect(await screen.findByText('No groups match these filters')).not.toBeNull();
    expect(screen.queryByText('No groups yet')).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(await screen.findByText('Payments Operations')).not.toBeNull();
  });
});

describe('GroupsView — lifecycle', () => {
  it('deactivates a group behind a confirmation naming it', async () => {
    const user = userEvent.setup();
    const setGroupActive = vi.fn<AdminRepository['setGroupActive']>(() =>
      Promise.resolve({
        group: { ...PAYMENTS_OPS, status: 'deactivated' as const },
        correlationId: 'corr-1',
      }),
    );
    render({ setGroupActive });

    await openRowMenu(user, 'Payments Operations');
    await user.click(screen.getByRole('menuitem', { name: 'Deactivate' }));

    const dialog = await screen.findByRole('dialog', {
      name: 'Deactivate Payments Operations?',
    });
    expect(within(dialog).getByText(/stay on record and can be reactivated/)).not.toBeNull();
    await user.click(within(dialog).getByRole('button', { name: 'Deactivate group' }));

    await waitFor(() => expect(setGroupActive).toHaveBeenCalledWith(PAYMENTS_OPS.id, false));
  });

  it('reactivates a deactivated group', async () => {
    const user = userEvent.setup();
    const setGroupActive = vi.fn<AdminRepository['setGroupActive']>((groupId) =>
      Promise.resolve({
        group: {
          ...(GROUPS.find((g) => g.id === groupId) as UserGroup),
          status: 'active' as const,
        },
        correlationId: 'corr-2',
      }),
    );
    render({ setGroupActive });

    await openRowMenu(user, 'Trading Auditors');
    await user.click(screen.getByRole('menuitem', { name: 'Reactivate' }));
    const dialog = await screen.findByRole('dialog', { name: 'Reactivate Trading Auditors?' });
    await user.click(within(dialog).getByRole('button', { name: 'Reactivate group' }));

    await waitFor(() => expect(setGroupActive).toHaveBeenCalledWith('grp-trading-readonly', true));
  });
});

describe('GroupsView — authorization', () => {
  it('omits the whole screen for an operator who cannot view local identity', async () => {
    render({}, []);
    expect(await screen.findByText('No permissions here')).not.toBeNull();
    expect(screen.queryByRole('heading', { name: 'Groups' })).toBeNull();
  });

  it('omits Add group when the operator cannot create, rather than disabling it', async () => {
    render({}, ['group.view']);
    await screen.findByRole('heading', { name: 'Groups' });
    expect(screen.queryByRole('button', { name: 'Add group' })).toBeNull();
  });

  it('omits the edit, membership and assignment actions an operator is not authorized for', async () => {
    const user = userEvent.setup();
    render({}, ['group.view']);
    await openRowMenu(user, 'Payments Operations');

    expect(screen.getByRole('menuitem', { name: 'View' })).not.toBeNull();
    expect(screen.queryByRole('menuitem', { name: 'Edit' })).toBeNull();
    expect(screen.queryByRole('menuitem', { name: 'Manage members' })).toBeNull();
    expect(screen.queryByRole('menuitem', { name: 'Manage role assignments' })).toBeNull();
    expect(screen.queryByRole('menuitem', { name: 'Deactivate' })).toBeNull();
  });

  it('gates MEMBERSHIP on group.member.manage alone — role assignment survives its absence', async () => {
    // The sharp case (`FE-D47`): dropping only `group.member.manage` must
    // remove Manage members and NOTHING else. Before the ratified verbs,
    // membership was mapped onto `role.assign`, so these two moved together
    // and this distinction could not be expressed.
    const user = userEvent.setup();
    render({}, ['group.view', 'group.edit', 'group.deactivate', 'role.assign']);
    await openRowMenu(user, 'Payments Operations');

    expect(screen.queryByRole('menuitem', { name: 'Manage members' })).toBeNull();
    expect(screen.getByRole('menuitem', { name: 'Manage role assignments' })).not.toBeNull();
    expect(screen.getByRole('menuitem', { name: 'Edit' })).not.toBeNull();
    expect(screen.getByRole('menuitem', { name: 'Deactivate' })).not.toBeNull();
  });

  it('gates a group ROLE assignment on role.assign alone — membership survives its absence', async () => {
    const user = userEvent.setup();
    render({}, ['group.view', 'group.member.manage']);
    await openRowMenu(user, 'Payments Operations');

    expect(screen.getByRole('menuitem', { name: 'Manage members' })).not.toBeNull();
    expect(screen.queryByRole('menuitem', { name: 'Manage role assignments' })).toBeNull();
  });

  it('never offers to edit an IdP-managed group', async () => {
    const user = userEvent.setup();
    render();
    await openRowMenu(user, 'platform-sre');

    expect(screen.queryByRole('menuitem', { name: 'Edit' })).toBeNull();
    expect(screen.queryByRole('menuitem', { name: 'Manage members' })).toBeNull();
    // Role assignment is still ours to make — only identity is the provider's.
    expect(screen.getByRole('menuitem', { name: 'Manage role assignments' })).not.toBeNull();
  });
});
