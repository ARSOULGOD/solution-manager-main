import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../test/renderWithProviders';

import { LoginPage } from './LoginPage';

describe('LoginPage', () => {
  it('renders the sign-in heading, the form, and the footer with the real configured idle minutes', () => {
    renderWithProviders(<LoginPage idleTimeoutMinutes={45} />, { route: '/login' });

    expect(screen.getByRole('heading', { name: 'Sign in' })).not.toBeNull();
    expect(screen.getByLabelText(/username/i)).not.toBeNull();
    expect(screen.getByText(/no self-service sign-up/i)).not.toBeNull();
    expect(screen.getByText(/sessions end after 45 minutes of inactivity/i)).not.toBeNull();
  });

  it('renders the session-expired arrival alert from ?arrival=session', () => {
    renderWithProviders(<LoginPage idleTimeoutMinutes={30} />, {
      route: '/login?arrival=session',
    });

    expect(screen.getByText('Your session ended')).not.toBeNull();
  });

  it('renders the idle-timeout arrival alert from ?arrival=idle, with the real minutes', () => {
    renderWithProviders(<LoginPage idleTimeoutMinutes={30} />, { route: '/login?arrival=idle' });

    expect(screen.getByText('Signed out after 30 minutes idle')).not.toBeNull();
  });

  it('ignores an unrecognised ?arrival value rather than rendering a broken alert', () => {
    renderWithProviders(<LoginPage idleTimeoutMinutes={30} />, {
      route: '/login?arrival=bogus',
    });

    expect(screen.queryByText('Your session ended')).toBeNull();
    expect(screen.queryByText(/signed out after/i)).toBeNull();
  });

  it('the card background is mode-reactive, never the frozen light-only token (task 111)', () => {
    const { container: light } = renderWithProviders(
      <LoginPage idleTimeoutMinutes={30} />,
      { route: '/login', mode: 'light' },
    );
    const lightCard = light.querySelector('.MuiPaper-root');
    expect(lightCard).toBeInstanceOf(HTMLElement);

    const { container: dark } = renderWithProviders(<LoginPage idleTimeoutMinutes={30} />, {
      route: '/login',
      mode: 'dark',
    });
    const darkCard = dark.querySelector('.MuiPaper-root');
    expect(darkCard).toBeInstanceOf(HTMLElement);

    if (!(lightCard instanceof HTMLElement) || !(darkCard instanceof HTMLElement)) {
      return;
    }
    // The frozen-token bug (`tokens.semantic.bg1`) rendered `#fff` in both
    // modes — asserting the two backgrounds differ is exactly the
    // regression check for that bug re-appearing.
    expect(getComputedStyle(darkCard).backgroundColor).not.toBe(
      getComputedStyle(lightCard).backgroundColor,
    );
    expect(getComputedStyle(darkCard).borderColor).not.toBe('');
  });
});
