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
import type { AdminRepository, EffectiveGrant } from '../adminRepository';
import { stubAdminRepository } from '../adminRepository.testdouble';

import { AccessCheckView } from './AccessCheckView';

const SCOPE_TREE: ScopeNode = {
  name: 'All organizations',
  level: 'All',
  children: [{ name: 'Payments', level: 'Organization' }],
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
    id: 'u-sam',
    kind: 'user',
    name: 'Sam Okoye',
    sub: 'sam@acme.example',
    source: 'local',
    status: 'active',
  },
];

const ROLES: RbacRole[] = [
  {
    id: 'org-op',
    name: 'Org Operator',
    kind: 'built-in',
    description: 'Operate instances.',
    permissionIds: ['instance.view', 'config.apply', 'instance.restart'],
  },
];

const ASSIGNMENTS: RbacAssignment[] = [
  {
    id: 'a1',
    principalId: 'u-mira',
    roleId: 'org-op',
    scope: [],
    status: 'active',
    assignedBy: 'Devon Ramirez',
    assignedAt: '2026-02-14T09:30:00Z',
  },
];

function computeGrants(principalId: string, scope: readonly string[]): EffectiveGrant[] {
  const reaches = (g: readonly string[]): boolean =>
    g.length <= scope.length && g.every((seg, i) => seg === scope[i]);
  const same = (a: readonly string[], b: readonly string[]): boolean =>
    a.length === b.length && a.every((seg, i) => seg === b[i]);
  return ASSIGNMENTS.filter((a) => a.principalId === principalId && reaches(a.scope)).flatMap(
    (a) => {
      const role = ROLES.find((r) => r.id === a.roleId);
      return (role?.permissionIds === '*' ? [] : (role?.permissionIds ?? [])).map(
        (permissionId) => ({
          permissionId,
          roleId: a.roleId,
          grantType: same(a.scope, scope) ? ('direct' as const) : ('inherited' as const),
          grantingScope: a.scope,
          via: { kind: 'direct' as const },
        }),
      );
    },
  );
}

function stubRepository(overrides: Partial<AdminRepository> = {}): AdminRepository {
  return stubAdminRepository({
    roles: () => Promise.resolve(ROLES),
    principals: () => Promise.resolve(PRINCIPALS),
    scopeTree: () => Promise.resolve(SCOPE_TREE),
    effectivePermissions: (principalId, scope) =>
      Promise.resolve(computeGrants(principalId, scope)),
    ...overrides,
  });
}

const modes = ['light', 'dark'] as const;

describe('AccessCheckView a11y', () => {
  it.each(modes)('the granted view has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(<AccessCheckView repository={stubRepository()} />, {
      mode,
    });
    await waitFor(() => {
      expect(screen.getByRole('row', { name: /instance\.view/ })).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the deny-by-default state has no violations in %s mode', async (mode) => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(<AccessCheckView repository={stubRepository()} />, {
      mode,
    });
    await user.click(await screen.findByLabelText('Principal'));
    await user.click(await screen.findByRole('option', { name: 'Sam Okoye · user' }));
    await waitFor(() => {
      expect(screen.getByText('No permissions here')).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the error state has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <AccessCheckView
        repository={stubRepository({
          effectivePermissions: () => Promise.reject(new Error('boom')),
        })}
      />,
      { mode },
    );
    await waitFor(() => {
      expect(screen.getByText('Effective permissions could not be computed')).not.toBeNull();
    });
    expect(await axe(container)).toHaveNoViolations();
  });
});
