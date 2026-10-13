import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { PERMISSION_CATALOG } from '@shared/authz/catalog';
import { useOutcomeStore } from '@shared/ui';

import { renderWithProviders, screen, waitFor, within } from '../../../test/renderWithProviders';
import type { AdminRepository, SaveRoleResult } from '../adminRepository';
import { stubAdminRepository } from '../adminRepository.testdouble';

import { RolesView } from './RolesView';

const BUILT_IN_ORG_OP = {
  id: 'org-op',
  name: 'Org Operator',
  kind: 'built-in' as const,
  description: 'Operate instances within one organization.',
  permissionIds: ['instance.view', 'config.view', 'config.apply', 'instance.restart'],
};

const CUSTOM_PAYMENTS = {
  id: 'payments-op',
  name: 'Payments Operator (no restart)',
  kind: 'custom' as const,
  clonedFrom: 'Org Operator',
  description: 'The built-in Org Operator, restart removed.',
  permissionIds: ['instance.view', 'config.view', 'config.apply'],
};

const SUPER = {
  id: 'super',
  name: 'Solace Super-admin',
  kind: 'built-in' as const,
  description: 'Every permission, across all organizations.',
  permissionIds: '*' as const,
};

function stubRepository(overrides: Partial<AdminRepository> = {}): AdminRepository {
  return stubAdminRepository({
    roles: () => Promise.resolve([SUPER, BUILT_IN_ORG_OP, CUSTOM_PAYMENTS]),
    saveRole: (draft) =>
      Promise.resolve<SaveRoleResult>({
        role: {
          id: 'custom-new',
          name: draft.name,
          kind: 'custom',
          description: draft.description,
          permissionIds: [...draft.permissionIds],
        },
        correlationId: 'corr-role-1',
      }),
    ...overrides,
  });
}

describe('RolesView', () => {
  it('lists built-in and custom roles in their own sections', async () => {
    renderWithProviders(<RolesView repository={stubRepository()} />);

    expect(await screen.findByRole('button', { name: 'Solace Super-admin' })).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Org Operator' })).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Payments Operator (no restart)' })).not.toBeNull();
    // '*' role shows "all permissions", not a number.
    expect(screen.getByText('all permissions')).not.toBeNull();
  });

  it('opens a built-in role read-only, with clone the only way to change it', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RolesView repository={stubRepository()} />);

    await user.click(await screen.findByRole('button', { name: 'Org Operator' }));

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(/Built-in roles can.t be edited/)).not.toBeNull();
    expect(within(dialog).queryByLabelText('Role name')).toBeNull();
    expect(within(dialog).getByRole('button', { name: 'Clone…' })).not.toBeNull();
    // Every permission checkbox is disabled in inspect mode.
    for (const box of within(dialog).getAllByRole('checkbox')) {
      expect((box as HTMLInputElement).disabled).toBe(true);
    }
  });

  it('marks every initial D10 operation grantable', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RolesView repository={stubRepository()} />);

    await user.click(await screen.findByRole('button', { name: 'Org Operator' }));
    const dialog = screen.getByRole('dialog');

    expect(within(dialog).getByText('Initial catalog enabled')).not.toBeNull();
    expect(within(dialog).queryByText('candidate · absent')).toBeNull();
    expect(within(dialog).getAllByText('grantable').length).toBe(PERMISSION_CATALOG.length);
    expect(within(dialog).getAllByText('high-consequence').length).toBeGreaterThan(0);
    expect(within(dialog).getAllByText('two-sided').length).toBeGreaterThan(0);
  });

  it('clones a built-in role into an editable draft seeded with its permissions', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RolesView repository={stubRepository()} />);

    await user.click(await screen.findByRole('button', { name: 'Org Operator' }));
    await user.click(screen.getByRole('button', { name: 'Clone…' }));

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'Clone role' })).not.toBeNull();
    expect(within(dialog).getByRole('textbox', { name: /Role name/ })).toHaveValue(
      'Org Operator (copy)',
    );
    // Seeded selection carried over from the source role.
    const instanceView = within(dialog).getByRole('checkbox', { name: 'instance.view' });
    expect((instanceView as HTMLInputElement).checked).toBe(true);
  });

  it('opens a custom role in edit mode with its fields prefilled', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RolesView repository={stubRepository()} />);

    await user.click(await screen.findByRole('button', { name: 'Payments Operator (no restart)' }));

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'Edit role' })).not.toBeNull();
    expect(within(dialog).getByRole('textbox', { name: /Role name/ })).toHaveValue(
      'Payments Operator (no restart)',
    );
  });

  it('creates a new custom role — Save is blocked until it has a name, then it saves and closes', async () => {
    const user = userEvent.setup();
    const saveRole = vi.fn<AdminRepository['saveRole']>((draft) =>
      stubRepository().saveRole(draft),
    );
    renderWithProviders(<RolesView repository={stubRepository({ saveRole })} />);

    await user.click(await screen.findByRole('button', { name: 'New custom role' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'New custom role' })).not.toBeNull();

    const save = within(dialog).getByRole('button', { name: 'Save role' });
    expect((save as HTMLButtonElement).disabled).toBe(true);

    await user.type(within(dialog).getByRole('textbox', { name: /Role name/ }), 'Cache Reader');
    await user.click(within(dialog).getByRole('checkbox', { name: 'instance.view' }));
    await user.click(within(dialog).getByRole('button', { name: 'Save role' }));

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(saveRole).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'Cache Reader', permissionIds: ['instance.view'] }),
    );
    // The mutation reports its outcome with the server correlation id (FE-D13).
    const outcome = useOutcomeStore.getState().byResource['role:custom-new'];
    expect(outcome?.title).toBe('Role “Cache Reader” saved');
    expect(outcome?.correlationId).toBe('corr-role-1');
  });

  it('shows an error panel with retry when roles fail to load', async () => {
    const roles = vi
      .fn<AdminRepository['roles']>()
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValue([BUILT_IN_ORG_OP]);
    renderWithProviders(<RolesView repository={stubRepository({ roles })} />);

    expect(await screen.findByText('Roles could not be loaded')).not.toBeNull();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('button', { name: 'Org Operator' })).not.toBeNull();
  });
});
