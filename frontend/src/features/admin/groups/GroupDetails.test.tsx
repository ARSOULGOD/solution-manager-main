import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen, waitFor, within } from '../../../test/renderWithProviders';
import type { AdminRepository } from '../adminRepository';
import { stubAdminRepository } from '../adminRepository.testdouble';

import { GroupDetails } from './GroupDetails';
import {
  ALL_GROUP_PERMISSIONS,
  GROUP_ASSIGNMENTS,
  IDP_GROUP,
  MEMBERS,
  PAYMENTS_OPS,
  ROLES,
  SCOPE_TREE,
  USERS,
} from './groupsTestData';

function render(
  overrides: Partial<AdminRepository> = {},
  {
    group = PAYMENTS_OPS,
    permissions = ALL_GROUP_PERMISSIONS,
    initialAction,
  }: {
    group?: typeof PAYMENTS_OPS;
    permissions?: readonly string[];
    initialAction?: 'members' | 'assignments';
  } = {},
) {
  return renderWithProviders(
    <GroupDetails
      repository={stubAdminRepository({
        groupMembers: () => Promise.resolve([...MEMBERS]),
        groupAssignments: () => Promise.resolve([...GROUP_ASSIGNMENTS]),
        ...overrides,
      })}
      group={group}
      users={USERS}
      roles={ROLES}
      scopeTree={SCOPE_TREE}
      onClose={vi.fn()}
      onEdit={vi.fn()}
      {...(initialAction !== undefined ? { initialAction } : {})}
    />,
    { permissions: [...permissions] },
  );
}

describe('GroupDetails — overview', () => {
  it('names every overview field', async () => {
    render();
    await screen.findByRole('heading', { name: 'Overview' });

    // Matched inside the definition list only: 'Organization' also names a
    // column header in the assignments table below.
    const overview = screen.getByRole('heading', { name: 'Overview' }).parentElement as HTMLElement;
    for (const label of [
      'Group name',
      'Description',
      'Organization',
      'Status',
      'Created by',
      'Created',
      'Last updated by',
      'Last updated',
    ]) {
      expect(within(overview).getByText(label)).not.toBeNull();
    }
    expect(screen.getAllByText('Acme Financial').length).toBeGreaterThan(0);
  });
});

describe('GroupDetails — members', () => {
  it('lists each member with email, status, date added and who added them', async () => {
    render();
    const row = within((await screen.findByText('Mira Chen')).closest('tr') as HTMLElement);
    expect(row.getByText('mira@acme.example')).not.toBeNull();
    expect(row.getByText('Active')).not.toBeNull();
    expect(row.getByText('Devon Ramirez')).not.toBeNull();
  });

  it('marks a member who has since been deactivated instead of hiding them', async () => {
    render();
    const row = within((await screen.findByText('Priya Nair')).closest('tr') as HTMLElement);
    expect(row.getByText(/Deactivated user/)).not.toBeNull();
  });

  it('removes a member behind a confirmation naming both the user and the group', async () => {
    const user = userEvent.setup();
    const removeGroupMember = vi.fn<AdminRepository['removeGroupMember']>(() => Promise.resolve());
    render({ removeGroupMember });

    const row = within((await screen.findByText('Mira Chen')).closest('tr') as HTMLElement);
    await user.click(row.getByRole('button', { name: 'Remove' }));

    const dialog = await screen.findByRole('dialog', {
      name: 'Remove Mira Chen from Payments Operations?',
    });
    expect(
      within(dialog).getByText(/lose every permission they hold only through this group/),
    ).not.toBeNull();
    await user.click(within(dialog).getByRole('button', { name: 'Remove member' }));

    await waitFor(() => expect(removeGroupMember).toHaveBeenCalledWith(PAYMENTS_OPS.id, 'u-mira'));
  });

  it('adds members from a searchable picker that offers nobody ineligible', async () => {
    const user = userEvent.setup();
    const addGroupMembers = vi.fn<AdminRepository['addGroupMembers']>(() =>
      Promise.resolve({ members: [], correlationId: 'corr-add-1' }),
    );
    render({ addGroupMembers }, { initialAction: 'members' });

    const dialog = await screen.findByRole('dialog', {
      name: 'Add members to Payments Operations',
    });
    // Mira is already a member; Priya is deactivated; Sam shares the owning Organization.
    expect(within(dialog).queryByRole('checkbox', { name: /Mira Chen/ })).toBeNull();
    expect(within(dialog).queryByRole('checkbox', { name: /Priya Nair/ })).toBeNull();
    expect(within(dialog).getByRole('checkbox', { name: /Sam Okoye/ })).not.toBeNull();

    await user.click(within(dialog).getByRole('checkbox', { name: /Devon Ramirez/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Add members' }));

    await waitFor(() => expect(addGroupMembers).toHaveBeenCalledWith(PAYMENTS_OPS.id, ['u-devon']));
  });
});

describe('GroupDetails — role assignments', () => {
  it('lists an assignment split across the hierarchy, with its scope type', async () => {
    render();
    const row = within((await screen.findByText('Org Operator')).closest('tr') as HTMLElement);
    expect(row.getByText('Sub-organization')).not.toBeNull();
    expect(row.getByText('Acme Financial')).not.toBeNull();
    expect(row.getByText('Payments')).not.toBeNull();
    expect(row.getByText('Active')).not.toBeNull();
  });

  it('revokes an assignment behind a confirmation, never deleting it', async () => {
    const user = userEvent.setup();
    const revokeAssignment = vi.fn<AdminRepository['revokeAssignment']>(() => Promise.resolve());
    render({ revokeAssignment });

    const row = within((await screen.findByText('Org Operator')).closest('tr') as HTMLElement);
    await user.click(row.getByRole('button', { name: 'Remove' }));

    const dialog = await screen.findByRole('dialog', {
      name: 'Remove Org Operator at Acme Financial / Payments from Payments Operations?',
    });
    expect(within(dialog).getByText(/revoked, not deleted/)).not.toBeNull();
    await user.click(within(dialog).getByRole('button', { name: 'Remove assignment' }));

    await waitFor(() => expect(revokeAssignment).toHaveBeenCalledWith('a6'));
  });

  it('assigns a role at an ENVIRONMENT scope, locked to the group’s own organization', async () => {
    const user = userEvent.setup();
    const createAssignment = vi.fn<AdminRepository['createAssignment']>((draft) =>
      Promise.resolve({
        assignment: {
          id: 'a-new',
          principalId: draft.principalId,
          roleId: draft.roleId,
          scope: [...draft.scope],
          status: 'active' as const,
          assignedBy: 'Devon Ramirez',
          assignedAt: '2026-09-04T10:00:00Z',
        },
        correlationId: 'corr-assign-2',
      }),
    );
    render({ createAssignment }, { initialAction: 'assignments' });

    const dialog = await screen.findByRole('dialog', {
      name: 'Assign a role to Payments Operations',
    });
    const organization = within(dialog).getByLabelText(/^Organization/);
    expect(organization).toHaveValue('Acme Financial');
    expect(organization).toHaveAttribute('readonly');

    await user.click(within(dialog).getByLabelText(/^Role/));
    await user.click(await screen.findByRole('option', { name: 'Read-only Auditor' }));
    await user.click(within(dialog).getByLabelText(/^Scope type/));
    await user.click(await screen.findByRole('option', { name: 'Environment' }));
    await user.click(within(dialog).getByLabelText(/^Sub-organization/));
    await user.click(await screen.findByRole('option', { name: 'Payments' }));
    await user.click(within(dialog).getByLabelText(/^Environment/));
    await user.click(await screen.findByRole('option', { name: 'PROD' }));

    await user.click(within(dialog).getByRole('button', { name: 'Assign role' }));

    await waitFor(() =>
      expect(createAssignment).toHaveBeenCalledWith({
        principalId: PAYMENTS_OPS.id,
        roleId: 'auditor',
        scope: ['Acme Financial', 'Payments', 'PROD'],
      }),
    );
  });

  it('blocks an incomplete scope and a duplicate assignment', async () => {
    const user = userEvent.setup();
    render({}, { initialAction: 'assignments' });

    const dialog = await screen.findByRole('dialog', {
      name: 'Assign a role to Payments Operations',
    });
    const assign = within(dialog).getByRole('button', { name: 'Assign role' });

    await user.click(within(dialog).getByLabelText(/^Role/));
    await user.click(await screen.findByRole('option', { name: 'Org Operator' }));
    await user.click(within(dialog).getByLabelText(/^Scope type/));
    await user.click(await screen.findByRole('option', { name: 'Sub-organization' }));

    // Scope incomplete — no sub-organization chosen yet.
    expect(within(dialog).getByText('Choose every level of the scope.')).not.toBeNull();
    expect((assign as HTMLButtonElement).disabled).toBe(true);

    await user.click(within(dialog).getByLabelText(/^Sub-organization/));
    await user.click(await screen.findByRole('option', { name: 'Payments' }));

    // Now complete — but it duplicates the assignment the group already has.
    expect(
      within(dialog).getByText(/already holds that role at exactly that scope/),
    ).not.toBeNull();
    expect((assign as HTMLButtonElement).disabled).toBe(true);
  });
});

describe('GroupDetails — authorization and IdP', () => {
  it('omits member management for an operator who cannot assign roles', async () => {
    render({}, { permissions: ['user.view', 'user.create'] });
    await screen.findByRole('heading', { name: 'Members' });

    expect(screen.queryByRole('button', { name: 'Add members' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Assign role' })).toBeNull();
  });

  it('makes an IdP-managed group read-only for identity, and says so', async () => {
    render({}, { group: IDP_GROUP });
    expect(await screen.findByText(/supplied by the identity provider/)).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Add members' })).toBeNull();
    // Role assignment stays ours.
    expect(screen.getByRole('button', { name: 'Assign role' })).not.toBeNull();
  });

  it('refuses to assign a role to a DEACTIVATED group', async () => {
    render(
      {},
      {
        group: { ...PAYMENTS_OPS, status: 'deactivated' as const },
        initialAction: 'assignments',
      },
    );
    const dialog = await screen.findByRole('dialog', {
      name: 'Assign a role to Payments Operations',
    });
    expect(within(dialog).getByText(/Reactivate it before assigning a role/)).not.toBeNull();
    expect(within(dialog).getByRole('button', { name: 'Assign role' })).toBeDisabled();
  });
});
