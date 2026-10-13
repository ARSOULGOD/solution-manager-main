import { describe, expect, it } from 'vitest';

import { healthVocab, notFoundVocab, versionVocab, type ConfigStateKey } from '@shared/vocab';

import { ATTENTION_FEED, CACHE_INSTANCES } from './instances';

describe('CACHE_INSTANCES', () => {
  it('has exactly twelve instances (§A.13.2)', () => {
    expect(CACHE_INSTANCES).toHaveLength(12);
  });

  it('every health vocab key is reachable from at least one instance or replica', () => {
    const seen = new Set<string>();
    for (const instance of CACHE_INSTANCES) {
      if (instance.healthRollup !== 'notfound') {
        seen.add(instance.healthRollup);
      }
      for (const replica of instance.replicas) {
        seen.add(replica.health);
      }
    }
    for (const key of Object.keys(healthVocab)) {
      expect(seen.has(key)).toBe(true);
    }
  });

  it('every genuinely-distinct not-found reason is reachable from at least one instance', () => {
    // `restart_expected` is excluded here deliberately: in the reference
    // (`notFound.jsx`'s `restart_expected: HEALTH.restart_expected`) it is
    // the SAME state as the health vocab's own `restart_expected`, just
    // referenced from the not-found table too — the ported 12-instance set
    // (cache-stage-02) represents it via the health rollup, matching the
    // reference's own fixture exactly, never via `notFoundReason`.
    const seen = new Set<string | undefined>(
      CACHE_INSTANCES.filter((i) => i.healthRollup === 'notfound').map((i) => i.notFoundReason),
    );
    for (const key of Object.keys(notFoundVocab)) {
      if (key === 'restart_expected') continue;
      expect(seen.has(key)).toBe(true);
    }
  });

  it('restart_expected is reachable via the health rollup (the same state, reference-aliased)', () => {
    expect(CACHE_INSTANCES.some((i) => i.healthRollup === 'restart_expected')).toBe(true);
  });

  it('every version state is reachable from at least one instance', () => {
    const seen = new Set<string>(CACHE_INSTANCES.map((i) => i.version.state));
    for (const key of Object.keys(versionVocab)) {
      expect(seen.has(key)).toBe(true);
    }
  });

  it('every config state is reachable from at least one instance', () => {
    const seen = new Set<ConfigStateKey>(CACHE_INSTANCES.map((i) => i.configState));
    const expected: ConfigStateKey[] = ['in_sync', 'staged', 'rendered', 'diverged', 'drift', 'na'];
    for (const key of expected) {
      expect(seen.has(key)).toBe(true);
    }
  });

  it('a never-observed (bootstrap) instance has lastSeen "never", not a fabricated timestamp', () => {
    const bootstrap = CACHE_INSTANCES.find((i) => i.notFoundReason === 'bootstrap');
    expect(bootstrap?.lastSeen).toBe('never');
  });

  it('every instance id is unique', () => {
    const ids = CACHE_INSTANCES.map((i) => i.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});

describe('ATTENTION_FEED', () => {
  it('every item references a real instance and a same-solution href', () => {
    const instanceIds = new Set(CACHE_INSTANCES.map((i) => i.id));
    for (const item of ATTENTION_FEED) {
      expect(instanceIds.has(item.instanceId)).toBe(true);
      expect(item.href).toContain('/solutions/cache/instances');
    }
  });

  it('uses only the three severities the reference draws', () => {
    for (const item of ATTENTION_FEED) {
      expect(['err', 'warn', 'info']).toContain(item.severity);
    }
  });
});
