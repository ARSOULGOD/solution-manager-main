import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../test/renderWithProviders';

import { LoginArrivalAlert } from './LoginArrivalAlert';

describe('LoginArrivalAlert', () => {
  it('renders nothing for a plain arrival (no query-string reason)', () => {
    const { container } = renderWithProviders(
      <LoginArrivalAlert arrival={null} idleTimeoutMinutes={30} />,
    );

    expect(container.textContent).toBe('');
  });

  it('session: a warn alert stating the server no longer recognised the session', () => {
    renderWithProviders(<LoginArrivalAlert arrival="session" idleTimeoutMinutes={30} />);

    expect(screen.getByText('Your session ended')).not.toBeNull();
    expect(screen.getByText(/no longer recognised your session/i)).not.toBeNull();
  });

  it('idle: an info alert stating the real configured idle-timeout minutes, not a hardcoded number', () => {
    renderWithProviders(<LoginArrivalAlert arrival="idle" idleTimeoutMinutes={45} />);

    expect(screen.getByText('Signed out after 45 minutes idle')).not.toBeNull();
    expect(screen.getByText(/your in-progress configuration edits were saved/i)).not.toBeNull();
  });

  it('idle: singular minute count uses the singular form', () => {
    renderWithProviders(<LoginArrivalAlert arrival="idle" idleTimeoutMinutes={1} />);

    expect(screen.getByText('Signed out after 1 minute idle')).not.toBeNull();
  });
});
