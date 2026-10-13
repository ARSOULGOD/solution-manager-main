import { describe, expect, it } from 'vitest';

import { queryKeys } from './queryKeys';

describe('queryKeys', () => {
  it('produces a stable, identical key for the identical question — two callers dedupe', () => {
    expect(queryKeys.instances.detail('cache-prod-01')).toEqual(
      queryKeys.instances.detail('cache-prod-01'),
    );
  });

  it('produces a different key for a different id — no false dedupe', () => {
    expect(queryKeys.instances.detail('cache-prod-01')).not.toEqual(
      queryKeys.instances.detail('cache-prod-02'),
    );
  });

  it('namespaces every family under its own first element', () => {
    expect(queryKeys.home.summary()[0]).toBe('home');
    expect(queryKeys.metrics.catalog()[0]).toBe('metrics');
    expect(queryKeys.admin.roles()[0]).toBe('admin');
  });

  it('list keys are a prefix of their own family — safe for prefix invalidation', () => {
    const all = queryKeys.instances.all();
    const list = queryKeys.instances.list('cache');
    expect(list.slice(0, all.length)).toEqual(all);
  });
});
