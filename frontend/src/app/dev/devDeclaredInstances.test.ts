/**
 * The declared-instance store's storage posture: what survives a reload,
 * what is refused, and what a hand-edited entry cannot smuggle back in.
 */
import { beforeEach, describe, expect, it } from 'vitest';

import {
  createDevDeclaredInstanceStore,
  DEV_DECLARED_STORAGE_KEY,
  type DevDeclaredStorage,
} from './devDeclaredInstances';

/**
 * A real multi-key store, not a single slot: declarations and removals use
 * two different keys (see the module header), so a one-slot fake silently
 * drops every removal and would make this suite pass a store that cannot
 * persist one.
 */
function fakeStorage(seed?: string): DevDeclaredStorage {
  const map = new Map<string, string>();
  if (seed !== undefined) {
    map.set(DEV_DECLARED_STORAGE_KEY, seed);
  }
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => {
      map.set(key, value);
    },
    removeItem: (key) => {
      map.delete(key);
    },
  };
}

const declaration = {
  name: 'ledger-eu-01',
  packageVersion: 7,
  env: 'TEST',
  org: 'Acme Financial',
  subOrg: 'Payments',
  driver: 'k8s',
  locus: 'host_local',
  replicas: ['ledger-eu-0'],
};

describe('createDevDeclaredInstanceStore', () => {
  let storage: ReturnType<typeof fakeStorage>;

  beforeEach(() => {
    storage = fakeStorage();
  });

  it('registers a declaration and finds it by id and by solution', () => {
    const store = createDevDeclaredInstanceStore(storage);

    const outcome = store.declare('cache', declaration, []);

    expect(outcome.ok).toBe(true);
    expect(store.byId('ledger-eu-01')?.solutionId).toBe('cache');
    expect(store.forSolution('cache')).toHaveLength(1);
    expect(store.forSolution('filemesh')).toHaveLength(0);
  });

  it('rejects a name already taken, whether by a fixture row or a declaration', () => {
    const store = createDevDeclaredInstanceStore(storage);

    expect(store.declare('cache', declaration, ['LEDGER-EU-01'])).toMatchObject({
      ok: false,
      rejection: { code: 'duplicate' },
    });

    store.declare('cache', declaration, []);
    expect(store.declare('cache', declaration, [])).toMatchObject({
      ok: false,
      rejection: { code: 'duplicate' },
    });
  });

  it('rejects a malformed declaration without registering a partial one', () => {
    const store = createDevDeclaredInstanceStore(storage);

    expect(store.declare('cache', { ...declaration, replicas: [] }, [])).toMatchObject({
      ok: false,
      rejection: { code: 'invalid' },
    });
    expect(store.list()).toEqual([]);
  });

  it('survives a reload through storage', () => {
    createDevDeclaredInstanceStore(storage).declare('cache', declaration, []);

    const reloaded = createDevDeclaredInstanceStore(storage);

    expect(reloaded.list().map((row) => row.id)).toEqual(['ledger-eu-01']);
  });

  it('re-derives observed facts on rehydration, so a hand-edited entry cannot assert health', () => {
    const tampered = JSON.stringify([
      { ...declaration, solutionId: 'cache', healthRollup: 'healthy', lastSeen: '3s ago' },
    ]);

    const store = createDevDeclaredInstanceStore(fakeStorage(tampered));

    const row = store.byId('ledger-eu-01');
    expect(row?.healthRollup).toBe('not_applicable');
    expect(row?.lastSeen).toBe('never');
  });

  it('drops entries that no longer validate, keeping the ones that do', () => {
    const mixed = JSON.stringify([
      { ...declaration, solutionId: 'cache' },
      { ...declaration, name: 'Not A Valid Name', solutionId: 'cache' },
      { name: 'orphan-01', solutionId: undefined },
      'not an object',
    ]);

    const store = createDevDeclaredInstanceStore(fakeStorage(mixed));

    expect(store.list().map((row) => row.id)).toEqual(['ledger-eu-01']);
  });

  it.each(['not json at all', '{"not":"an array"}'])('treats %s as no declarations', (raw) => {
    expect(createDevDeclaredInstanceStore(fakeStorage(raw)).list()).toEqual([]);
  });

  describe('undeclare', () => {
    it('drops a declaration made this session', () => {
      const store = createDevDeclaredInstanceStore(storage);
      store.declare('cache', declaration, []);

      expect(store.undeclare('ledger-eu-01')).toBe(true);

      expect(store.list()).toEqual([]);
      expect(store.byId('ledger-eu-01')).toBeUndefined();
    });

    it('TOMBSTONES a fixture id it never declared, so the merged list can exclude it', () => {
      const store = createDevDeclaredInstanceStore(storage);

      expect(store.undeclare('cache-prod-01')).toBe(true);

      // Nothing was added; the id is recorded as removed.
      expect(store.list()).toEqual([]);
      expect(store.removedIds().has('cache-prod-01')).toBe(true);
    });

    it('reports false for an id already removed, so the handler can 404 rather than pretend', () => {
      const store = createDevDeclaredInstanceStore(storage);
      store.undeclare('cache-prod-01');

      expect(store.undeclare('cache-prod-01')).toBe(false);
    });

    it('survives a reload', () => {
      createDevDeclaredInstanceStore(storage).undeclare('cache-prod-01');

      expect(createDevDeclaredInstanceStore(storage).removedIds().has('cache-prod-01')).toBe(true);
    });

    it('re-declaring a removed name LIFTS its tombstone', () => {
      const store = createDevDeclaredInstanceStore(storage);
      store.declare('cache', declaration, []);
      store.undeclare('ledger-eu-01');
      expect(store.removedIds().has('ledger-eu-01')).toBe(true);

      const outcome = store.declare('cache', declaration, []);

      expect(outcome.ok).toBe(true);
      // Otherwise the merged list would filter the row straight back out
      // and the declaration would look like it had silently failed.
      expect(store.removedIds().has('ledger-eu-01')).toBe(false);
    });

    it('clear() forgets removals as well as declarations', () => {
      const store = createDevDeclaredInstanceStore(storage);
      store.undeclare('cache-prod-01');

      store.clear();

      expect(store.removedIds().size).toBe(0);
    });
  });

  it('works with no storage at all, for a non-browser context', () => {
    const store = createDevDeclaredInstanceStore(undefined);

    store.declare('cache', declaration, []);

    expect(store.list()).toHaveLength(1);
  });

  it('never touches storage until something reads it', () => {
    const watched = fakeStorage();
    let reads = 0;
    const counting: DevDeclaredStorage = {
      getItem(key) {
        reads += 1;
        return watched.getItem(key);
      },
      setItem: (key, value) => watched.setItem(key, value),
      removeItem: (key) => watched.removeItem(key),
    };

    createDevDeclaredInstanceStore(counting);
    expect(reads).toBe(0);
  });
});
