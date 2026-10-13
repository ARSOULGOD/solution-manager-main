import { ThemeProvider } from '@mui/material';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import { createCpClient } from '@shared/api/cpClient';
import { createSmTheme } from '@shared/theme';

import type { AuthAdapter, Identity } from './AuthAdapter';
import { AuthProvider } from './AuthProvider';
import { RequireAuth } from './RequireAuth';

const IDENTITY: Identity = {
  username: 'devon.ramirez',
  source: 'local',
  mustRotate: false,
  superAdmin: true,
  organizationNames: ['ACME'],
  permissions: ['target.use'],
};

const cpClient = createCpClient('/api');

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

function renderGuarded(
  adapter: AuthAdapter,
  { initialPath = '/protected/deep', idleTimeoutMinutes = 30 } = {},
) {
  return render(
    <ThemeProvider theme={createSmTheme('light')}>
      <AuthProvider cpClient={cpClient} adapterOverride={adapter}>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route
              path="/protected/*"
              element={
                <RequireAuth idleTimeoutMinutes={idleTimeoutMinutes}>
                  <div data-testid="protected-page">protected content</div>
                </RequireAuth>
              }
            />
            <Route path="/login" element={<LoginPageWithSearch />} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </ThemeProvider>,
  );
}

// `window.location` in jsdom is not reliable across MemoryRouter
// navigations — read the current location's search via react-router's own
// hook instead.
function LoginPageWithSearch() {
  const location = useLocation();
  return <div data-testid="login-page">login page · {location.search}</div>;
}

describe('RequireAuth', () => {
  it('renders children once authenticated', async () => {
    renderGuarded(fakeAdapter());

    await waitFor(() => expect(screen.getByTestId('protected-page')).toBeInTheDocument());
  });

  it('redirects to /login when unauthenticated, preserving the attempted URL', async () => {
    renderGuarded(fakeAdapter({ getIdentity: () => Promise.reject(new Error('no session')) }));

    await waitFor(() => expect(screen.getByTestId('login-page')).toBeInTheDocument());
    expect(screen.getByTestId('login-page').textContent).toContain('from=%2Fprotected%2Fdeep');
  });

  it('redirects with arrival=session when the adapter reports a session expiry', async () => {
    let expire: () => void = () => {};
    const adapter = fakeAdapter({
      onSessionExpired: (cb) => {
        expire = cb;
        return () => {};
      },
    });
    renderGuarded(adapter);
    await waitFor(() => expect(screen.getByTestId('protected-page')).toBeInTheDocument());

    expire();

    await waitFor(() => expect(screen.getByTestId('login-page')).toBeInTheDocument());
    expect(screen.getByTestId('login-page').textContent).toContain('arrival=session');
  });

  it('redirects with arrival=idle after the idle timeout fires', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const logout = vi.fn(() => Promise.resolve());
    const adapter = fakeAdapter({ logout });
    renderGuarded(adapter, { idleTimeoutMinutes: 1 });
    await waitFor(() => expect(screen.getByTestId('protected-page')).toBeInTheDocument());

    await vi.advanceTimersByTimeAsync(60_000);

    await waitFor(() => expect(screen.getByTestId('login-page')).toBeInTheDocument());
    expect(screen.getByTestId('login-page').textContent).toContain('arrival=idle');
    expect(logout).toHaveBeenCalled();
    vi.useRealTimers();
  });

  it('renders nothing (no redirect flash) while status is loading', () => {
    const adapter = fakeAdapter({ getIdentity: () => new Promise<Identity>(() => {}) });
    renderGuarded(adapter);

    expect(screen.queryByTestId('protected-page')).not.toBeInTheDocument();
    expect(screen.queryByTestId('login-page')).not.toBeInTheDocument();
  });

  it('renders the blocking rotate-credential dialog instead of children when identity.mustRotate is true', async () => {
    const adapter = fakeAdapter({
      getIdentity: () => Promise.resolve({ ...IDENTITY, mustRotate: true }),
    });
    renderGuarded(adapter);

    await waitFor(() => expect(screen.getByText(/update your credential/i)).toBeInTheDocument());
    expect(screen.queryByTestId('protected-page')).not.toBeInTheDocument();
  });

  it('rotating the credential clears mustRotate and reveals the protected page', async () => {
    const rotateCredential = vi.fn().mockResolvedValue(undefined);
    let mustRotate = true;
    const adapter = fakeAdapter({
      getIdentity: () => Promise.resolve({ ...IDENTITY, mustRotate }),
      rotateCredential: async (...args) => {
        await rotateCredential(...args);
        mustRotate = false;
      },
    });
    renderGuarded(adapter);
    await waitFor(() => expect(screen.getByText(/update your credential/i)).toBeInTheDocument());

    await userEvent.type(screen.getByLabelText(/current/i), 'old-secret');
    await userEvent.type(screen.getByLabelText(/new/i), 'new-secret-12chars');
    await userEvent.click(screen.getByRole('button', { name: /update/i }));

    expect(rotateCredential).toHaveBeenCalledWith('old-secret', 'new-secret-12chars');
    await waitFor(() => expect(screen.getByTestId('protected-page')).toBeInTheDocument());
  });
});
