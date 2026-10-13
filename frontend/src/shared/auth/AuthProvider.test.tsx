import { act, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { createCpClient } from '@shared/api/cpClient';

import type { AuthAdapter, Identity, SessionExpiredListener } from './AuthAdapter';
import { AuthProvider, useAuth } from './AuthProvider';

const IDENTITY: Identity = {
  username: 'devon.ramirez',
  source: 'local',
  mustRotate: false,
  superAdmin: true,
  organizationNames: ['ACME'],
  permissions: ['target.use'],
};

function fakeAdapter(overrides: Partial<AuthAdapter> = {}): AuthAdapter {
  return {
    getIdentity: () => Promise.resolve(IDENTITY),
    login: () => Promise.resolve(IDENTITY),
    logout: () => Promise.resolve(),
    rotateCredential: () => Promise.resolve(),
    onSessionExpired: () => () => {},
    getAccessToken: () => undefined,
    ...overrides,
  };
}

function Probe() {
  const { status, identity, arrival } = useAuth();
  return (
    <div>
      <span data-testid="status">{status}</span>
      <span data-testid="identity">{identity?.username ?? 'none'}</span>
      <span data-testid="arrival">{arrival ?? 'none'}</span>
    </div>
  );
}

const cpClient = createCpClient('/api');

describe('AuthProvider', () => {
  it('resolves to authenticated with the server identity on mount', async () => {
    render(
      <AuthProvider cpClient={cpClient} adapterOverride={fakeAdapter()}>
        <Probe />
      </AuthProvider>,
    );

    expect(screen.getByTestId('status').textContent).toBe('loading');
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('authenticated'));
    expect(screen.getByTestId('identity').textContent).toBe('devon.ramirez');
  });

  it('resolves to unauthenticated when getIdentity() rejects', async () => {
    const adapter = fakeAdapter({ getIdentity: () => Promise.reject(new Error('no session')) });

    render(
      <AuthProvider cpClient={cpClient} adapterOverride={adapter}>
        <Probe />
      </AuthProvider>,
    );

    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('unauthenticated'));
    expect(screen.getByTestId('identity').textContent).toBe('none');
  });

  it('flips to unauthenticated with arrival "session" when the adapter reports a session expiry (401)', async () => {
    let expire: SessionExpiredListener = () => {};
    const adapter = fakeAdapter({
      onSessionExpired: (cb) => {
        expire = cb;
        return () => {};
      },
    });

    render(
      <AuthProvider cpClient={cpClient} adapterOverride={adapter}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('authenticated'));

    act(() => {
      expire();
    });

    expect(screen.getByTestId('status').textContent).toBe('unauthenticated');
    expect(screen.getByTestId('arrival').textContent).toBe('session');
    expect(screen.getByTestId('identity').textContent).toBe('none');
  });

  it('does not report a session expiry for the 401 on the initial identity probe (first-time visitor)', async () => {
    let expire: SessionExpiredListener = () => {};
    const adapter = fakeAdapter({
      // The real transport answers asynchronously — after the provider has subscribed — firing the
      // unauthorized listeners and then rejecting.
      getIdentity: async () => {
        await Promise.resolve();
        expire();
        throw new Error('401');
      },
      onSessionExpired: (cb) => {
        expire = cb;
        return () => {};
      },
    });

    render(
      <AuthProvider cpClient={cpClient} adapterOverride={adapter}>
        <Probe />
      </AuthProvider>,
    );

    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('unauthenticated'));
    expect(screen.getByTestId('arrival').textContent).toBe('none');
  });

  it('useAuth() throws when called outside an AuthProvider', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    function Broken() {
      useAuth();
      return null;
    }
    expect(() => render(<Broken />)).toThrow('useAuth() called outside an AuthProvider.');
    spy.mockRestore();
  });
});
