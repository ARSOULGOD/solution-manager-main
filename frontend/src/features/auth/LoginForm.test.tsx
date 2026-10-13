import { ThemeProvider } from '@mui/material';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '@shared/api';
import { createCpClient } from '@shared/api/cpClient';
import type { AuthAdapter, Identity } from '@shared/auth/AuthAdapter';
import { AuthProvider } from '@shared/auth/AuthProvider';
import { createSmTheme } from '@shared/theme';

import { LoginForm } from './LoginForm';

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

function renderForm(adapter: AuthAdapter, initialPath = '/login') {
  return render(
    <ThemeProvider theme={createSmTheme('light')}>
      <AuthProvider cpClient={cpClient} adapterOverride={adapter}>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/login" element={<LoginForm />} />
            <Route path="/" element={<div>Home page</div>} />
            <Route path="/solutions" element={<div>Solutions page</div>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </ThemeProvider>,
  );
}

async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/username/i), 'devon.ramirez');
  await user.type(screen.getByLabelText(/^password/i), 'wrong-or-right');
  await user.click(screen.getByRole('button', { name: /sign in/i }));
}

describe('LoginForm', () => {
  it('idle: renders empty username/password fields and an enabled submit button', () => {
    renderForm(fakeAdapter());

    expect(screen.getByLabelText(/username/i)).toHaveValue('');
    expect(screen.getByLabelText(/^password/i)).toHaveValue('');
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled();
  });

  it('submitting: disables the button and swaps its label while the login call is in flight', async () => {
    const user = userEvent.setup();
    let resolveLogin: (identity: Identity) => void = () => {};
    const pending = new Promise<Identity>((resolve) => {
      resolveLogin = resolve;
    });
    renderForm(fakeAdapter({ login: () => pending }));

    await fillAndSubmit(user);

    expect(screen.getByRole('button', { name: 'Signing in…' })).toBeDisabled();
    resolveLogin(IDENTITY);
    await waitFor(() => {
      expect(screen.getByText('Home page')).not.toBeNull();
    });
  });

  it('rejected: shows one message under the password field, marks username invalid without a second message, and never says which field was wrong', async () => {
    const user = userEvent.setup();
    renderForm(fakeAdapter({ login: () => Promise.reject(new ApiError({ status: 401 })) }));

    await fillAndSubmit(user);

    const password = await screen.findByLabelText(/^password/i);
    expect(password).toBeInvalid();
    expect(screen.getByText('Username or password is incorrect.')).not.toBeNull();

    const username = screen.getByLabelText(/username/i);
    expect(username).toBeInvalid();
    // Exactly one message in the whole form — the one under password.
    expect(screen.getAllByText('Username or password is incorrect.')).toHaveLength(1);
  });

  it('does not distinguish a not-found user from a wrong password — both scenarios render the identical text and both fields invalid (account enumeration)', async () => {
    const user = userEvent.setup();

    async function outcomeFor(error: ApiError) {
      const { container, unmount } = renderForm(fakeAdapter({ login: () => Promise.reject(error) }));
      await fillAndSubmit(user);
      await screen.findByText('Username or password is incorrect.');
      const result = {
        text: container.querySelector('form')?.textContent,
        usernameInvalid: screen.getByLabelText(/username/i).getAttribute('aria-invalid'),
        passwordInvalid: screen.getByLabelText(/^password/i).getAttribute('aria-invalid'),
      };
      unmount();
      return result;
    }

    const notFound = await outcomeFor(new ApiError({ status: 404, code: 'USER_NOT_FOUND' }));
    const badPassword = await outcomeFor(new ApiError({ status: 401, code: 'BAD_PASSWORD' }));

    expect(notFound).toEqual(badPassword);
    expect(notFound.usernameInvalid).toBe('true');
    expect(notFound.passwordInvalid).toBe('true');
  });

  it('rateLimited: a 429 with Retry-After renders a distinct message naming the retry window, not the generic rejected copy', async () => {
    const user = userEvent.setup();
    renderForm(
      fakeAdapter({
        login: () =>
          Promise.reject(new ApiError({ status: 429, code: 'RATE_LIMITED', retryAfterSeconds: 30 })),
      }),
    );

    await fillAndSubmit(user);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/too many sign-in attempts/i);
    expect(alert).toHaveTextContent(/30 seconds/i);
    expect(screen.queryByText('Username or password is incorrect.')).toBeNull();
  });

  it('rateLimited: a Retry-After of exactly 1 uses the singular "1 second", not "1 seconds"', async () => {
    const user = userEvent.setup();
    renderForm(
      fakeAdapter({
        login: () =>
          Promise.reject(new ApiError({ status: 429, code: 'RATE_LIMITED', retryAfterSeconds: 1 })),
      }),
    );

    await fillAndSubmit(user);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Wait 1 second, then try again.');
    expect(alert).not.toHaveTextContent('1 seconds');
  });

  it('unreachable: a non-ApiError failure (e.g. a network error) renders the "can\'t reach" banner, not the rejected copy', async () => {
    const user = userEvent.setup();
    renderForm(fakeAdapter({ login: () => Promise.reject(new TypeError('Failed to fetch')) }));

    await fillAndSubmit(user);

    expect(await screen.findByRole('alert')).toHaveTextContent(/can.t reach solution manager/i);
    expect(screen.queryByText('Username or password is incorrect.')).toBeNull();
  });

  it('a duplicate submit while already submitting is a no-op (in-flight guard)', async () => {
    const user = userEvent.setup();
    const login = vi.fn(() => new Promise<Identity>(() => {}));
    renderForm(fakeAdapter({ login }));

    await fillAndSubmit(user);
    // Button is now disabled, but fire a second submit attempt directly to
    // prove the ref guard — not just the disabled attribute — blocks it.
    const form = screen.getByRole('button', { name: 'Signing in…' }).closest('form');
    expect(form).not.toBeNull();
    form?.requestSubmit();

    expect(login).toHaveBeenCalledTimes(1);
  });

  it('a successful login navigates to the "from" URL when present, else home', async () => {
    const user = userEvent.setup();
    renderForm(fakeAdapter(), '/login?from=%2Fsolutions');

    await fillAndSubmit(user);

    expect(await screen.findByText('Solutions page')).not.toBeNull();
  });
});
