/**
 * Pins the package DIALECT boundary (task FE-149, design §14.1).
 *
 * Bundled packages are authored canonically — the shape
 * `contracts/solution-package.schema.json` defines and `validate-package.py`
 * gates. The projection engine reads a different, hybrid shape: `label`,
 * `helperText` and `advanced` flat on the field, but `section`, `order` and
 * `readOnly` nested under `ui`. `toFrontendPackage()` is the only thing that
 * converts between them, and before this task the bundled path cast straight
 * through — so `file-mesh`'s authored `ui.label` was silently ignored and
 * every field rendered with its raw key as its label.
 */
import { describe, expect, it } from 'vitest';

import { connectionsForInstance, DEV_CONNECTIONS } from './connections';
import { FILE_MESH_INSTANCES } from './instances';
import { connectionSelectorValues, draftValuesFor, packageForSolution } from './packages';

describe('packageForSolution', () => {
  it('exposes authored labels on the engine dialect, not the canonical nesting', () => {
    const pkg = packageForSolution('cache');
    const field = pkg?.components[0]?.fields.find((f) => f.key === 'max-size');
    expect(field?.label).toBe('Max entries');
  });

  it('normalizes every bundled solution, so no package depends on being pre-flattened', () => {
    for (const id of ['cache', 'file-mesh']) {
      const pkg = packageForSolution(id);
      expect(pkg).toBeDefined();
      for (const component of pkg!.components) {
        for (const field of component.fields) {
          // `ui` may carry section/order/readOnly, but never the three the
          // engine reads flat — that combination is the dialect bug.
          expect(field.ui).not.toHaveProperty('label');
          expect(field.ui).not.toHaveProperty('advanced');
          expect(field.ui).not.toHaveProperty('helperText');
        }
      }
    }
  });
});

describe('connectionSelectorValues (UI-D27)', () => {
  it('nests a declared connection selection under the component that declares the selector field', () => {
    const pkg = packageForSolution('cache')!;
    expect(connectionSelectorValues(pkg, { solaceBroker: 'conn-7' })).toEqual({
      'cache-engine': { solaceBroker: 'conn-7' },
    });
  });

  it('is generic — it never names "solaceBroker", "connection" or any solution', () => {
    const pkg = packageForSolution('cache')!;
    // A selector key the package does not declare contributes nothing —
    // proving this reads the package's OWN fields, not a hard-coded key.
    expect(connectionSelectorValues(pkg, { notARealSelector: 'conn-1' })).toEqual({});
  });

  it('returns an empty object when no connection was selected', () => {
    const pkg = packageForSolution('cache')!;
    expect(connectionSelectorValues(pkg, {})).toEqual({});
  });
});

describe('File Mesh fixture instances: broker selection (UI-D27)', () => {
  it.each(FILE_MESH_INSTANCES.map((instance) => [instance.id, instance] as const))(
    '%s selects an active Solace broker of its own scope, seeded into the solace component',
    (_id, instance) => {
      const selected = Object.values(instance.connections ?? {});
      expect(selected.length).toBeGreaterThan(0);
      const inScope = connectionsForInstance(
        DEV_CONNECTIONS.filter((row) => row.status === 'active'),
        { org: instance.org, subOrg: instance.subOrg, env: instance.env },
      );
      for (const id of selected) {
        const row = inScope.find((candidate) => candidate.id === id);
        expect(row?.connectionType, `${id} is an active in-scope broker`).toBe('solace-broker');
        expect(instance.appliedConnectionRevisions?.[id]).toBe(row?.revision);
      }
      expect(draftValuesFor(instance)['solace']).toEqual(instance.connections);
    },
  );
});
