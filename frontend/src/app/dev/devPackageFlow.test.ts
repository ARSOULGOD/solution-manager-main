/**
 * End of the dev-loader chain: a dropped file reaching the catalog, the
 * instance list, the instance, and the Designer — through the REAL
 * repositories and the real MSW handlers, not by calling the derivation
 * functions directly.
 *
 * This is the test that would fail if the four routes ever keyed the
 * package differently from one another, which is the failure mode nothing
 * else here can catch.
 *
 * `server.use(...)` registers the dev handlers FOR THIS FILE ONLY —
 * `src/test/setup.ts` resets after every test, so the shared `msw/node`
 * default set stays exactly as it is for every other suite.
 */
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import type { CanonicalSolutionPackage } from '@contracts/solution-package';
import { project } from '@contracts/view-model/project';
import { createConfigRepository } from '@features/designer/configRepository';
import { deriveComponentSelection } from '@features/designer/DesignerPage/componentSelection';
import { useDesignerDraft } from '@features/designer/form/useDesignerDraft';
import { createInstancesRepository } from '@features/inventory/instances';
import { createSolutionsRepository } from '@features/inventory/solutions';
import { ApiError } from '@shared/api';
import { createCpClient } from '@shared/api/cpClient';
import { createDpClient } from '@shared/api/dpClient';
import { CONNECTIONS, toCatalogEntries } from '@shared/testdoubles/data/connections';
import { CACHE_INSTANCES } from '@shared/testdoubles/data/instances';
import { server } from '@shared/testdoubles/server';

import { devDeclaredInstances } from './devDeclaredInstances';
import { devPackageHandlers } from './devPackageHandlers';
import { devPackageStore } from './devPackageStore';
import { withDevPackages } from './devSolutionsRepository';
import type { LoadedPackage } from './parsePackage';
import { CANONICAL_TEST_PACKAGE as CANONICAL_TEST_PACKAGE_RAW, TEST_PACKAGE } from './testPackage';

const CANONICAL_TEST_PACKAGE = CANONICAL_TEST_PACKAGE_RAW as unknown as CanonicalSolutionPackage;

const solutions = createSolutionsRepository(createCpClient('/api'));
const instances = createInstancesRepository(createDpClient('/api'));
// KNOWN REGRESSION (plan 008 Tasks 1/2, out of scope for this task): `configs.loadDesigner` now
// calls the real `getDesigner` wire contract (`configRepository.ts`'s own module header), but
// `devPackageHandlers.ts`'s `/designer` override still returns the OLD fixture shape — every test
// below that calls `configs.loadDesigner`/`configs.save` fails until that dev-tooling override is
// rewritten to the same wire shape. Not fixed here: dev tooling is not named in plan 008's Tasks 1/2.
const configs = createConfigRepository(createDpClient('/api'), solutions.getPackage.bind(solutions));

const pkg = TEST_PACKAGE as unknown as LoadedPackage;

beforeEach(() => {
  devPackageStore.clear();
  devDeclaredInstances.clear();
  server.use(...devPackageHandlers);
});

afterEach(() => {
  devPackageStore.clear();
  devDeclaredInstances.clear();
});

describe('the merged catalog', () => {
  it('serves exactly the default fixtures when nothing is loaded', async () => {
    const catalog = await solutions.list();

    const ids = catalog.map((entry) => entry.solutionId);
    expect(ids).toContain('cache');
    expect(ids).not.toContain('filemesh');
    expect(ids).not.toContain('widget-mesh');
  });

  it('adds a loaded package WITHOUT displacing anything already there', async () => {
    const before = await solutions.list();

    devPackageStore.register(pkg);
    const after = await solutions.list();

    expect(after).toHaveLength(before.length + 1);
    expect(after.map((entry) => entry.solutionId)).toEqual([
      ...before.map((e) => e.solutionId),
      'widget-mesh',
    ]);
  });

  it('reads the store at REQUEST time, so a package loaded after boot appears', async () => {
    expect((await solutions.list()).map((e) => e.solutionId)).not.toContain('widget-mesh');

    devPackageStore.register(pkg);

    expect((await solutions.list()).map((e) => e.solutionId)).toContain('widget-mesh');
  });

  it('carries the derived card through the wire unchanged, mapped to the console.yaml Solution envelope', async () => {
    devPackageStore.register(pkg);

    const card = (await solutions.list()).find((entry) => entry.solutionId === 'widget-mesh');

    expect(card).toMatchObject({
      solutionId: 'widget-mesh',
      solution: 'widget-mesh',
      // The package's whole version list, passed through unchanged (FE-118).
      packageVersions: [2],
      latestPackageVersion: 2,
      // v1 loads no signed packages — `Solution.signature` is always `unsigned`
      // (plan 005 Task 2); `appliesTo`/`kind`/`devMode`/`instanceCount` have no
      // counterpart on `Solution` and are no longer on the wire.
      signature: 'unsigned',
    });
  });

  it('serves the raw package by solutionId + packageVersion for a bundled solution', async () => {
    const cachePkg = await solutions.getPackage('cache', 6);
    expect(cachePkg.solution).toBe('cache');
    expect(cachePkg.components.length).toBeGreaterThan(0);
  });

  it('serves the raw package for a package the operator dropped this session', async () => {
    devPackageStore.commitPending();
    devPackageStore.holdPending(pkg);
    devPackageStore.commitPending();
    const loaded = await solutions.getPackage(pkg.solution, pkg.packageVersion[0] ?? 1);
    expect(loaded.solution).toBe(pkg.solution);
  });

  it('a session-declared instance carries its componentTypes binding into the Designer load', async () => {
    const outcome = devDeclaredInstances.declare(
      'cache',
      {
        name: 'role-bound-1',
        packageVersion: 6,
        env: 'DEV',
        org: 'Acme',
        subOrg: 'Platform',
        driver: 'docker',
        locus: 'host_local',
        replicas: ['role-bound-1'],
        componentTypes: ['cache-engine'],
      },
      CACHE_INSTANCES.map((i) => i.name),
    );
    expect(outcome.ok).toBe(true);
    if (!outcome.ok) return;
    const load = await configs.loadDesigner(outcome.instance.id);
    expect(load.componentTypes).toEqual(['cache-engine']);
  });

  it('a session-declared instance with no componentTypes leaves it undefined on the Designer load', async () => {
    const outcome = devDeclaredInstances.declare(
      'cache',
      {
        name: 'no-binding-1',
        packageVersion: 6,
        env: 'DEV',
        org: 'Acme',
        subOrg: 'Platform',
        driver: 'docker',
        locus: 'host_local',
        replicas: ['no-binding-1'],
      },
      CACHE_INSTANCES.map((i) => i.name),
    );
    expect(outcome.ok).toBe(true);
    if (!outcome.ok) return;
    const load = await configs.loadDesigner(outcome.instance.id);
    expect(load.componentTypes).toBeUndefined();
  });
});

describe('instance lookup', () => {
  it('lists the synthesized instance under the package solution id', async () => {
    devPackageStore.register(pkg);

    const page = await instances.list('widget-mesh', {});

    expect(page.total).toBe(1);
    expect(page.items[0]?.id).toBe('widget-mesh-dev-01');
  });

  it('fetches that instance by id', async () => {
    devPackageStore.register(pkg);

    const instance = await instances.get('widget-mesh-dev-01');

    expect(instance.solutionId).toBe('widget-mesh');
    expect(instance.version.state).toBe('unverified');
  });

  it('leaves every id it does not own to the default handlers', async () => {
    devPackageStore.register(pkg);

    // A Cache instance from the default fixture set still resolves — the
    // dev handler returning `undefined` for an id it does not own is what
    // makes that true.
    expect((await instances.get('cache-prod-01')).solutionId).toBe('cache');
  });

  it('404s for an instance of a package that was never loaded', async () => {
    await expect(instances.get('widget-mesh-dev-01')).rejects.toBeInstanceOf(ApiError);
  });
});

describe('the Designer, through the real projection path', () => {
  it('serves the raw package the operator loaded — the browser projects it (FE-D33)', async () => {
    devPackageStore.register(pkg);

    const load = await configs.loadDesigner('widget-mesh-dev-01');

    expect(load.package.solution).toBe('widget-mesh');
    // The LAST version the package declares (`derivePackageInstance`'s own rule).
    expect(load.packageVersion).toBe(2);
    // The real wire `ViewModel.components[]` carries one entry per projected
    // component instance regardless of whether it has any literal draft
    // value — `configRepository.ts`'s `mapWireDesignerToLocal` seeds
    // `draftValues[instanceKey]` from that list unconditionally, so a
    // component with no draft overrides reads as `{}`, never an absent key.
    expect(load.values).toEqual({ 'widget-connector': {} });

    // Projecting the served load reproduces exactly what the OLD (deleted)
    // pre-projected handler used to assert directly — every one of these is
    // a PACKAGE declaration, none of it a fixture, so this can only have
    // come through the real `project()`.
    const viewModel = project(
      load.package,
      [{ type: 'widget-connector', instanceId: 'widget-connector' }],
      load.values,
      load.observed,
      load.packageVersion,
    );
    expect(viewModel.sections.flatMap((s) => s.fields.map((f) => f.key))).toEqual([
      'MODE',
      'POLL_INTERVAL',
      'BATCH_SIZE',
    ]);
    expect(viewModel.sections.find((s) => s.title === 'General')?.fields[0]?.options).toEqual([
      'push',
      'pull',
    ]);
    expect(viewModel.sections.map((s) => s.title)).toEqual(['General', 'Tuning']);
  });

  it('changes with the package — edit a declaration, the served package follows', async () => {
    devPackageStore.register(pkg);
    const before = await configs.loadDesigner('widget-mesh-dev-01');
    expect(before.package.components[0]?.fields).toHaveLength(3);

    // The same package with one field removed, re-registered as an author
    // would after editing the file and re-dropping it.
    const edited = structuredClone(TEST_PACKAGE) as unknown as LoadedPackage;
    const [firstComponent] = edited.components;
    devPackageStore.register({
      ...edited,
      components: [{ ...firstComponent!, fields: firstComponent!.fields.slice(0, 2) }],
    });

    const after = await configs.loadDesigner('widget-mesh-dev-01');
    expect(after.package.components[0]?.fields).toHaveLength(2);
  });

  it('still serves Cache from the same route', async () => {
    devPackageStore.register(pkg);

    expect((await configs.loadDesigner('cache-prod-01')).package.solution).toBe('cache');
  });
});

// Plan 005 note: `withDevPackages.load()` used to receive the raw `File` and
// read its bytes itself (`parsePackageFile`), delegating METADATA ONLY to the
// real repository — because the real repository's `load()` only ever sent
// `{ fileName, fileSize }`. Plan 005 wired `load()` to the real `loadPackage`
// contract (body `{ package: <document> }`); `LoadPackageDialog.tsx` now
// parses and validates the file itself (`inspectPackageFile.ts`) before
// calling `repository.load()`, so this wrapper receives an already-validated
// document, never a `File`. The file-parsing/rejection cases this block used
// to cover (`parsePackageFile`'s own gates) are exercised directly by
// `parsePackage.test.ts`; the real parse path is covered by
// `inspectPackageFile.ts`'s own consumers (`LoadPackageDialog.test.tsx`).
describe('the repository decorator', () => {
  function baseRepository() {
    const calls: unknown[] = [];
    return {
      calls,
      repository: {
        list: () => Promise.resolve([]),
        getPackage: () => Promise.reject(new Error('not used in this test')),
        load: (loadedPkg: unknown) => {
          calls.push(loadedPkg);
          return Promise.resolve({
            solutionId: 'widget-mesh',
            packageVersion: [2],
            signature: 'unsigned' as const,
            stateVersion: 1,
            correlationId: 'corr-1',
          });
        },
      },
    };
  }

  it('holds the validated document pending, and delegates it to the real repository unchanged', async () => {
    const { calls, repository } = baseRepository();
    const dev = withDevPackages(repository);

    const result = await dev.load(CANONICAL_TEST_PACKAGE);

    expect(result.solutionId).toBe('widget-mesh');
    expect(calls).toEqual([CANONICAL_TEST_PACKAGE]);
    expect(devPackageStore.pending()?.solution).toBe('widget-mesh');
    expect(devPackageStore.list()).toHaveLength(0);
  });

  it('registers only on commit', async () => {
    const { repository } = baseRepository();
    const dev = withDevPackages(repository);
    await dev.load(CANONICAL_TEST_PACKAGE);

    dev.commitPending?.();

    expect(devPackageStore.list().map((entry) => entry.solution)).toEqual(['widget-mesh']);
  });

  it('registers nothing when the dialog is dismissed', async () => {
    const { repository } = baseRepository();
    const dev = withDevPackages(repository);
    await dev.load(CANONICAL_TEST_PACKAGE);

    dev.discardPending?.();

    expect(devPackageStore.list()).toEqual([]);
    expect(devPackageStore.pending()).toBeUndefined();
  });
});

/**
 * Declaring (task 115) — the second way an instance comes to exist in dev,
 * alongside the one a dropped package synthesizes. These go through the
 * real repository and the real handlers, so they would fail if the POST,
 * the merged GET and the Designer route ever keyed an instance
 * differently from one another.
 */
describe('declaring an instance', () => {
  const declaration = {
    name: 'ledger-eu-01',
    packageVersion: 7,
    env: 'TEST' as const,
    org: 'Acme Financial',
    subOrg: 'Payments',
    driver: 'k8s' as const,
    locus: 'host_local' as const,
    replicas: ['ledger-eu-0', 'ledger-eu-1'],
  };

  it('drop a package, declare against it, and BOTH instances are in its grid', async () => {
    devPackageStore.register(pkg);

    const before = await instances.list('widget-mesh', {});
    expect(before.items.map((row) => row.id)).toEqual(['widget-mesh-dev-01']);

    await instances.declare('widget-mesh', declaration);

    const after = await instances.list('widget-mesh', {});
    expect(after.items.map((row) => row.id)).toEqual(['widget-mesh-dev-01', 'ledger-eu-01']);
    expect(after.total).toBe(2);
  });

  it('declares against a baked-in solution that has no loaded package at all', async () => {
    const before = await instances.list('cache', {});

    await instances.declare('cache', declaration);

    const after = await instances.list('cache', {});
    expect(after.items).toHaveLength(before.items.length + 1);
    expect(after.items.map((row) => row.id)).toContain('ledger-eu-01');
  });

  it('is fetchable by id on the route the grid links to', async () => {
    await instances.declare('cache', declaration);

    const one = await instances.get('ledger-eu-01');
    expect(one).toMatchObject({ id: 'ledger-eu-01', solutionId: 'cache', env: 'TEST' });
  });

  it('opens the Designer of a baked-in solution it was declared against, with no package ever loaded', async () => {
    await instances.declare('cache', declaration);

    const load = await configs.loadDesigner('ledger-eu-01');

    expect(load.package.solution).toBe('cache');
  });

  it('opens the Designer of the package it was declared against', async () => {
    devPackageStore.register(pkg);
    await instances.declare('widget-mesh', declaration);

    const load = await configs.loadDesigner('ledger-eu-01');

    // Not a fixture: the declared instance resolves its schema through its
    // SOLUTION, having no package of its own — the same package a direct
    // dev-loaded instance of that solution gets.
    expect(load.package.solution).toBe('widget-mesh');
    const derived = await configs.loadDesigner('widget-mesh-dev-01');
    expect(load.package.components).toEqual(derived.package.components);
  });

  it('round-trips the declared package version through storage and the grid', async () => {
    await instances.declare('cache', { ...declaration, packageVersion: 6 });

    const row = (await instances.list('cache', {})).items.find((r) => r.id === 'ledger-eu-01');
    expect(row?.packageVersion).toBe(6);
    // And through a reload: the store persists the DECLARATION and
    // rebuilds the row, so the version has to survive that round trip too.
    expect((await instances.get('ledger-eu-01')).packageVersion).toBe(6);
  });

  it('refuses a declaration with no package version — identity, not an observation', async () => {
    const withoutVersion: Record<string, unknown> = { ...declaration };
    delete withoutVersion['packageVersion'];

    await expect(instances.declare('cache', withoutVersion as never)).rejects.toMatchObject({
      status: 400,
    });
  });

  it('the Designer excludes a v-scoped field when the instance is declared at an older version', async () => {
    // A two-version package whose second field belongs to v2 only.
    const versioned = structuredClone(TEST_PACKAGE) as unknown as LoadedPackage & {
      packageVersion: number[];
    };
    versioned.packageVersion = [1, 2];
    const fields = versioned.components[0]!.fields as unknown as Record<string, unknown>[];
    fields[0]!['version'] = [1, 2];
    fields[1]!['version'] = [2];
    fields[2]!['version'] = [1, 2];
    devPackageStore.register(versioned);

    await instances.declare('widget-mesh', { ...declaration, packageVersion: 1 });
    await instances.declare('widget-mesh', {
      ...declaration,
      name: 'ledger-eu-02',
      packageVersion: 2,
    });

    const atV1 = await configs.loadDesigner('ledger-eu-01');
    const atV2 = await configs.loadDesigner('ledger-eu-02');

    const keysOf = (load: typeof atV1) =>
      project(
        load.package,
        [{ type: 'widget-connector', instanceId: 'widget-connector' }],
        load.values,
        load.observed,
        load.packageVersion,
      ).sections.flatMap((section) => section.fields.map((field) => field.key));

    expect(keysOf(atV1)).not.toContain('POLL_INTERVAL');
    expect(keysOf(atV2)).toContain('POLL_INTERVAL');
    // And the served load carries the INSTANCE's version, not the package's list.
    expect(atV1.packageVersion).toBe(1);
    expect(atV2.packageVersion).toBe(2);
  });

  // Plan 005 note: `console.yaml`'s `Solution` carries no instance count
  // (`solutionsRepository.ts`'s header; `sidebarSolutions.ts` flags the same
  // gap for the drawer), so the three FE-120 tests that used to read
  // `solutions.list()`'s own `instanceCount` field now read the instance
  // grid directly — the fact being tested (declare/undeclare move the
  // count, and it is keyed on the card id, not the shared `solution`) is
  // unchanged.
  it('moves the instance grid count on declare AND on undeclare — never a stale fixture number', async () => {
    const countFor = async (id: string) => (await instances.list(id, {})).total;

    const before = await countFor('cache');
    expect(before).toBe(CACHE_INSTANCES.length);

    await instances.declare('cache', declaration);
    expect(await countFor('cache')).toBe(before + 1);

    await instances.undeclare('ledger-eu-01');
    expect(await countFor('cache')).toBe(before);
  });

  it('counts on the CARD id, not the shared solution — cache and cache-nightly differ', async () => {
    // `cache` and `cache-nightly` share one `solution`; only the card id
    // matches what `/dp/solutions/:solutionId/instances` filters on.
    const cards = await solutions.list();
    const cache = cards.find((card) => card.solutionId === 'cache');
    const nightly = cards.find((card) => card.solutionId === 'cache-nightly');
    expect(cache).toBeDefined();
    expect(nightly).toBeDefined();

    expect((await instances.list('cache', {})).total).toBe(CACHE_INSTANCES.length);
    // The nightly card's own id has no instance — honest zero, never the old
    // fixture's fabricated 1.
    expect((await instances.list('cache-nightly', {})).total).toBe(0);
  });

  it('refuses a name a FIXTURE instance already holds', async () => {
    await expect(
      instances.declare('cache', { ...declaration, name: 'cache-prod-01' }),
    ).rejects.toBeInstanceOf(ApiError);

    const after = await instances.list('cache', {});
    expect(after.items.filter((row) => row.name === 'cache-prod-01')).toHaveLength(1);
  });

  it('refuses the same name twice', async () => {
    await instances.declare('cache', declaration);

    await expect(instances.declare('cache', declaration)).rejects.toMatchObject({ status: 409 });
  });

  it('refuses a declaration missing a required field', async () => {
    await expect(instances.declare('cache', { ...declaration, org: '   ' })).rejects.toMatchObject({
      status: 400,
    });
  });

  it('undeclaring removes it from the grid and from its own route', async () => {
    await instances.declare('cache', declaration);
    expect((await instances.list('cache', {})).items.map((r) => r.id)).toContain('ledger-eu-01');

    await instances.undeclare('ledger-eu-01');

    expect((await instances.list('cache', {})).items.map((r) => r.id)).not.toContain(
      'ledger-eu-01',
    );
    await expect(instances.get('ledger-eu-01')).rejects.toBeInstanceOf(ApiError);
  });

  it('undeclares a FIXTURE instance too — a tombstone, since a fixture array cannot be mutated', async () => {
    const before = await instances.list('cache', {});
    expect(before.items.map((r) => r.id)).toContain('cache-prod-01');

    await instances.undeclare('cache-prod-01');

    const after = await instances.list('cache', {});
    expect(after.items.map((r) => r.id)).not.toContain('cache-prod-01');
    expect(after.items).toHaveLength(before.items.length - 1);
  });

  it('refuses to undeclare something already gone', async () => {
    await instances.undeclare('cache-prod-01');

    await expect(instances.undeclare('cache-prod-01')).rejects.toMatchObject({ status: 404 });
  });

  it('declaring the same name again brings the row back', async () => {
    await instances.declare('cache', declaration);
    await instances.undeclare('ledger-eu-01');

    await instances.declare('cache', declaration);

    expect((await instances.list('cache', {})).items.map((r) => r.id)).toContain('ledger-eu-01');
  });

  it('re-dropping the package does NOT discard declarations made against it', async () => {
    devPackageStore.register(pkg);
    await instances.declare('widget-mesh', declaration);

    devPackageStore.register(pkg);

    const after = await instances.list('widget-mesh', {});
    expect(after.items.map((row) => row.id)).toContain('ledger-eu-01');
  });
});

/**
 * The full Connection Objects journey (`UI-D27`/`UI-D28`/`UI-D29`), through
 * the real repositories and the real MSW handlers — declare an instance
 * against a chosen connection, load its Designer, resolve its bound fields,
 * and save. Every prior test in this file already proves `componentTypes`
 * makes this same round trip; this is the same proof for `connections`.
 */
describe('Connection Objects: declare -> Designer -> resolved fields -> save (UI-D27/28/29)', () => {
  const brokerDeclaration = {
    name: 'cache-broker-01',
    packageVersion: 7,
    env: 'PROD' as const,
    org: 'Acme Financial',
    subOrg: 'Payments',
    driver: 'k8s' as const,
    locus: 'host_local' as const,
    replicas: ['cache-broker-01'],
    connections: { solaceBroker: 'conn-7' },
  };

  it('retains the declared selector as part of the new instance\'s Designer draft', async () => {
    await instances.declare('cache', brokerDeclaration);

    const load = await configs.loadDesigner('cache-broker-01');

    expect(load.values['cache-engine']).toMatchObject({ solaceBroker: 'conn-7' });
  });

  it('returns the current connection catalog, in the CatalogEntry shape project() expects', async () => {
    await instances.declare('cache', brokerDeclaration);

    const load = await configs.loadDesigner('cache-broker-01');

    const entry = load.connectionCatalog?.find((row) => row.id === 'conn-7');
    expect(entry).toMatchObject({
      label: 'prod-broker-01',
      connectionType: 'solace-broker',
      revision: 4,
      attributes: { host: 'tcps://broker.acme.internal', port: 55443, vpn: 'payments-prod' },
    });
  });

  describe('which connections the Designer is offered for an instance', () => {
    const ids = async (instanceId: string) =>
      ((await configs.loadDesigner(instanceId)).connectionCatalog ?? []).map((entry) => entry.id);

    it("offers only ACTIVE connections in the instance's own scope — not other environments, other organizations, or retired ones", async () => {
      await instances.declare('cache', brokerDeclaration); // Acme Financial / Payments / PROD

      const offered = await ids('cache-broker-01');

      expect(offered).toContain('conn-7'); // PROD broker, active, in scope
      expect(offered).not.toContain('conn-8'); // same sub-org, but TEST
      expect(offered).not.toContain('conn-9'); // inactive (retired)
      expect(offered.filter((id) => id.startsWith('conn-b-'))).toEqual([]); // other scopes' brokers
    });

    it('offers the brokers of a DIFFERENT scope for an instance declared there', async () => {
      await instances.declare('cache', {
        name: 'cache-initech-01',
        packageVersion: brokerDeclaration.packageVersion,
        env: 'PROD' as const,
        org: 'Initech',
        subOrg: 'Retail',
        driver: brokerDeclaration.driver,
        locus: brokerDeclaration.locus,
        replicas: ['cache-initech-01'],
      });

      expect(await ids('cache-initech-01')).toEqual(['conn-b-initech-retail-prod']);
    });

    it('keeps a connection the instance ALREADY selected even when it is out of scope, so the saved choice still resolves', async () => {
      await instances.declare('cache', {
        ...brokerDeclaration,
        name: 'cache-moved-01',
        replicas: ['cache-moved-01'],
        connections: { solaceBroker: 'conn-8' }, // a TEST broker on a PROD instance
      });

      const offered = await ids('cache-moved-01');

      expect(offered).toContain('conn-8');
      expect(offered).toContain('conn-7');
    });
  });

  it('freezes the selected connection\'s revision at declare time, for drift detection', async () => {
    await instances.declare('cache', brokerDeclaration);

    const load = await configs.loadDesigner('cache-broker-01');

    expect(load.appliedConnectionRevisions).toEqual({ 'conn-7': 4 });
  });

  describe('resolving bound fields through the real projection path', () => {
    /** `solace.*` is behind `solace.enabled` (`when`) — the operator's own toggle, not this feature's concern. */
    async function projectedFields(instanceId: string, overrides: Record<string, unknown> = {}) {
      const load = await configs.loadDesigner(instanceId);
      const componentSelection = deriveComponentSelection(
        load.package,
        load.packageVersion,
        load.componentTypes,
      );
      const viewModel = project(
        load.package,
        componentSelection,
        { 'cache-engine': { ...load.values['cache-engine'], 'solace.enabled': true, ...overrides } },
        load.observed,
        load.packageVersion,
        load.connectionCatalog === undefined ? {} : { connection: load.connectionCatalog },
      );
      const byKey = new Map(viewModel.sections.flatMap((s) => s.fields).map((f) => [f.key, f]));
      return byKey;
    }

    it('resolves host, port, vpn and username from the selected connection', async () => {
      await instances.declare('cache', brokerDeclaration);

      const fields = await projectedFields('cache-broker-01');

      expect(fields.get('solace.host')?.value).toEqual({
        kind: 'value', value: 'tcps://broker.acme.internal', source: 'connection',
      });
      expect(fields.get('solace.port')?.value).toEqual({
        kind: 'value', value: 55443, source: 'connection',
      });
      expect(fields.get('solace.vpn')?.value).toEqual({
        kind: 'value', value: 'payments-prod', source: 'connection',
      });
      expect(fields.get('solace.username')?.value).toEqual({
        kind: 'value', value: 'svc-cache', source: 'connection',
      });
    });

    it('never resolves a secret plaintext — the bound password field reports unset, not a value', async () => {
      await instances.declare('cache', brokerDeclaration);

      const fields = await projectedFields('cache-broker-01');

      expect(fields.get('solace.password')?.value).toEqual({ kind: 'secretUnset' });
    });

    it('falls through cleanly when the chosen connection leaves an attribute unset (conn-8 has no username)', async () => {
      await instances.declare('cache', {
        ...brokerDeclaration,
        name: 'cache-broker-02',
        connections: { solaceBroker: 'conn-8' },
      });

      const fields = await projectedFields('cache-broker-02');

      expect(fields.get('solace.host')?.value).toMatchObject({ value: 'tcps://broker-st.acme.internal' });
      // No connection value AND no package default for `solace.username` —
      // unset, never a stale or fabricated value.
      expect(fields.get('solace.username')?.value).toEqual({ kind: 'unset' });
    });

    it('an explicit override beats the selected connection', async () => {
      await instances.declare('cache', brokerDeclaration);

      const fields = await projectedFields('cache-broker-01', { 'solace.port': 55999 });

      expect(fields.get('solace.port')?.value).toEqual({
        kind: 'value', value: 55999, source: 'override',
      });
    });
  });

  it('changing the selected connection rebinds every non-overridden field, through the real hook', async () => {
    await instances.declare('cache', brokerDeclaration);
    const load = await configs.loadDesigner('cache-broker-01');
    const componentSelection = deriveComponentSelection(
      load.package,
      load.packageVersion,
      load.componentTypes,
    );

    const { result } = renderHook(() =>
      useDesignerDraft({
        pkg: load.package,
        componentSelection,
        observedState: load.observed,
        initialDraft: { 'cache-engine': { ...load.values['cache-engine'], 'solace.enabled': true } },
        packageVersion: load.packageVersion,
        // This test is about REBINDING, not scoping: conn-8 is a TEST broker and this instance is
        // PROD, so the instance's own (scoped) catalog rightly does not offer it. Hand the hook the
        // whole catalog so the mechanism is what's under test.
        catalog: { connection: toCatalogEntries(CONNECTIONS) },
      }),
    );

    act(() => {
      result.current.onFieldChange('cache-engine', 'solaceBroker', 'conn-8');
    });

    const host = result.current.viewModel.sections
      .flatMap((s) => s.fields)
      .find((f) => f.key === 'solace.host');
    expect(host?.value).toEqual({
      kind: 'value', value: 'tcps://broker-st.acme.internal', source: 'connection',
    });
  });

  it('the save payload carries a $from reference for a bound field, never its resolved value', async () => {
    await instances.declare('cache', brokerDeclaration);
    const load = await configs.loadDesigner('cache-broker-01');
    const componentSelection = deriveComponentSelection(
      load.package,
      load.packageVersion,
      load.componentTypes,
    );

    const { result } = renderHook(() =>
      useDesignerDraft({
        pkg: load.package,
        componentSelection,
        observedState: load.observed,
        initialDraft: { 'cache-engine': { ...load.values['cache-engine'], 'solace.enabled': true } },
        packageVersion: load.packageVersion,
        catalog: load.connectionCatalog === undefined ? {} : { connection: load.connectionCatalog },
      }),
    );

    const payload = result.current.payload['cache-engine'];
    expect(payload?.['solace.host']).toEqual({ $from: 'conn-7#host' });
    expect(JSON.stringify(payload)).not.toMatch(/broker\.acme\.internal/);
  });

  it('editing a connection increments its revision, through the real fixture API', async () => {
    const cpClient = createCpClient('/api');
    const before = await cpClient.get<{ revision: number }[]>('/connections?type=solace-broker');
    const conn7Before = before.find((row) => (row as unknown as { id: string }).id === 'conn-7');

    await cpClient.put(`/connections/conn-7`, {
      name: 'prod-broker-01',
      attributes: { host: 'tcps://broker.acme.internal', port: 55443, vpn: 'payments-prod', username: 'svc-cache' },
    });

    const after = await cpClient.get<{ revision: number }[]>('/connections?type=solace-broker');
    const conn7After = after.find((row) => (row as unknown as { id: string }).id === 'conn-7');
    expect(conn7After?.revision).toBe((conn7Before?.revision ?? 0) + 1);
  });

  it('a revision bump after declare is what the drift banner would detect (client half, UI-D29)', async () => {
    const cpClient = createCpClient('/api');
    await instances.declare('cache', brokerDeclaration);
    const before = await configs.loadDesigner('cache-broker-01');
    expect(before.appliedConnectionRevisions).toEqual({ 'conn-7': 4 });

    await cpClient.put('/connections/conn-7', {
      name: 'prod-broker-01',
      attributes: { host: 'tcps://broker.acme.internal', port: 55443, vpn: 'payments-prod', username: 'svc-cache' },
    });

    const after = await configs.loadDesigner('cache-broker-01');
    const currentRevision = after.connectionCatalog?.find((row) => row.id === 'conn-7')?.revision;
    // The applied snapshot stayed frozen; the catalog's own revision moved.
    // Comparing the two is exactly what `detectDrift` does.
    expect(after.appliedConnectionRevisions).toEqual({ 'conn-7': 4 });
    expect(currentRevision).toBe(5);
  });
});
