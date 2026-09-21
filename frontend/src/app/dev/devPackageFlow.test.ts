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
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { project } from '@contracts/view-model/project';
import { createConfigRepository } from '@features/designer/configRepository';
import { createInstancesRepository } from '@features/inventory/instances';
import { createSolutionsRepository } from '@features/inventory/solutions';
import { ApiError } from '@shared/api';
import { createCpClient } from '@shared/api/cpClient';
import { createDpClient } from '@shared/api/dpClient';
import { CACHE_INSTANCES } from '@shared/testdoubles/data/instances';
import { server } from '@shared/testdoubles/server';

import { devDeclaredInstances } from './devDeclaredInstances';
import { devPackageHandlers } from './devPackageHandlers';
import { devPackageStore } from './devPackageStore';
import { withDevPackages } from './devSolutionsRepository';
import type { LoadedPackage } from './parsePackage';
import { TEST_PACKAGE, packageFile } from './testPackage';

const solutions = createSolutionsRepository(createCpClient('/api'));
const instances = createInstancesRepository(createDpClient('/api'));
const configs = createConfigRepository(createDpClient('/api'));

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

    const ids = catalog.map((entry) => entry.id);
    expect(ids).toContain('cache');
    expect(ids).toContain('filemesh');
    expect(ids).not.toContain('widget-mesh');
  });

  it('adds a loaded package WITHOUT displacing anything already there', async () => {
    const before = await solutions.list();

    devPackageStore.register(pkg);
    const after = await solutions.list();

    expect(after).toHaveLength(before.length + 1);
    expect(after.map((entry) => entry.id)).toEqual([...before.map((e) => e.id), 'widget-mesh']);
  });

  it('reads the store at REQUEST time, so a package loaded after boot appears', async () => {
    expect((await solutions.list()).map((e) => e.id)).not.toContain('widget-mesh');

    devPackageStore.register(pkg);

    expect((await solutions.list()).map((e) => e.id)).toContain('widget-mesh');
  });

  it('carries the derived card through the wire unchanged', async () => {
    devPackageStore.register(pkg);

    const card = (await solutions.list()).find((entry) => entry.id === 'widget-mesh');

    expect(card).toMatchObject({
      solution: 'widget-mesh',
      // The package's whole version list, passed through unchanged (FE-118).
      packageVersion: [2],
      appliesTo: '>=1.0 <2.0',
      kind: 'widget-connector',
      devMode: false,
      instanceCount: 1,
    });
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
    expect(load.values).toEqual({});

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

describe('the repository decorator', () => {
  function baseRepository() {
    const calls: { name: string; size: number }[] = [];
    return {
      calls,
      repository: {
        list: () => Promise.resolve([]),
        load: (file: Pick<File, 'name' | 'size'>) => {
          calls.push({ name: file.name, size: file.size });
          return Promise.resolve({
            verified: true,
            devMode: false,
            message: 'ok',
            correlationId: 'corr-1',
          });
        },
      },
    };
  }

  it('parses the file, holds it pending, and delegates METADATA ONLY', async () => {
    const { calls, repository } = baseRepository();
    const dev = withDevPackages(repository);

    const file = packageFile();
    const result = await dev.load(file);

    expect(result.verified).toBe(true);
    // The wire call carried the name and size, and nothing else — the
    // bytes were read here, in the browser.
    expect(calls).toEqual([{ name: 'package.json', size: file.size }]);
    expect(devPackageStore.pending()?.solution).toBe('widget-mesh');
    expect(devPackageStore.list()).toHaveLength(0);
  });

  it('registers only on commit', async () => {
    const { repository } = baseRepository();
    const dev = withDevPackages(repository);
    await dev.load(packageFile());

    dev.commitPending?.();

    expect(devPackageStore.list().map((entry) => entry.solution)).toEqual(['widget-mesh']);
  });

  it('registers nothing when the dialog is dismissed', async () => {
    const { repository } = baseRepository();
    const dev = withDevPackages(repository);
    await dev.load(packageFile());

    dev.discardPending?.();

    expect(devPackageStore.list()).toEqual([]);
    expect(devPackageStore.pending()).toBeUndefined();
  });

  it('rejects an unreadable file as an ApiError carrying the stated reason', async () => {
    const { calls, repository } = baseRepository();
    const dev = withDevPackages(repository);

    await expect(dev.load(packageFile('widget-mesh.zip'))).rejects.toMatchObject({
      status: 422,
      code: 'zip-unsupported',
    });
    // Nothing was posted and nothing was held: a file that is not a
    // package never reaches the wire or the pending slot.
    expect(calls).toEqual([]);
    expect(devPackageStore.pending()).toBeUndefined();
  });

  it('delegates untouched when handed file metadata rather than a File', async () => {
    const { calls, repository } = baseRepository();
    const dev = withDevPackages(repository);

    await dev.load({ name: 'metadata-only.json', size: 12 });

    expect(calls).toEqual([{ name: 'metadata-only.json', size: 12 }]);
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

  it('moves the catalog count on declare AND on undeclare — never a stale fixture number', async () => {
    const countFor = async (id: string) =>
      (await solutions.list()).find((card) => card.id === id)?.instanceCount;

    const before = await countFor('cache');
    expect(before).toBe(CACHE_INSTANCES.length);

    await instances.declare('cache', declaration);
    expect(await countFor('cache')).toBe((before ?? 0) + 1);

    await instances.undeclare('ledger-eu-01');
    expect(await countFor('cache')).toBe(before);
  });

  it('agrees with the grid the card links to — counted on the CARD id, not the solution', async () => {
    // `cache` and `cache-nightly` share one `solution`; only the card id
    // matches what `/dp/solutions/:solutionId/instances` filters on.
    const cards = await solutions.list();
    for (const card of cards) {
      const grid = await instances.list(card.id, {});
      expect(card.instanceCount).toBe(grid.total);
    }
  });

  it('reports 0 for the nightly card, which no instance actually belongs to', async () => {
    const nightly = (await solutions.list()).find((card) => card.id === 'cache-nightly');

    // The fixture claimed 1 with an empty grid. Honest now.
    expect(nightly?.instanceCount).toBe(0);
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
