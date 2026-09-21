import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { AdminRepository, SaveRoleResult } from '../adminRepository';
import { stubAdminRepository } from '../adminRepository.testdouble';

import { RolesView } from './RolesView';

const ROLES: Awaited<ReturnType<AdminRepository['roles']>> = [
  {
    id: 'super',
    name: 'Solace Super-admin',
    kind: 'built-in',
    description: 'Every permission, across all organizations.',
    permissionIds: '*',
  },
  {
    id: 'org-op',
    name: 'Org Operator',
    kind: 'built-in',
    description: 'Operate instances within one organization.',
    permissionIds: ['instance.view', 'config.apply', 'instance.restart'],
  },
  {
    id: 'payments-op',
    name: 'Payments Operator (no restart)',
    kind: 'custom',
    description: 'Org Operator without restart.',
    permissionIds: ['instance.view', 'config.view'],
  },
];

function stubRepository(overrides: Partial<AdminRepository> = {}): AdminRepository {
  return stubAdminRepository({
    roles: () => Promise.resolve(ROLES),
    saveRole: (draft) =>
      Promise.resolve<SaveRoleResult>({
        role: {
          id: 'custom-new',
          name: draft.name,
          kind: 'custom',
          description: draft.description,
          permissionIds: [...draft.permissionIds],
        },
        correlationId: 'corr-a11y',
      }),
    ...overrides,
  });
}

const modes = ['light', 'dark'] as const;

describe('RolesView a11y', () => {
  it.each(modes)('the role list has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(<RolesView repository={stubRepository()} />, {
      mode,
    });
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Org Operator' })).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the read-only inspect dialog has no violations in %s mode', async (mode) => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(<RolesView repository={stubRepository()} />, {
      mode,
    });
    await user.click(await screen.findByRole('button', { name: 'Org Operator' }));
    await waitFor(() => {
      expect(screen.getByRole('dialog')).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the create-role editor has no violations in %s mode', async (mode) => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(<RolesView repository={stubRepository()} />, {
      mode,
    });
    await user.click(await screen.findByRole('button', { name: 'New custom role' }));
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'New custom role' })).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the roles error state has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <RolesView repository={stubRepository({ roles: () => Promise.reject(new Error('boom')) })} />,
      { mode },
    );
    await waitFor(() => {
      expect(screen.getByText('Roles could not be loaded')).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });
});
