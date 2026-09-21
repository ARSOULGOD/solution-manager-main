import { HttpResponse, http } from 'msw';
import { describe, expect, it, vi } from 'vitest';

import { createCpClient } from '@shared/api/cpClient';
import { server } from '@shared/testdoubles/server';

import type { Identity } from './AuthAdapter';
import { createLocalSessionAdapter } from './localSessionAdapter';

const IDENTITY: Identity = {
  name: 'Devon Ramirez',
  email: 'devon.ramirez@acme.example',
  role: 'Operator',
  organization: 'Acme Financial · self-hosted',
  superAdmin: true,
  permissions: ['target.use'],
};

describe('createLocalSessionAdapter', () => {
  it('getIdentity() returns the server identity, including the display-only organization', async () => {
    server.use(http.get('*/api/cp/auth/identity', () => HttpResponse.json(IDENTITY)));
    const adapter = createLocalSessionAdapter(createCpClient('/api'));

    await expect(adapter.getIdentity()).resolves.toEqual(IDENTITY);
  });

  it('login() never sends the organization as an authoritative request parameter', async () => {
    let sentBody: unknown;
    server.use(
      http.post('*/api/cp/auth/login', async ({ request: req }) => {
        sentBody = await req.json();
        return HttpResponse.json(IDENTITY);
      }),
    );
    const adapter = createLocalSessionAdapter(createCpClient('/api'));

    await adapter.login({ username: 'devon.ramirez', password: 'secret' });

    expect(sentBody).toEqual({ username: 'devon.ramirez', password: 'secret' });
    expect(JSON.stringify(sentBody)).not.toContain('organization');
  });

  it('getAccessToken() is undefined until a login response carries one (cookie-session default)', async () => {
    server.use(http.post('*/api/cp/auth/login', () => HttpResponse.json(IDENTITY)));
    const adapter = createLocalSessionAdapter(createCpClient('/api'));

    expect(adapter.getAccessToken?.()).toBeUndefined();
    await adapter.login({ username: 'devon.ramirez', password: 'secret' });
    expect(adapter.getAccessToken?.()).toBeUndefined();
  });

  it('getAccessToken() holds the in-memory fallback token when the response carries one', async () => {
    server.use(
      http.post('*/api/cp/auth/login', () =>
        HttpResponse.json({ ...IDENTITY, accessToken: 'fallback-token-abc' }),
      ),
    );
    const adapter = createLocalSessionAdapter(createCpClient('/api'));

    await adapter.login({ username: 'devon.ramirez', password: 'secret' });

    expect(adapter.getAccessToken?.()).toBe('fallback-token-abc');
  });

  it('logout() clears the in-memory access token', async () => {
    server.use(
      http.post('*/api/cp/auth/login', () =>
        HttpResponse.json({ ...IDENTITY, accessToken: 'fallback-token-abc' }),
      ),
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
});
