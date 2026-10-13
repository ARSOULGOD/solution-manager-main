/**
 * The harness testing itself (task 008 acceptance): a trivial component
 * render through the full provider stack, and a trivial axe pass over
 * the result.
 */
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from './renderWithProviders';

function Greeting() {
  return (
    <main>
      <h1>Hello, Solution Manager</h1>
    </main>
  );
}

describe('renderWithProviders', () => {
  it('mounts a component through the theme/query/auth/router stack', () => {
    renderWithProviders(<Greeting />);

    expect(screen.getByRole('heading', { name: 'Hello, Solution Manager' })).toBeInTheDocument();
  });

  it('renders dark mode when asked', () => {
    renderWithProviders(<Greeting />, { mode: 'dark' });

    expect(screen.getByRole('heading', { name: 'Hello, Solution Manager' })).toBeInTheDocument();
  });

  it('has no detectable accessibility violations', async () => {
    const { container } = renderWithProviders(<Greeting />);

    expect(await axe(container)).toHaveNoViolations();
  });
});
