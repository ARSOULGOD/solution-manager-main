import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { ScopeNode } from '@shared/testdoubles/data/rbac';
import { useOutcomeStore } from '@shared/ui';

import { renderWithProviders, screen, waitFor, within } from '../../../test/renderWithProviders';
import type { AdminRepository, CreateUserResult } from '../adminRepository';
import { stubAdminRepository } from '../adminRepository.testdouble';

import { AddUserDialog } from './AddUserDialog';

const SCOPE_TREE: ScopeNode = {
  name: 'All organizations',
  level: 'All',
  children: [
    { name: 'Acme Financial', level: 'Organization' },
    { name: 'Globex', level: 'Organization' },
  ],
};

function stubRepository(overrides: Partial<AdminRepository> = {}): AdminRepository {
  return stubAdminRepository({
    scopeTree: () => Promise.resolve(SCOPE_TREE),
    createUser: (draft) =>
      Promise.resolve<CreateUserResult>({
        user: {
          id: 'u-new',
          name: draft.name,
          email: draft.email,
          status: 'active',
          lastSignIn: 'never',
          createdBy: 'Devon Ramirez',
          organization: draft.organization,
        },
        correlationId: 'corr-user-1',
      }),
    ...overrides,
  });
}

describe('AddUserDialog', () => {
  it('separates identity from authorization — no role field, and a note that says so', () => {
    renderWithProviders(<AddUserDialog repository={stubRepository()} onClose={vi.fn()} />);

    const dialog = screen.getByRole('dialog', { name: 'Add a user' });
    expect(within(dialog).getByText(/Creating a user does not assign a role/)).not.toBeNull();
    expect(within(dialog).queryByLabelText(/role/i)).toBeNull();
  });

  it('sends no role of any kind — creating a user grants nothing (`D19` 🔒)', async () => {
    const user = userEvent.setup();
    const createUser = vi.fn<AdminRepository['createUser']>((draft) =>
      stubRepository().createUser(draft),
    );
    renderWithProviders(
      <AddUserDialog repository={stubRepository({ createUser })} onClose={vi.fn()} />,
    );

    const dialog = screen.getByRole('dialog', { name: 'Add a user' });
    await user.type(within(dialog).getByRole('textbox', { name: /Full name/ }), 'Ada Lovelace');
    await user.type(within(dialog).getByRole('textbox', { name: /Email/ }), 'ada@acme.example');
    await user.click(within(dialog).getByLabelText(/^Organization/));
    await user.click(await screen.findByRole('option', { name: 'Acme Financial' }));
    await user.click(within(dialog).getByRole('button', { name: 'Create user' }));

    await waitFor(() => expect(createUser).toHaveBeenCalled());
    // The payload's KEYS are the assertion: role assignment stays an
    // explicit, separate action on the Assignments screen, which is what
    // keeps a later IdP migration a change to identity only.
    const draft = createUser.mock.calls[0]?.[0];
    expect(Object.keys(draft ?? {}).sort()).toEqual([
      'credentialPath',
      'email',
      'name',
      'organization',
    ]);
  });

  it('defaults to the admin-set temporary credential — the chosen path — with the emailed invite disabled', () => {
    renderWithProviders(<AddUserDialog repository={stubRepository()} onClose={vi.fn()} />);

    const temp = screen.getByRole('radio', { name: 'Admin-set temporary credential' });
    const invite = screen.getByRole('radio', { name: 'Emailed invitation link' });
    expect((temp as HTMLInputElement).checked).toBe(true);
    expect((invite as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText('chosen')).not.toBeNull();
    expect(screen.getByText('needs email')).not.toBeNull();
  });

  it('blocks Create until a name and email are present, then creates with the default path and toasts', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const createUser = vi.fn<AdminRepository['createUser']>((draft) =>
      stubRepository().createUser(draft),
    );
    renderWithProviders(
      <AddUserDialog repository={stubRepository({ createUser })} onClose={onClose} />,
    );

    const dialog = screen.getByRole('dialog', { name: 'Add a user' });
    const create = within(dialog).getByRole('button', { name: 'Create user' });
    expect((create as HTMLButtonElement).disabled).toBe(true);

    await user.type(within(dialog).getByRole('textbox', { name: /Full name/ }), 'Ada Lovelace');
    await user.type(within(dialog).getByRole('textbox', { name: /Email/ }), 'ada@acme.example');
    // An account belongs to one organization — the top of the hierarchy.
    await user.click(within(dialog).getByLabelText(/^Organization/));
    await user.click(await screen.findByRole('option', { name: 'Acme Financial' }));
    await user.click(create);

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(createUser).toHaveBeenCalledWith({
      name: 'Ada Lovelace',
      email: 'ada@acme.example',
      organization: 'Acme Financial',
      credentialPath: 'temp',
    });
    const outcome = useOutcomeStore.getState().byResource['user:u-new'];
    expect(outcome?.title).toBe('User Ada Lovelace created');
    expect(outcome?.correlationId).toBe('corr-user-1');
  });

  it('passes the out-of-band path when the admin picks it', async () => {
    const user = userEvent.setup();
    const createUser = vi.fn<AdminRepository['createUser']>((draft) =>
      stubRepository().createUser(draft),
    );
    renderWithProviders(
      <AddUserDialog repository={stubRepository({ createUser })} onClose={vi.fn()} />,
    );

    const dialog = screen.getByRole('dialog', { name: 'Add a user' });
    await user.type(within(dialog).getByRole('textbox', { name: /Full name/ }), 'Grace Hopper');
    await user.type(within(dialog).getByRole('textbox', { name: /Email/ }), 'grace@acme.example');
    await user.click(within(dialog).getByLabelText(/^Organization/));
    await user.click(await screen.findByRole('option', { name: 'Acme Financial' }));
    await user.click(within(dialog).getByRole('radio', { name: 'Out-of-band handoff' }));
    await user.click(within(dialog).getByRole('button', { name: 'Create user' }));

    await waitFor(() =>
      expect(createUser).toHaveBeenCalledWith(expect.objectContaining({ credentialPath: 'oob' })),
    );
  });
});
