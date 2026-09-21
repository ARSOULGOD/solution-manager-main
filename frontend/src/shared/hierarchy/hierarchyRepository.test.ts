/**
 * `HierarchyRepository` against the fixture handlers (task 134, `D20`, `FE-D53`).
 */
import { describe, expect, it } from 'vitest';

import { createCpClient } from '@shared/api';
import { ORGANIZATIONS } from '@shared/testdoubles/data/hierarchy';

import { createHierarchyRepository } from './hierarchyRepository';

const cpClient = createCpClient('/api');
const repo = createHierarchyRepository(cpClient);

describe('createHierarchyRepository (fixture-backed)', () => {
  it('loads every organization', async () => {
    expect(await repo.organizations()).toEqual(ORGANIZATIONS.map((org) => org.name));
  });

  it('loads the sub-orgs of one organization, not of all of them', async () => {
    expect(await repo.subOrgs('Acme Financial')).toEqual([
      'Payments',
      'Trading',
      'Risk',
      'Onboarding',
      'Logistics',
      'Sandbox',
    ]);
  });

  it('loads the environments of one sub-org', async () => {
    expect(await repo.environments('Acme Financial', 'Payments')).toEqual(['PROD', 'TEST']);
    expect(await repo.environments('Acme Financial', 'Logistics')).toEqual(['PROD']);
  });

  it('walks the whole cascade in order', async () => {
    const [org] = await repo.organizations();
    const [subOrg] = await repo.subOrgs(org as string);
    const environments = await repo.environments(org as string, subOrg as string);

    expect(environments.length).toBeGreaterThan(0);
  });

  // A stale selection under a reloaded catalog is a normal state, not a
  // fault — the cascade must show "none" rather than an error boundary.
  it('resolves empty for an unknown organization or sub-org', async () => {
    expect(await repo.subOrgs('no-such-org')).toEqual([]);
    expect(await repo.environments('Acme Financial', 'no-such-sub-org')).toEqual([]);
  });

  // Organization names are free-form display strings; a raw interpolation
  // would address the wrong resource for anything needing escaping.
  it('escapes path segments', async () => {
    expect(await repo.subOrgs('a/b')).toEqual([]);
    expect(await repo.environments('EU West', 'x y')).toEqual([]);
  });

  it('propagates an abort', async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(repo.organizations(controller.signal)).rejects.toThrow();
  });
});
