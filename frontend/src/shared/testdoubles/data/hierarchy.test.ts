/**
 * The anti-drift assertions for the org hierarchy (`D20`, `FE-D53`).
 *
 * `ORGANIZATIONS` is the SINGLE source of truth for `Organization →
 * Sub-organization → Environment`. Before this module the hierarchy was asserted
 * twice and the two disagreed — `SCOPE_TREE` knew two organizations while
 * the instance rows carried six. The last test below is the one that keeps
 * that from happening again.
 */
import { describe, expect, it } from 'vitest';

import {
  ORGANIZATIONS,
  environmentNodesOf,
  environmentsOf,
  nodeId,
  organizationNames,
  organizationNodes,
  subOrgNamesOf,
  subOrgNodesOf,
} from './hierarchy';
import { CACHE_INSTANCES } from './instances';

describe('ORGANIZATIONS', () => {
  it('is a strict three-level hierarchy — every org has a sub-org, every sub-org an environment', () => {
    expect(ORGANIZATIONS.length).toBeGreaterThan(0);
    for (const org of ORGANIZATIONS) {
      expect(org.name.trim()).not.toBe('');
      expect(org.subOrgs.length).toBeGreaterThan(0);
      for (const subOrg of org.subOrgs) {
        // The em-dash placeholder the Sandbox row used to carry is exactly
        // what a mandatory cascade has no room for.
        expect(subOrg.name.trim()).not.toBe('');
        expect(subOrg.name).not.toBe('—');
        expect(subOrg.environments.length).toBeGreaterThan(0);
      }
    }
  });

  it('has Acme Financial as the organization and the former organizations as sub-organizations', () => {
    expect(organizationNames()).toEqual(['Acme Financial', 'Globex', 'Initech', 'Umbrella Health']);
    expect(subOrgNamesOf('Acme Financial')).toEqual([
      'Payments',
      'Trading',
      'Risk',
      'Onboarding',
      'Logistics',
      'Sandbox',
    ]);
  });

  it('contains neither a Tenant level nor the removed former Sub-org values', () => {
    expect(JSON.stringify(ORGANIZATIONS).toLowerCase()).not.toContain('tenant');
    expect(JSON.stringify(ORGANIZATIONS)).not.toMatch(/Ledger|Quotes|Batch|KYC|EU-West|Default/);
  });

  it('has unique org names, and unique sub-org names within an org', () => {
    expect(new Set(organizationNames()).size).toBe(ORGANIZATIONS.length);
    for (const org of ORGANIZATIONS) {
      const names = subOrgNamesOf(org.name);
      expect(new Set(names).size).toBe(names.length);
    }
  });
});

describe('lookup helpers', () => {
  it('walks org → sub-org → environment', () => {
    const org = organizationNames()[0] as string;
    const subOrg = subOrgNamesOf(org)[0] as string;
    expect(environmentsOf(org, subOrg).length).toBeGreaterThan(0);
  });

  it('returns empty for an unknown org or sub-org rather than throwing', () => {
    expect(subOrgNamesOf('no-such-org')).toEqual([]);
    expect(environmentsOf('no-such-org', 'nope')).toEqual([]);
    expect(environmentsOf(organizationNames()[0] as string, 'no-such-sub-org')).toEqual([]);
  });
});

describe('drift against the instance fixture', () => {
  // The whole reason this module exists: every triple an instance row
  // claims must be selectable in the cascade, or the grid shows instances
  // whose organization the declare dialog cannot offer.
  it('resolves every org / sub-org / env triple the instance rows use', () => {
    for (const row of CACHE_INSTANCES) {
      expect(organizationNames(), `org ${row.org} (${row.id})`).toContain(row.org);
      expect(subOrgNamesOf(row.org), `sub-org ${row.subOrg} (${row.id})`).toContain(row.subOrg);
      expect(environmentsOf(row.org, row.subOrg), `env ${row.env} (${row.id})`).toContain(row.env);
    }
  });
});

describe("wire-shaped, id-bearing views (the MSW handlers' source)", () => {
  it('gives every node in the tree a unique, UUID-shaped, deterministic id', () => {
    const ids: string[] = [];
    for (const org of organizationNodes()) {
      ids.push(org.id);
      for (const sub of subOrgNodesOf(org.id)) {
        ids.push(sub.id);
        for (const env of environmentNodesOf(org.id, sub.id)) ids.push(env.id);
      }
    }
    expect(new Set(ids).size).toBe(ids.length);
    for (const id of ids) {
      expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-8[0-9a-f]{3}-[0-9a-f]{12}$/);
    }
    expect(nodeId('Acme Financial')).toBe(nodeId('Acme Financial'));
  });

  it('serves the same names as the name-based helpers, and [] for an unknown id', () => {
    const [first] = organizationNodes();
    expect(subOrgNodesOf(first?.id ?? '').map((n) => n.name)).toEqual(
      subOrgNamesOf(first?.name ?? ''),
    );
    expect(subOrgNodesOf('no-such-id')).toEqual([]);
    expect(environmentNodesOf('no-such-id', 'x')).toEqual([]);
  });
});
