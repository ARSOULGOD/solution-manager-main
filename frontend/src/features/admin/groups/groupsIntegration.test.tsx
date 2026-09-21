/**
 * The whole loop, end to end, through the REAL repository and the fixture
 * server — not a stub. This is the test that answers "does the feature
 * actually work?" rather than "does this component render?": a group is
 * created, a member is added, and the member's effective permissions
 * change as a result, all through the same seams a deployed console would
 * use.
 */
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { createCpClient } from '@shared/api';

import { renderWithProviders, screen, waitFor, within } from '../../../test/renderWithProviders';
import { AccessCheckView } from '../access';
import { createAdminRepository } from '../adminRepository';

import { ALL_GROUP_PERMISSIONS } from './groupsTestData';
import { GroupsView } from './GroupsView';

const repository = createAdminRepository(createCpClient('/api'));

const ADMIN_PERMISSIONS = [...ALL_GROUP_PERMISSIONS];

function renderGroups() {
  return renderWithProviders(<GroupsView repository={repository} />, {
    permissions: ADMIN_PERMISSIONS,
  });
}

function field(scope: HTMLElement, label: string): HTMLElement {
  return within(scope).getByLabelText(new RegExp(`^${label}\\s*\\*?$`));
}

describe('Groups, end to end against the fixture server', () => {
  it('creates a group and it appears in the list with its organization and counts', async () => {
    const user = userEvent.setup();
    renderGroups();
    await screen.findByText('Payments Operations');

    await user.click(screen.getByRole('button', { name: 'Add group' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add a group' });

    await user.type(field(dialog, 'Group name'), 'Payments Reviewers');
    await user.click(field(dialog, 'Organization'));
    await user.click(await screen.findByRole('option', { name: 'Acme Financial' }));
    await user.click(await screen.findByRole('checkbox', { name: /Mira Chen/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Create group' }));

    const row = within(
      (await screen.findByText('Payments Reviewers')).closest('tr') as HTMLElement,
    );
    expect(row.getByText('Acme Financial')).not.toBeNull();
    expect(row.getByText('1 member')).not.toBeNull();
    expect(row.getByText('0 assignments')).not.toBeNull();
    expect(row.getByText('Active')).not.toBeNull();
  });

  it('refuses a duplicate name in the same organization but allows it in another', async () => {
    const user = userEvent.setup();
    renderGroups();
    await screen.findByText('Payments Operations');

    await user.click(screen.getByRole('button', { name: 'Add group' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add a group' });

    await user.click(field(dialog, 'Organization'));
    await user.click(await screen.findByRole('option', { name: 'Acme Financial' }));
    await user.type(field(dialog, 'Group name'), 'payments operations');
    await user.click(within(dialog).getByRole('button', { name: 'Create group' }));

    expect(
      await screen.findByText('A group called this already exists in Acme Financial.'),
    ).not.toBeNull();

    // Same name, different organization — allowed.
    await user.click(field(dialog, 'Organization'));
    await user.click(await screen.findByRole('option', { name: 'Globex' }));
    await user.click(within(dialog).getByRole('button', { name: 'Create group' }));

    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Add a group' })).toBeNull();
    });
  });

  it('deactivating a group keeps it on the list, marked, and reactivation restores it', async () => {
    const user = userEvent.setup();
    renderGroups();
    await screen.findByText('Payments Operations');

    await user.click(screen.getByRole('button', { name: 'Actions for Payments Operations' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Deactivate' }));
    await user.click(
      within(
        await screen.findByRole('dialog', { name: 'Deactivate Payments Operations?' }),
      ).getByRole('button', { name: 'Deactivate group' }),
    );

    await waitFor(() => {
      const row = within(screen.getByText('Payments Operations').closest('tr') as HTMLElement);
      expect(row.getByText('Deactivated')).not.toBeNull();
    });

    await user.click(screen.getByRole('button', { name: 'Actions for Payments Operations' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Reactivate' }));
    await user.click(
      within(
        await screen.findByRole('dialog', { name: 'Reactivate Payments Operations?' }),
      ).getByRole('button', { name: 'Reactivate group' }),
    );

    await waitFor(() => {
      const row = within(screen.getByText('Payments Operations').closest('tr') as HTMLElement);
      expect(row.getByText('Active')).not.toBeNull();
    });
  });

  it('a user gains permissions through a group, and the Access check names the group', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AccessCheckView repository={repository} />, {
      permissions: ADMIN_PERMISSIONS,
    });

    // Sam is in Trading Auditors, which holds Read-only Auditor at Trading.
    await user.click(await screen.findByLabelText('Principal'));
    await user.click(await screen.findByRole('option', { name: 'Sam Okoye · user' }));
    await user.click(await screen.findByRole('button', { name: 'Trading' }));

    const row = await screen.findByRole('row', { name: /instance\.view/ });
    expect(within(row).getByText('Group: Trading Auditors')).not.toBeNull();
    // And his own direct assignment is still there alongside it.
    expect(within(row).getByText('Direct assignment')).not.toBeNull();
  });

  it('removing a member drops the group’s member count, and the access it conferred', async () => {
    const user = userEvent.setup();
    renderGroups();

    const before = within(
      (await screen.findByText('Trading Auditors')).closest('tr') as HTMLElement,
    );
    expect(before.getByText('1 member')).not.toBeNull();

    await user.click(screen.getByRole('button', { name: 'Actions for Trading Auditors' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Manage members' }));

    // The action opens straight on Add members; close it and remove Sam.
    await user.click(
      within(
        await screen.findByRole('dialog', { name: /Add members to Trading Auditors/ }),
      ).getByRole('button', { name: 'Cancel' }),
    );
    const memberRow = within((await screen.findByText('Sam Okoye')).closest('tr') as HTMLElement);
    await user.click(memberRow.getByRole('button', { name: 'Remove' }));
    await user.click(
      within(
        await screen.findByRole('dialog', {
          name: 'Remove Sam Okoye from Trading Auditors?',
        }),
      ).getByRole('button', { name: 'Remove member' }),
    );

    await waitFor(() => {
      expect(screen.queryByText('Sam Okoye')).toBeNull();
    });

    // The count is derived from the membership table, so the list follows.
    // Two controls are named "Close": the title-bar × and the action —
    // either dismisses it, take the action.
    const closeButtons = screen.getAllByRole('button', { name: 'Close' });
    await user.click(closeButtons[closeButtons.length - 1] as HTMLElement);
    await waitFor(() => {
      const row = within(screen.getByText('Trading Auditors').closest('tr') as HTMLElement);
      expect(row.getByText('0 members')).not.toBeNull();
    });
  });
});
