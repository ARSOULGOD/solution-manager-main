import { describe, expect, it } from 'vitest';

import { NAMED_SCREEN_REGISTRY, resolveNamedScreen } from './screenRegistry';

describe('screenRegistry', () => {
  it('stays empty in v1 — D1 vs UI-D21 is a flagged conflict, not resolved, so no package-supplied screen is registered', () => {
    expect(Object.keys(NAMED_SCREEN_REGISTRY)).toHaveLength(0);
  });

  it('resolves every key to undefined — there is no loader here for a package to point at', () => {
    expect(resolveNamedScreen('anything')).toBeUndefined();
    expect(resolveNamedScreen('')).toBeUndefined();
  });
});
