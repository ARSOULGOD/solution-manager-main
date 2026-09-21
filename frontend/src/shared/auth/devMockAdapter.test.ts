import { describe, expect, it } from 'vitest';

import { createDevMockAdapter } from './devMockAdapter';

// Vitest runs with import.meta.env.DEV === true (confirmed elsewhere in the
// repo, e.g. router.test.tsx), so the guard's happy path is what these
// tests exercise; the throwing branch is defence-in-depth documented in
// the module header, not independently testable from inside a DEV run.
describe('createDevMockAdapter', () => {
  it('resolves a fixed identity without any network call', async () => {
    const adapter = createDevMockAdapter();
    const identity = await adapter.getIdentity();

    expect(identity.name).toBe('Devon Ramirez');
    expect(identity.superAdmin).toBe(true);
  });

  it('login() accepts anything and resolves the same fixed identity', async () => {
    const adapter = createDevMockAdapter();
    const identity = await adapter.login({ username: 'anyone', password: 'anything' });

    expect(identity.name).toBe('Devon Ramirez');
  });

  it('getAccessToken() is always undefined — no fallback token in DEV mock', () => {
    const adapter = createDevMockAdapter();
    expect(adapter.getAccessToken?.()).toBeUndefined();
  });

  it('onSessionExpired() never fires — DEV work is not interrupted by a fabricated 401', () => {
    const adapter = createDevMockAdapter();
    let fired = false;
    const unsubscribe = adapter.onSessionExpired(() => {
      fired = true;
    });

    unsubscribe();

    expect(fired).toBe(false);
  });
});
