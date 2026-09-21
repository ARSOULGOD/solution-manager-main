import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { CASES } from './cases.js';
import { project, hashInput } from './project.js';
import { fixturePath, loadPackage, type FixtureFile } from './fixtures.js';
import type { ObservedState, SolutionPackage } from './types.js';

/**
 * The committed fixtures are the contract. This suite asserts the TypeScript engine still
 * matches them; the Java engine must pass the same files (UI-D15, FE-D12, DM-D20).
 */
describe('project() matches the committed fixtures', () => {
  for (const c of CASES) {
    it(c.name, () => {
      const f = JSON.parse(readFileSync(fixturePath(c.name), 'utf8')) as FixtureFile;
      const got = project(loadPackage(c.pkg), c.selection, c.draft, c.observed, c.catalog ?? {});
      expect(got).toEqual(f.expected);
    });
  }
});

describe('multi-version projection', () => {
  const pkg: SolutionPackage = {
    solution: 'versioned-example',
    packageVersion: [1, 2],
    appliesTo: '>=1.0 <2.0',
    components: [
      {
        type: 'worker',
        cardinality: 'exactly-one',
        configPath: 'worker',
        version: [1, 2],
        fields: [
          { key: 'COMMON', type: 'string' },
          { key: 'V1_ONLY', type: 'string', version: [1] },
          { key: 'V2_ONLY', type: 'string', version: [2] },
        ],
      },
    ],
  };
  const observed: ObservedState = { version: { state: 'OK' }, replicas: [] };
  const selection = { instances: [{ instanceKey: 'worker', type: 'worker' }] };

  it.each([
    [1, ['COMMON', 'V1_ONLY']],
    [2, ['COMMON', 'V2_ONLY']],
  ] as const)('projects only fields belonging to package version %s', (version, expected) => {
    const vm = project(pkg, selection, { worker: {} }, observed, {}, version);

    expect(vm.packageVersion).toBe(version);
    expect(vm.components[0]?.sections.flatMap((section) => section.fields.map((field) => field.key)))
      .toEqual(expected);
  });

  it('fails closed when the requested package version is not declared', () => {
    const vm = project(pkg, selection, { worker: {} }, observed, {}, 3);

    expect(vm.components).toEqual([]);
    expect(vm.diagnostics).toEqual([
      expect.objectContaining({ code: 'PACKAGE_VERSION_NOT_DECLARED' }),
    ]);
  });
});

/** Properties that must hold for EVERY case, not just the one that pins each. */
describe('invariants across every fixture', () => {
  const all = CASES.map((c) => ({ c, vm: project(loadPackage(c.pkg), c.selection, c.draft, c.observed, c.catalog ?? {}) }));

  it('no virtual field ever reaches the rendered set (UI-D24)', () => {
    // Compares by (instanceKey, key), NOT by renderedPath: a virtual field HAS no
    // renderedPath (it is null), so matching paths against keys could never collide for a
    // package with a non-empty configPath and the assertion was near-vacuous.
    let virtualSeen = 0;
    for (const { c, vm } of all) {
      const renderedKeys = new Set(vm.rendered.map((r) => `${r.instanceKey}\u0000${r.renderedPath}`));
      for (const comp of vm.components) {
        for (const sec of comp.sections) {
          for (const f of sec.fields) {
            if (!f.virtual) continue;
            virtualSeen++;
            expect(f.renderedPath, `${c.name}: virtual ${f.key} must have no path`).toBeNull();
            // and no rendered entry may correspond to it under any path spelling
            for (const suffix of [f.key, `edge.${f.key}`, `cache-engine.${f.key}`]) {
              expect(renderedKeys.has(`${c.selection.instances[0]?.instanceKey ?? ''}\u0000${suffix}`),
                `${c.name}: ${f.key} leaked`).toBe(false);
            }
          }
        }
      }
    }
    // the assertion is only meaningful if some case actually has virtual fields
    expect(virtualSeen, 'no fixture exercises virtual fields').toBeGreaterThan(0);
  });

  it('no hidden field ever reaches the rendered set (UI-D4)', () => {
    for (const { c, vm } of all) {
      const hidden = new Set(
        vm.components.flatMap((k) => k.sections.flatMap((s) => s.fields.filter((f) => !f.visible).map((f) => f.renderedPath))),
      );
      for (const r of vm.rendered) expect(hidden.has(r.renderedPath), `${c.name}: ${r.renderedPath}`).toBe(false);
    }
  });

  it('no resolved secret value appears anywhere in the view model (I6, FE-D23)', () => {
    for (const { c, vm } of all) {
      for (const k of vm.components) {
        for (const s of k.sections) {
          for (const f of s.fields) {
            if (!f.masked) continue;
            expect(f.value.kind, `${c.name}: ${f.key}`).not.toBe('value');
          }
        }
      }
      // and the rendered payload must not carry one either
      const maskedPaths = new Set(
        vm.components.flatMap((k) => k.sections.flatMap((s) => s.fields.filter((f) => f.masked).map((f) => f.renderedPath))),
      );
      for (const r of vm.rendered) {
        if (maskedPaths.has(r.renderedPath)) {
          expect(String(r.value), `${c.name}: ${r.renderedPath} must be a reference, not a value`).toMatch(/^secretRef:/);
        }
      }
    }
  });

  it('never emits a transform, resolution, mark or gap (DM-D11, DM-D16, DM-D23)', () => {
    for (const { c, vm } of all) {
      const blob = JSON.stringify(vm);
      for (const forbidden of ['"transform"', '"resolution"', '"mark"', '"gaps"', '"thresholds"']) {
        expect(blob.includes(forbidden), `${c.name} leaked ${forbidden}`).toBe(false);
      }
    }
  });

  it('never emits RBAC or auth material (FE-D22)', () => {
    // Exact key names, not substrings: ComponentView.role is component topology
    // (source/sink), not an RBAC role, and a substring match flags it wrongly.
    const FORBIDDEN = new Set([
      'permissions', 'permission', 'roles', 'scopes', 'scope',
      'token', 'authz', 'canEdit', 'canView', 'allowed', 'principal', 'organizationId',
    ]);
    const walk = (node: unknown, path: string, hits: string[]): void => {
      if (Array.isArray(node)) { node.forEach((n, i) => walk(n, `${path}[${i}]`, hits)); return; }
      if (node === null || typeof node !== 'object') return;
      for (const [k, v] of Object.entries(node)) {
        if (FORBIDDEN.has(k)) hits.push(`${path}.${k}`);
        walk(v, `${path}.${k}`, hits);
      }
    };
    for (const { c, vm } of all) {
      const hits: string[] = [];
      walk(vm, '$', hits);
      expect(hits, `${c.name} leaked RBAC keys`).toEqual([]);
    }
  });

  it('rendered is ordered by CODE POINT, which is what String.compareTo does (FE-D17)', () => {
    // Deliberately an independent ordinal comparator, not the engine's. Re-sorting with the
    // engine's own comparator only proved the sort ran; it could not detect that the ordering
    // was locale-dependent and therefore unreachable from Java.
    const ordinal = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0);
    for (const { c, vm } of all) {
      const sorted = [...vm.rendered].sort(
        (a, b) => ordinal(a.instanceKey, b.instanceKey) || ordinal(a.renderedPath, b.renderedPath),
      );
      expect(vm.rendered, c.name).toEqual(sorted);
    }
  });

  it('no ordering anywhere depends on locale collation', () => {
    // Any pair that localeCompare and code-point order differently would make the fixtures
    // demand ICU collation from the Java implementation.
    for (const { c, vm } of all) {
      // instanceKey is the primary sort key, so paths are only ordered WITHIN an instance.
      const byInstance = new Map<string, string[]>();
      for (const r of vm.rendered) {
        const list = byInstance.get(r.instanceKey) ?? [];
        list.push(r.renderedPath);
        byInstance.set(r.instanceKey, list);
      }
      for (const [inst, paths] of byInstance) {
        for (let i = 1; i < paths.length; i++) {
          const a = paths[i - 1]!;
          const b = paths[i]!;
          const ord = a < b ? -1 : a > b ? 1 : 0;
          expect(ord, `${c.name}/${inst}: ${a} vs ${b} is not in code-point order`).toBeLessThanOrEqual(0);
        }
      }
      // and instanceKeys themselves must be ordinal-ordered
      const keys = [...new Set(vm.rendered.map((r) => r.instanceKey))];
      expect(keys, `${c.name}: instanceKeys not in code-point order`)
        .toEqual([...keys].sort((x, y) => (x < y ? -1 : x > y ? 1 : 0)));
    }
  });

  it('a literal secret is refused, never rendered (I6)', () => {
    const cse = all.find((x) => x.c.name === 'secret-literal-is-refused-and-never-rendered');
    expect(cse, 'the adversarial secret case must exist').toBeTruthy();
    const f = cse!.vm.components
      .flatMap((k) => k.sections.flatMap((s) => s.fields))
      .find((x) => x.key === 'solace.password');
    expect(f?.value.kind).toBe('secretUnset');
    expect(f?.validations.some((v) => v.code === 'SECRET_NOT_A_REFERENCE' && v.severity === 'block')).toBe(true);
    expect(cse!.vm.rendered.some((r) => r.renderedPath.endsWith('solace.password'))).toBe(false);
  });

  it('an object-valued field hashes independently of key insertion order (FE-D17)', () => {
    const cse = all.find((x) => x.c.name === 'edge-object-value-hashes-canonically');
    expect(cse).toBeTruthy();
    const pkg = loadPackage('engine-edge');
    const reordered = project(pkg, cse!.c.selection, { e: { labels: { a: '1', b: '2' } } }, cse!.c.observed, {});
    expect(hashInput(reordered)).toBe(hashInput(cse!.vm));
  });

  it('section order follows declaration, not the alphabet', () => {
    const cse = all.find((x) => x.c.name === 'edge-section-order-follows-declaration-not-alphabet');
    expect(cse).toBeTruthy();
    const titles = cse!.vm.components[0]!.sections.map((s) => s.title);
    expect(titles[0], 'Zulu section is declared first and must render first').toBe('Zulu section');
  });

  it('layout coordinates never reach the rendered payload or the hash (UI-D20)', () => {
    const a = all.find((x) => x.c.name === 'fm-health-not-declared-is-not-applicable');
    const b = all.find((x) => x.c.name === 'fm-layout-excluded-from-hash');
    expect(a && b).toBeTruthy();
    // identical drafts, wildly different layout coordinates
    expect(hashInput(b!.vm)).toBe(hashInput(a!.vm));
  });

  it('a component declaring no health block never rolls up to RED (REQ-009, I1)', () => {
    for (const { c, vm } of all) {
      if (vm.status.healthAvailability === 'not-declared') {
        expect(vm.status.healthRollup, c.name).toBe('NOT_APPLICABLE');
      }
    }
  });
});
