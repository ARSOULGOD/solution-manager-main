/**
 * The hierarchy cascade against the REAL MSW handlers (no fake client) — the static-testing path.
 *
 * `hierarchyRepository.test.ts` drives the repository with a hand-built client, so it kept passing
 * after plan 001 task 7 changed the repository to expect the server's `ScopeNodeList` envelope
 * while the MSW handlers still returned bare name arrays: in no-backend mode every cascade load
 * threw `list.items.forEach is not a function`. This test closes that gap.
 */
import { describe, expect, it } from 'vitest';

import { createCpClient } from '@shared/api';
import { ORGANIZATIONS } from '@shared/testdoubles/data/hierarchy';

import { createHierarchyRepository } from './hierarchyRepository';

describe('createHierarchyRepository (fixture-backed, real MSW handlers)', () => {
  const repo = () => createHierarchyRepository(createCpClient('/api'));

  it("loads every organization, then each one's sub-organizations, then their environments", async () => {
    const r = repo();
    const orgs = await r.organizations();
    expect(orgs).toEqual(ORGANIZATIONS.map((o) => o.name));

    for (const org of ORGANIZATIONS) {
      const subOrgs = await r.subOrgs(org.name);
      expect(subOrgs).toEqual(org.subOrgs.map((s) => s.name));
      for (const sub of org.subOrgs) {
        expect(await r.environments(org.name, sub.name)).toEqual(sub.environments);
      }
    }
  });

  it('offers more than one organization and more than one sub-organization, for cascade testing', async () => {
    const r = repo();
    const orgs = await r.organizations();
    expect(orgs.length).toBeGreaterThanOrEqual(3);
    const multi = await Promise.all(orgs.map((o) => r.subOrgs(o)));
    expect(multi.filter((s) => s.length >= 2).length).toBeGreaterThanOrEqual(3);
  });
});
