import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type {
  RbacAssignment,
  RbacPrincipal,
  RbacRole,
  ScopeNode,
} from '@shared/testdoubles/data/rbac';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { AdminRepository } from '../adminRepository';
import { stubAdminRepository } from '../adminRepository.testdouble';

import { AssignmentsView } from './AssignmentsView';

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
          children: [{ name: 'PROD', level: 'Environment' }],
        },
      ],
    },
  ],
};

const ROLES: RbacRole[] = [
  {
    id: 'org-op',
    name: 'Org Operator',
    kind: 'built-in',
    description: 'Operate instances.',
    permissionIds: ['instance.view'],
  },
];

const ASSIGNMENTS: RbacAssignment[] = [
  {
    id: 'a2',
    principalId: 'u-mira',
    roleId: 'org-op',
    scope: ['Acme Financial'],
    status: 'active',
    assignedBy: 'Devon Ramirez',
    assignedAt: '2026-02-14T09:30:00Z',
  },
];

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
    id: 'g-pay-ops',
    kind: 'group',
    name: 'payments-operators',
    sub: 'IdP group',
    source: 'idp',
    status: 'active',
    organization: 'Acme Financial',
  },
];

function stubRepository(overrides: Partial<AdminRepository> = {}): AdminRepository {
  return stubAdminRepository({
    roles: () => Promise.resolve(ROLES),
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

const modes = ['light', 'dark'] as const;

describe('AssignmentsView a11y', () => {
  it.each(modes)('the by-principal table has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(<AssignmentsView repository={stubRepository()} />, {
      mode,
    });
    await waitFor(() => {
      expect(screen.getByText('Mira Chen')).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the by-scope view has no violations in %s mode', async (mode) => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(<AssignmentsView repository={stubRepository()} />, {
      mode,
    });
    await user.click(await screen.findByRole('button', { name: 'By scope' }));
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'PROD' })).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the New assignment dialog has no violations in %s mode', async (mode) => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(<AssignmentsView repository={stubRepository()} />, {
      mode,
    });
    await user.click(await screen.findByRole('button', { name: 'New assignment' }));
    await waitFor(() => {
      expect(screen.getByRole('dialog')).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the error state has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <AssignmentsView
        repository={stubRepository({ assignments: () => Promise.reject(new Error('boom')) })}
      />,
      { mode },
    );
    await waitFor(() => {
      expect(screen.getByText('Assignments could not be loaded')).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });
});
