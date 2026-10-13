import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type {
  RbacAssignment,
  RbacPrincipal,
  RbacRole,
  ScopeNode,
} from '@shared/testdoubles/data/rbac';

import { renderWithProviders, screen, within } from '../../../test/renderWithProviders';
import type { AdminRepository, EffectiveGrant } from '../adminRepository';
import { stubAdminRepository } from '../adminRepository.testdouble';

import { AccessCheckView } from './AccessCheckView';

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
  {
    id: 'g-sre',
    kind: 'group',
    name: 'platform-sre',
    sub: 'IdP group',
    source: 'idp',
    status: 'active',
    organization: 'Acme Financial',
  },
];

const ORG_OP: RbacRole = {
  id: 'org-op',
  name: 'Org Operator',
  kind: 'built-in',
  description: 'Operate instances.',
  permissionIds: ['instance.view', 'config.view', 'config.apply', 'instance.restart'],
};

const ROLES: RbacRole[] = [ORG_OP];

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
  {
    id: 'a2',
    principalId: 'u-sam',
    roleId: 'org-op',
    scope: ['Acme Financial', 'Payments'],
    status: 'active',
    assignedBy: 'Devon Ramirez',
    assignedAt: '2026-02-14T09:30:00Z',
  },
];

function computeGrants(principalId: string, scope: readonly string[]): EffectiveGrant[] {
  const reaches = (grantScope: readonly string[]): boolean =>
    grantScope.length <= scope.length && grantScope.every((seg, i) => seg === scope[i]);
  const same = (a: readonly string[], b: readonly string[]): boolean =>
    a.length === b.length && a.every((seg, i) => seg === b[i]);
  return ASSIGNMENTS.filter((a) => a.principalId === principalId && reaches(a.scope)).flatMap(
    (a) => {
      const role = ROLES.find((r) => r.id === a.roleId);
      const ids = role?.permissionIds === '*' ? [] : (role?.permissionIds ?? []);
      return ids.map((permissionId) => ({
        permissionId,
        roleId: a.roleId,
        grantType: same(a.scope, scope) ? ('direct' as const) : ('inherited' as const),
        grantingScope: a.scope,
        via: { kind: 'direct' as const },
      }));
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

describe('AccessCheckView', () => {
  it('names the conferring role for each granted permission and tags a same-scope grant direct', async () => {
    renderWithProviders(<AccessCheckView repository={stubRepository()} />);

    // Default: first principal (Mira) at the global scope — her org-op grant is made there.
    const row = await screen.findByRole('row', { name: /instance\.view/ });
    // The role and the direct/inherited tag appear in the "Conferred by"
    // summary AND in the per-source breakdown next to it.
    expect(within(row).getAllByText('Org Operator').length).toBeGreaterThan(0);
    expect(within(row).getAllByText('direct').length).toBeGreaterThan(0);
    expect(within(row).getAllByText('Direct assignment').length).toBeGreaterThan(0);
    // A high-consequence verb in the set is marked as such.
    expect(screen.getAllByText('high-consequence').length).toBeGreaterThan(0);
  });

  it('shows a grant made above the selected scope as inherited, with the scope it was granted at', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AccessCheckView repository={stubRepository()} />);

    await user.click(await screen.findByLabelText('Principal'));
    await user.click(await screen.findByRole('option', { name: 'Sam Okoye · user' }));
    await user.click(await screen.findByRole('button', { name: 'PROD' }));

    const row = await screen.findByRole('row', { name: /config\.apply/ });
    expect(within(row).getAllByText('inherited').length).toBeGreaterThan(0);
    expect(within(row).getByText('at Acme Financial / Payments')).not.toBeNull();
    // §8: the level a grant was inherited FROM is named, not only its path.
    expect(within(row).getByText(/Inherited from Sub-organization/)).not.toBeNull();
  });

  it('states deny-by-default when no assignment reaches the scope', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AccessCheckView repository={stubRepository()} />);

    // Sam has a grant at Acme Financial / Payments, none at the global scope.
    await user.click(await screen.findByLabelText('Principal'));
    await user.click(await screen.findByRole('option', { name: 'Sam Okoye · user' }));

    expect(await screen.findByText('No permissions here')).not.toBeNull();
    expect(screen.getByText(/Deny-by-default/)).not.toBeNull();
    expect(
      screen.getByText(/No direct assignment and no group this principal belongs to/),
    ).not.toBeNull();
  });

  it('explains denial even when permissions are granted, with a concrete example', async () => {
    renderWithProviders(<AccessCheckView repository={stubRepository()} />);

    expect(await screen.findByText(/A permission that isn.t granted is denied/)).not.toBeNull();
    expect(screen.getByText(/^Example:/)).not.toBeNull();
  });

  it('recovers from an effective-permissions failure via Retry', async () => {
    const user = userEvent.setup();
    const effectivePermissions = vi
      .fn<AdminRepository['effectivePermissions']>()
      .mockRejectedValueOnce(new Error('boom'))
      .mockImplementation((principalId, scope) =>
        Promise.resolve(computeGrants(principalId, scope)),
      );
    renderWithProviders(<AccessCheckView repository={stubRepository({ effectivePermissions })} />);

    expect(await screen.findByText('Effective permissions could not be computed')).not.toBeNull();
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('row', { name: /instance\.view/ })).not.toBeNull();
  });
});

/**
 * §8: a user's effective permissions are the deduplicated union of their
 * own assignments, their groups' assignments, and whatever a parent scope
 * confers. The screen must name WHICH of those conferred each permission,
 * because "remove the assignment and access changes" is only true if you
 * removed the right one.
 */
describe('AccessCheckView — where a grant came from', () => {
  const GROUP_GRANTS: EffectiveGrant[] = [
    {
      permissionId: 'instance.view',
      roleId: 'org-op',
      grantType: 'direct',
      grantingScope: ['Payments'],
      via: { kind: 'direct' },
    },
    {
      permissionId: 'instance.view',
      roleId: 'org-op',
      grantType: 'inherited',
      grantingScope: [],
      via: { kind: 'group', groupId: 'grp-payments-ops', groupName: 'Payments Operations' },
    },
    {
      permissionId: 'config.apply',
      roleId: 'org-op',
      grantType: 'inherited',
      grantingScope: [],
      via: { kind: 'group', groupId: 'grp-payments-ops', groupName: 'Payments Operations' },
    },
  ];

  function groupStub(): AdminRepository {
    return stubRepository({ effectivePermissions: () => Promise.resolve(GROUP_GRANTS) });
  }

  it('names the group a permission arrived through, alongside the direct source', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AccessCheckView repository={groupStub()} />);
    await user.click(await screen.findByRole('button', { name: 'Payments' }));

    const row = await screen.findByRole('row', { name: /instance\.view/ });
    expect(within(row).getByText('Direct assignment')).not.toBeNull();
    expect(within(row).getByText('Group: Payments Operations')).not.toBeNull();
  });

  it('lists a permission held ONLY through a group as coming from that group', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AccessCheckView repository={groupStub()} />);
    await user.click(await screen.findByRole('button', { name: 'Payments' }));

    const row = await screen.findByRole('row', { name: /config\.apply/ });
    expect(within(row).getByText('Group: Payments Operations')).not.toBeNull();
    expect(within(row).queryByText('Direct assignment')).toBeNull();
    expect(within(row).getByText(/Inherited from All organizations/)).not.toBeNull();
  });

  it('collapses one permission to one row while keeping both of its sources', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AccessCheckView repository={groupStub()} />);
    await user.click(await screen.findByRole('button', { name: 'Payments' }));

    await screen.findByRole('row', { name: /instance\.view/ });
    expect(screen.getAllByRole('row', { name: /instance\.view/ })).toHaveLength(1);
  });

  it('says a deactivated principal holds nothing, and why', async () => {
    const user = userEvent.setup();
    const principals: RbacPrincipal[] = [
      ...PRINCIPALS,
      {
        id: 'u-priya',
        kind: 'user',
        name: 'Priya Nair',
        sub: 'priya@acme.example',
        source: 'local',
        status: 'deactivated',
      },
    ];
    renderWithProviders(
      <AccessCheckView
        repository={stubRepository({
          principals: () => Promise.resolve(principals),
          effectivePermissions: () => Promise.resolve([]),
        })}
      />,
    );

    await user.click(await screen.findByLabelText('Principal'));
    await user.click(await screen.findByRole('option', { name: 'Priya Nair · user' }));

    expect(await screen.findByText(/This principal is deactivated/)).not.toBeNull();
  });
});
