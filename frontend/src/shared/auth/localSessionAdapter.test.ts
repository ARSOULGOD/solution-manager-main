import { HttpResponse, http } from 'msw';
import { describe, expect, it, vi } from 'vitest';

import type { CsrfConfig, PlaneClient } from '@shared/api';
import { createCpClient } from '@shared/api/cpClient';
import { server } from '@shared/testdoubles/server';

import type { Identity } from './AuthAdapter';
import { createLocalSessionAdapter } from './localSessionAdapter';

// The wire shape the backend actually returns (verified live 2026-10-08,
// docs/plans/integration/reports/real-e2e-2026-10-08.md) — lowercase
// source, scoped permission entries, organizations[] (not a flat
// organizationId), no role/superAdmin field of its own.
const WIRE_IDENTITY = {
  principalId: 'p1',
  username: 'devon.ramirez',
  source: 'local' as const,
  mustRotate: false,
  // `{}` (unscoped) on `target.use` makes this identity superAdmin — any
  // one unscoped grant is the signal (OR-12), not a minimum count.
  permissions: [{ permission: 'target.use', scopes: [{}] }],
  csrfToken: 'tok',
  organizations: [{ id: 'org-1', name: 'ACME' }],
};

// What the adapter must map WIRE_IDENTITY to.
const IDENTITY: Identity = {
  username: 'devon.ramirez',
  source: 'local',
  mustRotate: false,
  superAdmin: true,
  organizationNames: ['ACME'],
  permissions: ['target.use'],
};

function fakeCpClient(overrides: Partial<PlaneClient> = {}): PlaneClient {
  return {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
    ...overrides,
  } as PlaneClient;
}

describe('createLocalSessionAdapter', () => {
  it('posts the credential under the "credential" field, not "password"', async () => {
    const post = vi.fn().mockResolvedValue({
      principalId: 'p1',
      username: 'operator',
      source: 'local',
      mustRotate: false,
      permissions: [],
      csrfToken: 'tok',
      organizations: [],
    });
    const adapter = createLocalSessionAdapter(fakeCpClient({ post }));

    await adapter.login({ username: 'operator', password: 'secret' });

    expect(post).toHaveBeenCalledWith('/auth/login', { username: 'operator', credential: 'secret' });
  });

  it('flattens scoped permissions to a flat id list and passes through organization names', async () => {
    const post = vi.fn().mockResolvedValue({
      principalId: 'p1', username: 'operator', source: 'local', mustRotate: false,
      // Every grant here is scope-bound (a real scope level, never `{}`) —
      // this identity is NOT superAdmin. The unscoped-grant case is its
      // own dedicated test below.
      permissions: [
        { permission: 'instance.view', scopes: [{ organizationId: 'org-1' }] },
        { permission: 'config.edit', scopes: [{ environmentId: 'env-1' }, { environmentId: 'env-2' }] },
      ],
      csrfToken: 'tok', organizations: [{ id: 'org-1', name: 'ACME' }],
    });
    const adapter = createLocalSessionAdapter(fakeCpClient({ post }));

    const identity = await adapter.login({ username: 'operator', password: 'secret' });

    expect(identity).toMatchObject({
      username: 'operator',
      source: 'local',
      mustRotate: false,
      superAdmin: false,
      organizationNames: ['ACME'],
      permissions: ['instance.view', 'config.edit'],
    });
  });

  it('superAdmin is true when ANY permission is held at an unscoped ({}) grant', async () => {
    const post = vi.fn().mockResolvedValue({
      principalId: 'p1', username: 'operator', source: 'local', mustRotate: false,
      permissions: [
        { permission: 'instance.view', scopes: [{ organizationId: 'org-1' }] },
        { permission: 'audit.view', scopes: [{}] },
      ],
      csrfToken: 'tok', organizations: [{ id: 'org-1', name: 'ACME' }],
    });
    const adapter = createLocalSessionAdapter(fakeCpClient({ post }));

    const identity = await adapter.login({ username: 'operator', password: 'secret' });

    expect(identity.superAdmin).toBe(true);
  });

  it('getIdentity() returns the server identity, including the display-only organization names', async () => {
    server.use(http.get('*/api/cp/auth/identity', () => HttpResponse.json(WIRE_IDENTITY)));
    const adapter = createLocalSessionAdapter(createCpClient('/api'));

    await expect(adapter.getIdentity()).resolves.toEqual(IDENTITY);
  });

  it('login() never sends the organization as an authoritative request parameter', async () => {
    let sentBody: unknown;
    server.use(
      http.post('*/api/cp/auth/login', async ({ request: req }) => {
        sentBody = await req.json();
        return HttpResponse.json(WIRE_IDENTITY);
      }),
    );
    const adapter = createLocalSessionAdapter(createCpClient('/api'));

    await adapter.login({ username: 'devon.ramirez', password: 'secret' });

    expect(sentBody).toEqual({ username: 'devon.ramirez', credential: 'secret' });
    expect(JSON.stringify(sentBody)).not.toContain('organization');
  });

  it('getAccessToken() is undefined until a login response carries one (cookie-session default)', async () => {
    server.use(http.post('*/api/cp/auth/login', () => HttpResponse.json(WIRE_IDENTITY)));
    const adapter = createLocalSessionAdapter(createCpClient('/api'));

    expect(adapter.getAccessToken?.()).toBeUndefined();
    await adapter.login({ username: 'devon.ramirez', password: 'secret' });
    expect(adapter.getAccessToken?.()).toBeUndefined();
  });

  it('getAccessToken() always returns undefined regardless of response content', async () => {
    server.use(
      http.post('*/api/cp/auth/login', () =>
        HttpResponse.json({ ...WIRE_IDENTITY, accessToken: 'fallback-token-abc' }),
      ),
    );
    const adapter = createLocalSessionAdapter(createCpClient('/api'));

    await adapter.login({ username: 'devon.ramirez', password: 'secret' });

    expect(adapter.getAccessToken?.()).toBeUndefined();
  });

  it('logout() calls the backend logout endpoint', async () => {
    server.use(
      http.post('*/api/cp/auth/login', () => HttpResponse.json(WIRE_IDENTITY)),
      http.post('*/api/cp/auth/logout', () => new HttpResponse(null, { status: 204 })),
    );
    const adapter = createLocalSessionAdapter(createCpClient('/api'));
    await adapter.login({ username: 'devon.ramirez', password: 'secret' });

    await adapter.logout();

    expect(adapter.getAccessToken?.()).toBeUndefined();
  });

  it('onSessionExpired() fires when ANY request comes back 401', async () => {
    server.use(
      http.get('*/api/cp/auth/identity', () =>
        HttpResponse.json({ code: 'UNAUTHORIZED' }, { status: 401 }),
      ),
    );
    const adapter = createLocalSessionAdapter(createCpClient('/api'));
    const listener = vi.fn();
    adapter.onSessionExpired(listener);

    await expect(adapter.getIdentity()).rejects.toThrow();

    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('unsubscribe stops the listener from firing again', async () => {
    server.use(
      http.get('*/api/cp/auth/identity', () =>
        HttpResponse.json({ code: 'UNAUTHORIZED' }, { status: 401 }),
      ),
    );
    const adapter = createLocalSessionAdapter(createCpClient('/api'));
    const listener = vi.fn();
    const unsubscribe = adapter.onSessionExpired(listener);
    unsubscribe();

    await expect(adapter.getIdentity()).rejects.toThrow();

    expect(listener).not.toHaveBeenCalled();
  });

  it('configures CSRF with the backend header name and reads the token from the last identity/login response', async () => {
    const post = vi.fn().mockResolvedValue({
      principalId: 'p1', username: 'operator', source: 'local', mustRotate: false,
      permissions: [], csrfToken: 'first-token', organizations: [],
    });
    const get = vi.fn().mockResolvedValue({
      principalId: 'p1', username: 'operator', source: 'local', mustRotate: false,
      permissions: [{ permission: 'target.use', scopes: [{}] }],
      organizations: [{ id: 'org1', name: 'ACME' }], csrfToken: 'second-token',
    });
    const configureCsrf = vi.fn();
    const adapter = createLocalSessionAdapter(fakeCpClient({ post, get }), configureCsrf);

    await adapter.login({ username: 'operator', password: 'secret' });

    expect(configureCsrf).toHaveBeenCalledWith(
      expect.objectContaining({
        headerName: 'X-SM-CSRF',
        getToken: expect.any(Function) as unknown as CsrfConfig['getToken'],
      }),
    );
    const call = configureCsrf.mock.calls[0]?.[0] as CsrfConfig | undefined;
    const getToken = call?.getToken;
    expect(getToken).toBeDefined();
    expect(getToken?.()).toBe('first-token');

    // Verify token rotates on subsequent getIdentity() call (not captured-once at init time)
    await adapter.getIdentity();
    expect(getToken?.()).toBe('second-token');
  });

  it('keeps the global CSRF hook pointed at the adapter that is actually in use when a second adapter is constructed afterwards (React StrictMode double-invokes useMemo)', async () => {
    const wire = {
      principalId: 'p1', username: 'operator', source: 'local', mustRotate: false,
      permissions: [], csrfToken: 'live-token', organizations: [],
    };
    let active: CsrfConfig = {};
    const configureCsrf = (config: CsrfConfig) => {
      active = config;
    };
    const inUse = createLocalSessionAdapter(fakeCpClient({ post: vi.fn().mockResolvedValue(wire) }), configureCsrf);
    // The discarded StrictMode twin: constructed last, never used.
    createLocalSessionAdapter(fakeCpClient({}), configureCsrf);

    await inUse.login({ username: 'operator', password: 'secret' });

    expect(active.getToken?.()).toBe('live-token');
  });

  it('posts current/new credential to /auth/credential on rotateCredential', async () => {
    const post = vi.fn().mockResolvedValue(undefined);
    const adapter = createLocalSessionAdapter(fakeCpClient({ post }));

    await adapter.rotateCredential('old-secret', 'new-secret-12chars');

    expect(post).toHaveBeenCalledWith('/auth/credential', {
      currentCredential: 'old-secret',
      newCredential: 'new-secret-12chars',
    });
  });
});
