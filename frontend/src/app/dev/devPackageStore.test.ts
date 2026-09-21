/**
 * The store's two jobs, tested separately: hold-then-commit-or-discard
 * (so a picked-but-abandoned file never reaches the catalog), and survive
 * a reload without ever trusting what it reads back.
 *
 * `createDevPackageStore(storage)` takes the storage seam as an argument,
 * so these run against a fake rather than the ambient `sessionStorage` —
 * which is also what lets the corrupt-entry cases write a value no real
 * code path could produce.
 */
import { describe, expect, it } from 'vitest';

import {
  DEV_PACKAGE_STORAGE_KEY,
  createDevPackageStore,
  type DevPackageStorage,
} from './devPackageStore';
import type { LoadedPackage } from './parsePackage';
import { TEST_PACKAGE, packageWith } from './testPackage';

const pkg = TEST_PACKAGE as unknown as LoadedPackage;
const other = packageWith({ solution: 'other-mesh' }) as LoadedPackage;

/** An in-memory `Storage` slice, so a test can inspect and corrupt what was written. */
function fakeStorage(seed?: string): DevPackageStorage & { raw(): string | null } {
  const cells = new Map<string, string>();
  if (seed !== undefined) {
    cells.set(DEV_PACKAGE_STORAGE_KEY, seed);
  }
  return {
    getItem: (key) => cells.get(key) ?? null,
    setItem: (key, value) => {
      cells.set(key, value);
    },
    removeItem: (key) => {
      cells.delete(key);
    },
    raw: () => cells.get(DEV_PACKAGE_STORAGE_KEY) ?? null,
  };
}

describe('devPackageStore — registration', () => {
  it('registers a package under its own solution id', () => {
    const store = createDevPackageStore(fakeStorage());

    store.register(pkg);

    expect(store.list()).toHaveLength(1);
    expect(store.get('widget-mesh')?.solution).toBe('widget-mesh');
    expect(store.get('nothing-like-it')).toBeUndefined();
  });

  it('REPLACES a re-registered solution rather than duplicating it', () => {
    const store = createDevPackageStore(fakeStorage());
    store.register(pkg);

    store.register(packageWith({ packageVersion: 9 }) as LoadedPackage);

    expect(store.list()).toHaveLength(1);
    expect(store.get('widget-mesh')?.packageVersion).toBe(9);
  });

  it('keeps distinct solutions side by side, in registration order', () => {
    const store = createDevPackageStore(fakeStorage());

    store.register(pkg);
    store.register(other);

    expect(store.list().map((entry) => entry.solution)).toEqual(['widget-mesh', 'other-mesh']);
  });

  it('derives one catalog card and one instance per registered package', () => {
    const store = createDevPackageStore(fakeStorage());
    store.register(pkg);

    expect(store.summaries().map((card) => card.id)).toEqual(['widget-mesh']);
    expect(store.instances().map((instance) => instance.id)).toEqual(['widget-mesh-dev-01']);
  });

  it('works with no storage at all — memory only', () => {
    const store = createDevPackageStore(undefined);

    store.register(pkg);

    expect(store.list()).toHaveLength(1);
  });
});

describe('devPackageStore — the pending lifecycle', () => {
  it('a held package is NOT in the catalog until it is committed', () => {
    const store = createDevPackageStore(fakeStorage());

    store.holdPending(pkg);

    expect(store.pending()?.solution).toBe('widget-mesh');
    expect(store.list()).toHaveLength(0);
    expect(store.summaries()).toHaveLength(0);
  });

  it('commit registers it and clears the slot', () => {
    const store = createDevPackageStore(fakeStorage());
    store.holdPending(pkg);

    expect(store.commitPending()?.solution).toBe('widget-mesh');

    expect(store.pending()).toBeUndefined();
    expect(store.list()).toHaveLength(1);
  });

  it('discard drops it, and the catalog never saw it', () => {
    const store = createDevPackageStore(fakeStorage());
    store.holdPending(pkg);

    store.discardPending();

    expect(store.pending()).toBeUndefined();
    expect(store.list()).toHaveLength(0);
    // A later commit has nothing to commit — the abandoned file cannot
    // reappear because the operator opened the dialog again.
    expect(store.commitPending()).toBeUndefined();
    expect(store.list()).toHaveLength(0);
  });

  it('committing with nothing pending is a no-op, not a throw', () => {
    const store = createDevPackageStore(fakeStorage());

    expect(store.commitPending()).toBeUndefined();
    expect(store.list()).toHaveLength(0);
  });

  it('never persists a pending package', () => {
    const storage = fakeStorage();
    const store = createDevPackageStore(storage);

    store.holdPending(pkg);

    expect(storage.raw()).toBeNull();
  });
});

describe('devPackageStore — rehydration', () => {
  it('reads back what a previous session committed', () => {
    const storage = fakeStorage();
    createDevPackageStore(storage).register(pkg);

    const next = createDevPackageStore(storage);

    expect(next.list().map((entry) => entry.solution)).toEqual(['widget-mesh']);
    expect(next.get('widget-mesh')?.components[0]?.fields).toHaveLength(3);
  });

  it('rehydrated packages still produce their cards and instances', () => {
    const storage = fakeStorage();
    createDevPackageStore(storage).register(pkg);

    const next = createDevPackageStore(storage);

    expect(next.summaries()[0]?.id).toBe('widget-mesh');
    expect(next.instanceById('widget-mesh-dev-01')?.solutionId).toBe('widget-mesh');
    expect(next.packageForInstance('widget-mesh-dev-01')?.solution).toBe('widget-mesh');
    expect(next.packageForInstance('cache-prod-01')).toBeUndefined();
  });

  it('does not touch storage until something reads the store', () => {
    let reads = 0;
    const storage: DevPackageStorage = {
      getItem: () => {
        reads += 1;
        return null;
      },
      setItem: () => undefined,
      removeItem: () => undefined,
    };

    createDevPackageStore(storage);
    expect(reads).toBe(0);

    createDevPackageStore(storage).list();
    expect(reads).toBe(1);
  });
});

describe('devPackageStore — corrupt storage never breaks startup', () => {
  it.each([
    ['unparseable JSON', '{ not json'],
    ['a JSON object instead of an array', '{"solution":"widget-mesh"}'],
    ['a JSON scalar', '42'],
    ['an empty string', ''],
  ])('ignores %s', (_label, seed) => {
    const store = createDevPackageStore(fakeStorage(seed));

    expect(store.list()).toEqual([]);
    expect(store.summaries()).toEqual([]);
  });

  it('drops individual entries that no longer validate, keeping the ones that do', () => {
    // The middle entry has been hand-edited into something that is JSON
    // but not a package — exactly the shape a contract change would leave
    // behind between sessions.
    const seed = JSON.stringify([pkg, { solution: 'half-written' }, other]);

    const store = createDevPackageStore(fakeStorage(seed));

    expect(store.list().map((entry) => entry.solution)).toEqual(['widget-mesh', 'other-mesh']);
  });

  it('survives storage that throws on every access', () => {
    const hostile: DevPackageStorage = {
      getItem: () => {
        throw new Error('SecurityError: access is denied for this document');
      },
      setItem: () => {
        throw new Error('QuotaExceededError');
      },
      removeItem: () => undefined,
    };
    const store = createDevPackageStore(hostile);

    expect(store.list()).toEqual([]);
    // A register that cannot persist still holds in memory for this page.
    expect(() => {
      store.register(pkg);
    }).not.toThrow();
    expect(store.list()).toHaveLength(1);
  });
});
