import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { CASES } from './cases.js';
import { project, hashInput } from './project.js';
import { fixturePath, loadPackage, type FixtureFile } from './fixtures.js';
import type { Catalog, ObservedState, SolutionPackage, ViewModel } from './types.js';

const OBSERVED_OK: ObservedState = {
  version: { declared: '7', observed: '7', state: 'OK' },
  replicas: [{ id: 'r1', health: 'GREEN' }],
};

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

describe('valueFrom resolution (UI-D28)', () => {
  const BROKER_PKG: SolutionPackage = {
    solution: 'cache',
    packageVersion: [7],
    appliesTo: '>=7.0 <8.0',
    components: [{
      type: 'cache-engine',
      cardinality: 'exactly-one',
      configPath: 'application.yml',
      fields: [
        { key: 'solaceBroker', type: 'enum', virtual: true, required: true,
          optionsSource: { kind: 'sm-catalog', entity: 'connection', connectionType: 'solace-broker' },
          ui: { section: 'Broker', label: 'Broker' } },
        { key: 'solace.host', type: 'string', required: true,
          valueFrom: { ref: 'solaceBroker', attribute: 'host' },
          ui: { section: 'Broker', label: 'Host' } },
        { key: 'solace.port', type: 'int', default: 55555,
          valueFrom: { ref: 'solaceBroker', attribute: 'port' },
          ui: { section: 'Broker', label: 'Port' } },
        { key: 'solace.username', type: 'string', default: 'guest',
          valueFrom: { ref: 'solaceBroker', attribute: 'username' },
          ui: { section: 'Broker', label: 'Username' } },
      ],
    }],
  };

  const CATALOG: Catalog = {
    connection: [{
      id: 'conn-7', label: 'prod-broker-01', connectionType: 'solace-broker', revision: 4,
      attributes: { host: 'tcps://broker.acme.internal', port: 55443, vpn: 'payments-prod' },
      // NOTE: `username` deliberately absent - exercises precedence row 3.
    }],
  };

  // NOTE ON THE BRIEF: its snippet used `componentType` and read `vm.sections` directly.
  // Neither matches this engine's actual types — ComponentInstance keys the type as `type`,
  // and FieldView lives under ViewModel.components[].sections[].fields, not a top-level
  // `sections`. Fixed to match the real shapes; the test intent (and every assertion) is
  // unchanged.
  const SELECTION = { instances: [{ instanceKey: 'i1', type: 'cache-engine' }] };

  function fieldOf(vm: ViewModel, key: string) {
    return vm.components.flatMap((c) => c.sections.flatMap((s) => s.fields)).find((f) => f.key === key);
  }

  it('resolves a bound field from the selected connection', () => {
    const vm = project(BROKER_PKG, SELECTION, { i1: { solaceBroker: 'conn-7' } }, OBSERVED_OK, CATALOG, 7);
    const host = fieldOf(vm, 'solace.host');
    expect(host?.value).toEqual({ kind: 'value', value: 'tcps://broker.acme.internal', source: 'connection' });
    expect(host?.enabled).toBe(false); // bound fields are read-only until overridden
  });

  it('falls through to the declared default when the connection leaves an attribute unset', () => {
    const vm = project(BROKER_PKG, SELECTION, { i1: { solaceBroker: 'conn-7' } }, OBSERVED_OK, CATALOG, 7);
    expect(fieldOf(vm, 'solace.username')?.value)
      .toEqual({ kind: 'value', value: 'guest', source: 'default' });
  });

  it('treats a literal draft value on a bound field as an override', () => {
    const vm = project(BROKER_PKG, SELECTION,
      { i1: { solaceBroker: 'conn-7', 'solace.port': 55556 } }, OBSERVED_OK, CATALOG, 7);
    const port = fieldOf(vm, 'solace.port');
    expect(port?.value).toEqual({ kind: 'value', value: 55556, source: 'override' });
    expect(port?.enabled).toBe(true); // an override is editable
  });

  it('blocks a required bound field when no connection is selected', () => {
    const vm = project(BROKER_PKG, SELECTION, { i1: {} }, OBSERVED_OK, CATALOG, 7);
    const host = fieldOf(vm, 'solace.host');
    expect(host?.value).toEqual({ kind: 'unset' });
    expect(host?.validations.some((v: { severity: string }) => v.severity === 'block')).toBe(true);
  });

  it('diagnoses a selector pointing at a connection the catalog does not hold', () => {
    const vm = project(BROKER_PKG, SELECTION, { i1: { solaceBroker: 'conn-99' } }, OBSERVED_OK, CATALOG, 7);
    expect(vm.diagnostics.some((d) => d.code === 'CONNECTION_UNRESOLVED')).toBe(true);
  });

  it('never lets a resolved secret reach a FieldValue (I6)', () => {
    const pkg: SolutionPackage = { ...BROKER_PKG, components: [{ ...BROKER_PKG.components[0]!, fields: [
      BROKER_PKG.components[0]!.fields![0]!,
      { key: 'solace.password', type: 'secret' as const, secret: true,
        valueFrom: { ref: 'solaceBroker', attribute: 'password' }, ui: { section: 'Broker', label: 'Password' } },
    ]}]};
    const catalog: Catalog = { connection: [{ ...CATALOG.connection![0]!,
      attributes: { ...CATALOG.connection![0]!.attributes, password: 'hunter2' } }] };
    const vm = project(pkg, SELECTION, { i1: { solaceBroker: 'conn-7' } }, OBSERVED_OK, catalog, 7);
    expect(fieldOf(vm, 'solace.password')?.value).toEqual({ kind: 'secretUnset' });
  });

  it('refuses to resolve a secret attribute into a non-secret field (I6)', () => {
    const pkg: SolutionPackage = { ...BROKER_PKG, components: [{ ...BROKER_PKG.components[0]!, fields: [
      BROKER_PKG.components[0]!.fields![0]!,
      { key: 'solace.passwordPlain', type: 'string', required: false,
        valueFrom: { ref: 'solaceBroker', attribute: 'password' }, ui: { section: 'Broker', label: 'Password (plain)' } },
    ]}]};
    const catalog: Catalog = { connection: [{
      id: 'conn-7', label: 'prod-broker-01', connectionType: 'solace-broker', revision: 4,
      attributes: { host: 'h', password: 'hunter2' },
      secretAttributes: ['password'],
    }] };
    const vm = project(pkg, SELECTION, { i1: { solaceBroker: 'conn-7' } }, OBSERVED_OK, catalog, 7);
    const field = fieldOf(vm, 'solace.passwordPlain');
    expect(JSON.stringify(field?.value)).not.toContain('hunter2');
    expect(field?.value).toEqual({ kind: 'unset' });
    expect(vm.diagnostics.some((d) => d.code === 'SECRET_BINDING_REFUSED')).toBe(true);
  });
});
