import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { LocalUser, ScopeNode } from '@shared/testdoubles/data/rbac';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { AdminRepository } from '../adminRepository';
import { stubAdminRepository } from '../adminRepository.testdouble';

import { UsersView } from './UsersView';

const SCOPE_TREE: ScopeNode = {
  name: 'All organizations',
  level: 'All',
  children: [
    { name: 'Acme Financial', level: 'Organization' },
    { name: 'Globex', level: 'Organization' },
  ],
};

const USERS: LocalUser[] = [
  {
    id: 'u-devon',
    name: 'Devon Ramirez',
    email: 'devon@acme.example',
    status: 'active',
    lastSignIn: '2m ago',
    createdBy: 'installer bootstrap',
    organization: 'Acme Financial',
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

function stubRepository(): AdminRepository {
  return stubAdminRepository({
    users: () => Promise.resolve(USERS),
    setUserActive: () => Promise.resolve(),
    scopeTree: () => Promise.resolve(SCOPE_TREE),
  });
}

const modes = ['light', 'dark'] as const;

describe('UsersView a11y', () => {
  it.each(modes)('the user list has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(<UsersView repository={stubRepository()} />, {
      mode,
    });
    await waitFor(() => {
      expect(screen.getByRole('row', { name: /Devon Ramirez/ })).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the Add user dialog has no violations in %s mode', async (mode) => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(<UsersView repository={stubRepository()} />, {
      mode,
    });
    await user.click(await screen.findByRole('button', { name: 'Add user' }));
    await waitFor(() => {
      expect(screen.getByRole('dialog', { name: 'Add a user' })).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });
});
