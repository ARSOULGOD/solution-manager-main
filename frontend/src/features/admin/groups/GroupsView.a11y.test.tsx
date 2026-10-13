import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { AdminRepository } from '../adminRepository';
import { stubAdminRepository } from '../adminRepository.testdouble';

import {
  ALL_GROUP_PERMISSIONS,
  GROUPS,
  GROUP_ASSIGNMENTS,
  MEMBERS,
  ROLES,
  SCOPE_TREE,
  USERS,
} from './groupsTestData';
import { GroupsView } from './GroupsView';

function repository(overrides: Partial<AdminRepository> = {}): AdminRepository {
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

function render(mode: 'light' | 'dark', overrides: Partial<AdminRepository> = {}) {
  return renderWithProviders(<GroupsView repository={repository(overrides)} />, {
    mode,
    permissions: [...ALL_GROUP_PERMISSIONS],
  });
}

const modes = ['light', 'dark'] as const;

describe('GroupsView a11y', () => {
  it.each(modes)('the group list has no violations in %s mode', async (mode) => {
    const { container } = render(mode);
    await waitFor(() => {
      expect(screen.getByText('Payments Operations')).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the Add group form has no violations in %s mode', async (mode) => {
    const user = userEvent.setup();
    const { container } = render(mode);
    await user.click(await screen.findByRole('button', { name: 'Add group' }));
    await waitFor(() => {
      expect(screen.getByRole('dialog', { name: 'Add a group' })).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the group details view has no violations in %s mode', async (mode) => {
    const user = userEvent.setup();
    const { container } = render(mode);
    await user.click(
      await screen.findByRole('button', { name: 'Actions for Payments Operations' }),
    );
    await user.click(await screen.findByRole('menuitem', { name: 'View' }));
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Members' })).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the no-results state has no violations in %s mode', async (mode) => {
    const user = userEvent.setup();
    const { container } = render(mode);
    await screen.findByText('Payments Operations');
    await user.type(screen.getByLabelText('Search groups'), 'zzzz-nothing');
    await waitFor(() => {
      expect(screen.getByText('No groups match these filters')).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the error state has no violations in %s mode', async (mode) => {
    const { container } = render(mode, {
      groups: () => Promise.reject(new Error('boom')),
    });
    await waitFor(() => {
      expect(screen.getByText('Groups could not be loaded')).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });
});
