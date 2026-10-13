import { describe, expect, it } from 'vitest';

import { CONNECTIONS, DESCRIPTORS, DEV_CONNECTIONS, toCatalogEntries } from './connections';
import { ORGANIZATIONS } from './hierarchy';

describe('connection test doubles', () => {
  it('holds no secret VALUE anywhere (I6)', () => {
    const serialized = JSON.stringify(CONNECTIONS);
    expect(serialized).not.toMatch(/hunter2|BEGIN (RSA )?PRIVATE KEY/);
    for (const row of CONNECTIONS) {
      for (const status of Object.values(row.secretStatus)) {
        expect(['set', 'unset']).toContain(status);
      }
    }
  });

  it('reports secret status only for attributes the descriptor marks secret', () => {
    for (const row of CONNECTIONS) {
      const descriptor = DESCRIPTORS[row.connectionType];
      const secretKeys = new Set(
        descriptor.attributes.filter((a) => a.secret === true).map((a) => a.key),
      );
      for (const key of Object.keys(row.secretStatus)) {
        expect(secretKeys.has(key)).toBe(true);
      }
      // and the converse: a secret attribute never leaks into `attributes`
      for (const key of secretKeys) {
        expect(row.attributes).not.toHaveProperty(key);
      }
    }
  });

  it('projects to catalog entries carrying the revision UI-D29 needs', () => {
    const entries = toCatalogEntries(CONNECTIONS);
    const prod = entries.find((e) => e.id === 'conn-7');
    expect(prod).toMatchObject({ label: 'prod-broker-01', revision: 4 });
    expect(prod?.attributes).toMatchObject({ vpn: 'payments-prod' });
  });

  it('offers at least one inactive connection so the filter has something to exclude', () => {
    expect(CONNECTIONS.some((c) => c.status === 'inactive')).toBe(true);
  });

  it('derives secretAttributes from the descriptor, never the row (I6)', () => {
    const entries = toCatalogEntries(CONNECTIONS);
    const prod = entries.find((e) => e.id === 'conn-7');
    expect(prod?.secretAttributes).toEqual(['password']);
    for (const entry of entries) {
      expect(entry.secretAttributes).toBeDefined();
      expect(entry.secretAttributes?.length).toBeGreaterThan(0);
    }
  });
});

describe('connection fixtures vs the hierarchy fixture (the Declare-instance broker dropdown)', () => {
  it('scopes every active connection by names that exist in the hierarchy, spelled exactly as the cascade spells them', () => {
    for (const row of DEV_CONNECTIONS) {
      if (row.status === 'inactive') continue; // the retired broker's scope may no longer exist
      const org = ORGANIZATIONS.find((o) => o.name === row.scope.org);
      expect(org, `${row.name}: org ${row.scope.org}`).toBeDefined();
      const sub = org?.subOrgs.find((s) => s.name === row.scope.subOrg);
      expect(sub, `${row.name}: sub-org ${row.scope.subOrg}`).toBeDefined();
      expect(sub?.environments, `${row.name}: env ${row.scope.env}`).toContain(row.scope.env);
    }
  });

  it('offers an active Solace broker for EVERY org / sub-org / environment an operator can pick', () => {
    for (const org of ORGANIZATIONS) {
      for (const sub of org.subOrgs) {
        for (const env of sub.environments) {
          const offered = DEV_CONNECTIONS.filter(
            (row) =>
              row.connectionType === 'solace-broker' &&
              row.status === 'active' &&
              row.scope.org === org.name &&
              row.scope.subOrg === sub.name &&
              row.scope.env === env,
          );
          expect(offered.length, `${org.name} / ${sub.name} / ${env}`).toBeGreaterThan(0);
        }
      }
    }
  });

  it('keeps CONNECTIONS (which component tests pin by count) free of the generated brokers', () => {
    expect(CONNECTIONS).toHaveLength(5);
    expect(DEV_CONNECTIONS.length).toBeGreaterThan(CONNECTIONS.length);
    expect(new Set(DEV_CONNECTIONS.map((r) => r.id)).size).toBe(DEV_CONNECTIONS.length);
    expect(new Set(DEV_CONNECTIONS.map((r) => r.name)).size).toBe(DEV_CONNECTIONS.length);
  });
});
