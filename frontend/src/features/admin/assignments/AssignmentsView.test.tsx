import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type {
  RbacAssignment,
  RbacPrincipal,
  RbacRole,
  ScopeNode,
} from '@shared/testdoubles/data/rbac';
import { useOutcomeStore } from '@shared/ui';

import { renderWithProviders, screen, waitFor, within } from '../../../test/renderWithProviders';
import type { AdminRepository } from '../adminRepository';
import { stubAdminRepository } from '../adminRepository.testdouble';

import { AssignmentsView } from './AssignmentsView';
import { SELECTABLE_SCOPE_TYPES } from './ScopeSelector';

const SCOPE_TREE: ScopeNode = {
  name: 'All organizations',
  level: 'All',
  children: [
    {
      name: 'Acme Financial',
      level: 'Organization',
      children: [
        {
          name: 'Payments',
          level: 'Sub-organization',
          children: [
            {
              name: 'PROD',
              level: 'Environment',
              children: [{ name: 'cache-eu-1', level: 'Instance' }],
            },
          ],
        },
        {
          name: 'Trading',
          level: 'Sub-organization',
          children: [{ name: 'TEST', level: 'Environment' }],
        },
      ],
    },
    {
      name: 'Globex',
      level: 'Organization',
      children: [
        {
          name: 'Platform',
          level: 'Sub-organization',
          children: [{ name: 'DEV', level: 'Environment' }],
        },
      ],
    },
  ],
};

const ORG_OP: RbacRole = {
  id: 'org-op',
  name: 'Org Operator',
  kind: 'built-in',
  description: 'Operate instances.',
  permissionIds: ['instance.view', 'config.apply'],
};

const PLACEMENT: RbacRole = {
  id: 'placement',
  name: 'Placement Manager',
  kind: 'built-in',
  description: 'Place instances on targets.',
  permissionIds: ['instance.view', 'instance.bindTarget', 'target.use'],
};

const PRINCIPALS: RbacPrincipal[] = [
  {
    id: 'u-mira',
    kind: 'user',
    name: 'Mira Chen',
    sub: 'mira@acme.example',
    source: 'local',
    status: 'active',
  },
  {
    id: 'u-devon',
    kind: 'user',
    name: 'Devon Ramirez',
    sub: 'devon@acme.example',
    source: 'local',
    status: 'active',
  },
  {
    id: 'u-priya',
    kind: 'user',
    name: 'Priya Nair',
    sub: 'priya@acme.example',
    source: 'local',
    status: 'deactivated',
  },
  {
    id: 'g-pay-ops',
    kind: 'group',
    name: 'payments-operators',
    sub: 'IdP group · OIDC',
    source: 'idp',
    status: 'active',
    organization: 'Acme Financial',
  },
];

const ASSIGNMENTS: RbacAssignment[] = [
  // The installer-managed ROOT grant (`D19` 🔒). The console can never
  // create one; it must still render it, confer through it, and let an
  // administrator inspect it (`FE-D49`).
  {
    id: 'a1',
    principalId: 'u-devon',
    roleId: 'org-op',
    scope: [],
    status: 'active',
    assignedBy: 'installer bootstrap',
    assignedAt: '2026-01-08T10:00:00Z',
  },
  {
    id: 'a2',
    principalId: 'u-mira',
    roleId: 'org-op',
    scope: ['Acme Financial'],
    status: 'active',
    assignedBy: 'Devon Ramirez',
    assignedAt: '2026-02-14T09:30:00Z',
  },
  {
    id: 'a3',
    principalId: 'g-pay-ops',
    roleId: 'placement',
    scope: ['Acme Financial', 'Payments', 'PROD'],
    status: 'active',
    assignedBy: 'Devon Ramirez',
    assignedAt: '2026-05-21T08:00:00Z',
  },
];

function stubRepository(overrides: Partial<AdminRepository> = {}): AdminRepository {
  return stubAdminRepository({
    roles: () => Promise.resolve([ORG_OP, PLACEMENT]),
    principals: () => Promise.resolve(PRINCIPALS),
    scopeTree: () => Promise.resolve(SCOPE_TREE),
    assignments: () => Promise.resolve(ASSIGNMENTS),
    createAssignment: (draft) =>
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
        correlationId: 'corr-assign-1',
      }),
    ...overrides,
  });
}

/** Opens the New assignment dialog and returns it. */
async function openDialog(user: ReturnType<typeof userEvent.setup>): Promise<HTMLElement> {
  await user.click(await screen.findByRole('button', { name: 'New assignment' }));
  return screen.getByRole('dialog');
}

/**
 * MUI renders a required field's label as "Name *", so every lookup here
 * matches by PREFIX rather than by exact text — otherwise adding `required`
 * to a control silently breaks its test.
 */
function field(dialog: HTMLElement, label: string): HTMLElement {
  return within(dialog).getByLabelText(new RegExp(`^${label}\\s*\\*?$`));
}

async function chooseOption(
  user: ReturnType<typeof userEvent.setup>,
  dialog: HTMLElement,
  label: string,
  option: string | RegExp,
): Promise<void> {
  await user.click(field(dialog, label));
  await user.click(await screen.findByRole('option', { name: option }));
}

describe('AssignmentsView', () => {
  it('lists assignments by principal, discriminating a group from a user', async () => {
    renderWithProviders(<AssignmentsView repository={stubRepository()} />);

    const miraRow = (await screen.findByText('Mira Chen')).closest('tr') as HTMLElement;
    expect(within(miraRow).getByText('Org Operator')).not.toBeNull();
    expect(within(miraRow).getByText('Acme Financial')).not.toBeNull();
    expect(within(miraRow).getByText('User')).not.toBeNull();

    const groupRow = screen.getByText('payments-operators').closest('tr') as HTMLElement;
    // A group principal is marked as such — not rendered as if it were a user id.
    expect(within(groupRow).getByText('IdP group')).not.toBeNull();
    expect(within(groupRow).getByText('Group')).not.toBeNull();
    expect(within(miraRow).queryByText('IdP group')).toBeNull();
  });

  it('by scope: a grant at the node is direct, one from an ancestor is inherited', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AssignmentsView repository={stubRepository()} />);

    await user.click(await screen.findByRole('button', { name: 'By scope' }));
    await user.click(await screen.findByRole('button', { name: 'PROD' }));

    const paymentsOps = await screen.findByText('payments-operators');
    const miraEntry = screen.getByText('Mira Chen').closest('[data-confer]') as HTMLElement;
    const groupEntry = paymentsOps.closest('[data-confer]') as HTMLElement;

    expect(within(groupEntry).getByText('direct')).not.toBeNull();
    expect(within(miraEntry).getByText('inherited')).not.toBeNull();
    expect(
      screen.getByText(/showing it as .direct. is how someone deletes an assignment/i),
    ).not.toBeNull();
  });

  it('creates a USER assignment at an organization scope', async () => {
    const user = userEvent.setup();
    const createAssignment = vi.fn<AdminRepository['createAssignment']>((draft) =>
      stubRepository().createAssignment(draft),
    );
    renderWithProviders(<AssignmentsView repository={stubRepository({ createAssignment })} />);

    const dialog = await openDialog(user);
    const create = within(dialog).getByRole('button', { name: 'Create assignment' });
    expect((create as HTMLButtonElement).disabled).toBe(true);

    await chooseOption(user, dialog, 'Principal', /Mira Chen/);
    expect((create as HTMLButtonElement).disabled).toBe(true);

    await chooseOption(user, dialog, 'Role', 'Org Operator');
    // Still blocked: the scope is incomplete until an organization is picked.
    expect((create as HTMLButtonElement).disabled).toBe(true);

    await chooseOption(user, dialog, 'Organization', 'Globex');
    expect((create as HTMLButtonElement).disabled).toBe(false);

    await user.click(create);

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(createAssignment).toHaveBeenCalledWith({
      principalId: 'u-mira',
      roleId: 'org-op',
      scope: ['Globex'],
    });
    expect(useOutcomeStore.getState().byResource['assignment:a-new']?.title).toBe(
      'Assignment created',
    );
  });

  it('the principal type selector swaps the picker between users and groups', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AssignmentsView repository={stubRepository()} />);

    const dialog = await openDialog(user);
    await user.click(field(dialog, 'Principal'));
    expect(screen.queryByRole('option', { name: /payments-operators/ })).toBeNull();
    await user.keyboard('{Escape}');

    await user.click(within(dialog).getByRole('button', { name: 'Group' }));
    await user.click(field(dialog, 'Principal'));
    expect(await screen.findByRole('option', { name: /payments-operators/ })).not.toBeNull();
    expect(screen.queryByRole('option', { name: /Mira Chen/ })).toBeNull();
  });

  it('locks a group assignment to the group’s own organization', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AssignmentsView repository={stubRepository()} />);

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole('button', { name: 'Group' }));
    await chooseOption(user, dialog, 'Principal', /payments-operators/);

    // The organization is displayed, fixed — there is no combobox to open,
    // so granting inside another organization is unreachable, not refused.
    const organization = field(dialog, 'Organization') as HTMLInputElement;
    expect(organization.value).toBe('Acme Financial');
    expect(organization.readOnly).toBe(true);
    expect(within(dialog).getByText(/can never be granted access outside it/)).not.toBeNull();
  });

  it('refuses a duplicate assignment for the same principal, role and exact scope', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AssignmentsView repository={stubRepository()} />);

    const dialog = await openDialog(user);
    await chooseOption(user, dialog, 'Principal', /Mira Chen/);
    await chooseOption(user, dialog, 'Role', 'Org Operator');
    await chooseOption(user, dialog, 'Organization', 'Acme Financial');

    expect(
      within(dialog).getByText(/already holds that role at exactly that scope/),
    ).not.toBeNull();
    expect(within(dialog).getByRole('button', { name: 'Create assignment' })).toBeDisabled();
  });

  it('refuses an assignment to a deactivated principal', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AssignmentsView repository={stubRepository()} />);

    const dialog = await openDialog(user);
    await chooseOption(user, dialog, 'Principal', /Priya Nair/);
    await chooseOption(user, dialog, 'Role', 'Org Operator');
    await chooseOption(user, dialog, 'Organization', 'Globex');

    expect(within(dialog).getByText(/deactivated and can hold no new assignment/)).not.toBeNull();
    expect(within(dialog).getByRole('button', { name: 'Create assignment' })).toBeDisabled();
  });

  it('offers only sub-organizations of the chosen organization, and clears them when it changes', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AssignmentsView repository={stubRepository()} />);

    const dialog = await openDialog(user);
    await chooseOption(user, dialog, 'Scope type', 'Sub-organization');
    await chooseOption(user, dialog, 'Organization', 'Acme Financial');
    await user.click(field(dialog, 'Sub-organization'));
    expect(await screen.findByRole('option', { name: 'Payments' })).not.toBeNull();
    expect(screen.queryByRole('option', { name: 'Platform' })).toBeNull();
    await user.click(screen.getByRole('option', { name: 'Payments' }));

    // Changing the organization must not leave a sub-organization of the old
    // one behind — the selection is cleared, and only Globex's own is offered.
    await chooseOption(user, dialog, 'Organization', 'Globex');
    expect(field(dialog, 'Sub-organization').textContent).not.toContain('Payments');
    await user.click(field(dialog, 'Sub-organization'));
    expect(await screen.findByRole('option', { name: 'Platform' })).not.toBeNull();
    expect(screen.queryByRole('option', { name: 'Payments' })).toBeNull();
  });

  it('warns about the two-sided rule when the chosen role grants a two-sided verb', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AssignmentsView repository={stubRepository()} />);

    const dialog = await openDialog(user);
    expect(within(dialog).queryByText(/two-sided rule/i)).toBeNull();
    await chooseOption(user, dialog, 'Role', 'Placement Manager');
    expect(within(dialog).getByText(/two-sided rule/i)).not.toBeNull();
  });

  it('shows an error panel with retry when assignments fail to load', async () => {
    const assignments = vi
      .fn<AdminRepository['assignments']>()
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValue(ASSIGNMENTS);
    renderWithProviders(<AssignmentsView repository={stubRepository({ assignments })} />);

    expect(await screen.findByText('Assignments could not be loaded')).not.toBeNull();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Mira Chen')).not.toBeNull();
  });
});

describe('AssignmentsView — scope levels (`FE-D49`)', () => {
  it('composes an INSTANCE-scoped assignment through four dependent selects', async () => {
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
        correlationId: 'corr-assign-1',
      }),
    );
    renderWithProviders(<AssignmentsView repository={stubRepository({ createAssignment })} />);

    const dialog = await openDialog(user);
    await chooseOption(user, dialog, 'Principal', 'Mira Chen');
    await chooseOption(user, dialog, 'Role', 'Org Operator');
    await chooseOption(user, dialog, 'Scope type', 'Instance');
    await chooseOption(user, dialog, 'Organization', 'Acme Financial');
    await chooseOption(user, dialog, 'Sub-organization', 'Payments');
    await chooseOption(user, dialog, 'Environment', 'PROD');
    await chooseOption(user, dialog, 'Instance', 'cache-eu-1');

    await user.click(within(dialog).getByRole('button', { name: 'Create assignment' }));

    await waitFor(() =>
      expect(createAssignment).toHaveBeenCalledWith({
        principalId: 'u-mira',
        roleId: 'org-op',
        scope: ['Acme Financial', 'Payments', 'PROD', 'cache-eu-1'],
      }),
    );
  });

  it('offers exactly four scope types — no root/global one (`D19`)', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AssignmentsView repository={stubRepository()} />);

    const dialog = await openDialog(user);
    await user.click(field(dialog, 'Scope type'));

    const options = await screen.findAllByRole('option');
    expect(options.map((option) => option.textContent)).toEqual([
      'Organization',
      'Sub-organization',
      'Environment',
      'Instance',
    ]);
    expect(SELECTABLE_SCOPE_TYPES).toEqual([
      'organization',
      'subOrganization',
      'environment',
      'instance',
    ]);
  });

  it('renders the installer-managed root assignment, labelled system-managed', async () => {
    renderWithProviders(<AssignmentsView repository={stubRepository()} />);

    const devonRow = (await screen.findByText('Devon Ramirez')).closest('tr') as HTMLElement;
    expect(within(devonRow).getByText('All organizations')).not.toBeNull();
    expect(within(devonRow).getByText('System-managed')).not.toBeNull();

    // Only the ROOT row carries it — an ordinary organization grant is not
    // system-managed.
    const miraRow = screen.getByText('Mira Chen').closest('tr') as HTMLElement;
    expect(within(miraRow).queryByText('System-managed')).toBeNull();
  });

  it('by scope: the default node is the root, where the root grant is inspectable', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AssignmentsView repository={stubRepository()} />);

    await user.click(await screen.findByRole('button', { name: 'By scope' }));

    // The root's path is `[]`, so the root grant is DIRECT here — and the
    // two narrower ones do not reach up to it. Before `FE-D49` this view
    // defaulted to `['All organizations']`, a scope that does not exist.
    await waitFor(() => expect(document.querySelector('[data-confer="a1"]')).not.toBeNull());
    const card = document.querySelector('[data-confer="a1"]') as HTMLElement;
    expect(document.querySelector('[data-confer="a2"]')).toBeNull();
    expect(within(card).getByText('direct')).not.toBeNull();
  });
});
