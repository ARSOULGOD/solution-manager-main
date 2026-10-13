import { describe, expect, it } from 'vitest';

import { createCpClient } from '@shared/api';

import { createAdminRepository } from './adminRepository';

const cpClient = createCpClient('/api');

describe('createAdminRepository (fixture-backed)', () => {
  it('resolves roles, principals, the scope tree and assignments', async () => {
    const repo = createAdminRepository(cpClient);

    expect(await repo.roles()).toHaveLength(5);
    // Principals are DERIVED from the users and groups tables, so this is
    // four local users plus five groups (three local, two IdP-sourced).
    expect(await repo.principals()).toHaveLength(9);
    // The synthetic root, not an organization (`D20`).
    const tree = await repo.scopeTree();
    expect(tree.level).toBe('All');
    expect(tree.children?.map((child) => child.name)).toContain('Acme Financial');
    expect(await repo.assignments()).toHaveLength(7);
  });

  it('createUser() echoes the draft as an active user with a correlation id', async () => {
    const repo = createAdminRepository(cpClient);

    const { user, correlationId } = await repo.createUser({
      name: 'Ada Lovelace',
      email: 'ada@acme.example',
      organization: 'Acme Financial',
      credentialPath: 'temp',
    });

    expect(user.name).toBe('Ada Lovelace');
    expect(user.email).toBe('ada@acme.example');
    expect(user.status).toBe('active');
    expect(user.organization).toBe('Acme Financial');
    expect(correlationId).not.toBe('');
  });

  it('groups(), members and assignments all resolve, with DERIVED counts', async () => {
    const repo = createAdminRepository(cpClient);

    const groups = await repo.groups();
    const paymentsOps = groups.find((group) => group.name === 'Payments Operations');
    expect(paymentsOps).toBeDefined();
    expect(paymentsOps?.organization).toBe('Acme Financial');
    // Counts come from the membership and assignment tables, never a
    // stored number that can drift from them.
    expect(paymentsOps?.memberCount).toBe(2);
    expect(paymentsOps?.assignmentCount).toBe(1);

    const members = await repo.groupMembers(paymentsOps?.id ?? '');
    expect(members.map((member) => member.userId).sort()).toEqual(['u-devon', 'u-mira']);

    const assignments = await repo.groupAssignments(paymentsOps?.id ?? '');
    expect(assignments).toHaveLength(1);
    expect(assignments[0]?.scope).toEqual(['Acme Financial', 'Payments']);
  });
});
