import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { LocalUser } from '@shared/testdoubles/data/rbac';

import { renderWithProviders, screen, within } from '../../../test/renderWithProviders';
import type { AdminRepository } from '../adminRepository';
import { stubAdminRepository } from '../adminRepository.testdouble';

import { UsersView } from './UsersView';

const USERS: LocalUser[] = [
  {
    id: 'u-devon',
    name: 'Devon Ramirez',
    email: 'devon@acme.example',
    status: 'active',
    lastSignIn: '2m ago',
    createdBy: 'installer bootstrap',
    organization: 'Acme Financial',
    isBootstrapAdmin: true,
  },
  {
    id: 'u-priya',
    name: 'Priya Nair',
    email: 'priya@acme.example',
    status: 'deactivated',
    lastSignIn: '3mo ago',
    createdBy: 'installer bootstrap',
    organization: 'Globex',
  },
];

function stubRepository(overrides: Partial<AdminRepository> = {}): AdminRepository {
  return stubAdminRepository({
    users: () => Promise.resolve(USERS),
    setUserActive: () => Promise.resolve(),
    ...overrides,
  });
}

describe('UsersView', () => {
  it('lists local users with their status, and never offers a delete', async () => {
    renderWithProviders(<UsersView repository={stubRepository()} />);

    expect(await screen.findByRole('heading', { name: 'Users' })).not.toBeNull();
    expect(screen.getByText('Devon Ramirez')).not.toBeNull();
    expect(screen.getByText('Priya Nair')).not.toBeNull();
    expect(screen.getByText('Active')).not.toBeNull();
    expect(screen.getByText('Deactivated')).not.toBeNull();
    expect(screen.queryByRole('button', { name: /delete/i })).toBeNull();
    expect(screen.getByText(/Deactivate, never delete/)).not.toBeNull();
  });

  it('deactivates an active user and reactivates a deactivated one — the only lifecycle actions', async () => {
    const user = userEvent.setup();
    const setUserActive = vi.fn<AdminRepository['setUserActive']>(() => Promise.resolve());
    // An ORDINARY active account: Devon is the last active bootstrap super
    // admin and is deliberately undeactivatable (`D19` 🔒, `FE-D50`).
    const mira: LocalUser = {
      id: 'u-mira',
      name: 'Mira Chen',
      email: 'mira@acme.example',
      status: 'active',
      lastSignIn: '1h ago',
      createdBy: 'installer bootstrap',
      organization: 'Acme Financial',
    };
    renderWithProviders(
      <UsersView
        repository={stubRepository({ setUserActive, users: () => Promise.resolve([...USERS, mira]) })}
      />,
    );

    const miraRow = within(await screen.findByRole('row', { name: /Mira Chen/ }));
    await user.click(miraRow.getByRole('button', { name: 'Deactivate' }));
    expect(setUserActive).toHaveBeenCalledWith('u-mira', false);

    const priyaRow = within(screen.getByRole('row', { name: /Priya Nair/ }));
    await user.click(priyaRow.getByRole('button', { name: 'Reactivate' }));
    expect(setUserActive).toHaveBeenCalledWith('u-priya', true);
  });

  it('states what an IdP migration removes and what survives, and does not build a bootstrap flow', async () => {
    renderWithProviders(<UsersView repository={stubRepository()} />);

    await screen.findByRole('heading', { name: 'Users' });
    expect(screen.getByText(/Everything on this screen is a stopgap/)).not.toBeNull();
    expect(screen.getByText(/the list itself survives/)).not.toBeNull();
    expect(
      screen.getByText(/created directly in the database at installation/),
    ).not.toBeNull();
    // `D19` settles the MODEL; the console still builds no bootstrap flow
    // and never handles a credential.
    expect(screen.queryByLabelText(/initial secret/i)).toBeNull();
    expect(screen.queryByLabelText(/password/i)).toBeNull();
  });

  it('opens the Add user dialog from the header action', async () => {
    const user = userEvent.setup();
    renderWithProviders(<UsersView repository={stubRepository()} />);

    await user.click(await screen.findByRole('button', { name: 'Add user' }));
    expect(screen.getByRole('dialog', { name: 'Add a user' })).not.toBeNull();
  });

  it('shows an error panel with retry when users fail to load', async () => {
    const users = vi
      .fn<AdminRepository['users']>()
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValue(USERS);
    renderWithProviders(<UsersView repository={stubRepository({ users })} />);

    expect(await screen.findByText('Users could not be loaded')).not.toBeNull();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Devon Ramirez')).not.toBeNull();
  });
});

describe('UsersView — the Bootstrap Super Admin (`D19` 🔒, `FE-D50`)', () => {
  it('marks the bootstrap admin row', async () => {
    renderWithProviders(<UsersView repository={stubRepository()} />);

    const row = (await screen.findByText('Devon Ramirez')).closest('tr') as HTMLElement;
    expect(within(row).getByText('Bootstrap admin')).not.toBeNull();

    const other = screen.getByText('Priya Nair').closest('tr') as HTMLElement;
    expect(within(other).queryByText('Bootstrap admin')).toBeNull();
  });

  it('disables Deactivate for the LAST active bootstrap admin, with the reason on screen', async () => {
    const setUserActive = vi.fn<AdminRepository['setUserActive']>(() => Promise.resolve());
    const user = userEvent.setup();
    renderWithProviders(<UsersView repository={stubRepository({ setUserActive })} />);

    const row = (await screen.findByText('Devon Ramirez')).closest('tr') as HTMLElement;
    const button = within(row).getByRole('button', { name: 'Deactivate' });

    // Disabled with a REASON, not omitted: the operator holds the
    // permission — it is the system that refuses (`FE-D50`).
    expect(button.hasAttribute('disabled')).toBe(true);
    expect(
      within(row).getByText(/last active bootstrap super admin cannot be deactivated/i),
    ).not.toBeNull();

    // A disabled control cannot be clicked at all — asserting the write
    // never fires is what proves the guard, not the attribute alone.
    await expect(user.click(button)).rejects.toThrow(/pointer-events/);
    expect(setUserActive).not.toHaveBeenCalled();
  });

  it('leaves Deactivate usable once a SECOND bootstrap admin is active', async () => {
    const setUserActive = vi.fn<AdminRepository['setUserActive']>(() => Promise.resolve());
    const user = userEvent.setup();
    const twoAdmins: LocalUser[] = [
      ...USERS,
      {
        id: 'u-root2',
        name: 'Second Root',
        email: 'root2@acme.example',
        status: 'active',
        lastSignIn: 'never',
        createdBy: 'installer bootstrap',
        organization: 'Acme Financial',
        isBootstrapAdmin: true,
      },
    ];
    renderWithProviders(
      <UsersView
        repository={stubRepository({ setUserActive, users: () => Promise.resolve(twoAdmins) })}
      />,
    );

    const row = (await screen.findByText('Devon Ramirez')).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Deactivate' }));
    expect(setUserActive).toHaveBeenCalledWith('u-devon', false);
  });

  it('says on screen that hashing, first-login, audit and sessions are backend work', async () => {
    renderWithProviders(<UsersView repository={stubRepository()} />);

    expect(
      await screen.findByText(
        /Password hashing, forced replacement at first sign-in, the audit record and session invalidation are install-time and backend responsibilities/i,
      ),
    ).not.toBeNull();
  });
});
