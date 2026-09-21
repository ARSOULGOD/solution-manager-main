import { describe, expect, it, vi } from 'vitest';

import { SOLUTIONS } from '@shared/testdoubles/data/solutions';

import { fireEvent, renderWithProviders, screen } from '../../../test/renderWithProviders';

import { SolutionCard } from './SolutionCard';

const SIGNED = SOLUTIONS.find((s) => s.id === 'cache')!;
const UNSIGNED = SOLUTIONS.find((s) => s.id === 'cache-nightly')!;

describe('SolutionCard', () => {
  it('renders provenance, version, applies-to and components for a signed package', () => {
    renderWithProviders(<SolutionCard solution={SIGNED} onOpen={() => undefined} />);

    expect(screen.getByText('Signed · Solace')).not.toBeNull();
    // Every version the one package file serves, joined (FE-118).
    expect(screen.getByText('6 · 7')).not.toBeNull();
    expect(screen.getByText('>=2.2 <2.5')).not.toBeNull();
    expect(screen.getByText('cache-engine')).not.toBeNull();
  });

  it('calls onOpen with the solution id when a signed card\'s Open button is clicked', () => {
    const onOpen = vi.fn();
    renderWithProviders(<SolutionCard solution={SIGNED} onOpen={onOpen} />);

    fireEvent.click(screen.getByRole('button', { name: 'Open' }));

    expect(onOpen).toHaveBeenCalledWith('cache');
  });

  // Vitest always sets `import.meta.env.DEV = true` (see `devMockAdapter.ts`'s
  // own header for the same note), so this exercises exactly the console-in-
  // dev-mode branch — an unsigned package is openable, matching the
  // `LoadPackageDialog`'s own admission rule and closing the gap
  // task-114-report.md flagged ("the unsigned path... [was] catalog-only").
  // The `!import.meta.env.DEV` branch (blocked outside a dev console) is a
  // one-line boolean short-circuit, correct by inspection — same posture
  // `LoadPackageDialog.tsx`'s header comment already takes for its own
  // untestable-in-Vitest half of the identical condition.
  it('calls onOpen for an unsigned card when the console is running in dev mode', () => {
    const onOpen = vi.fn();
    renderWithProviders(<SolutionCard solution={UNSIGNED} onOpen={onOpen} />);

    const button = screen.getByRole('button', { name: 'Open' });
    expect(button.hasAttribute('disabled')).toBe(false);

    fireEvent.click(button);

    expect(onOpen).toHaveBeenCalledWith('cache-nightly');
  });

  it('renders the unsigned dev-mode banner and "Unsigned · dev-mode" provenance', () => {
    renderWithProviders(<SolutionCard solution={UNSIGNED} onOpen={() => undefined} />);

    expect(
      screen.getByText('Unsigned · development mode — not trusted like a signed package'),
    ).not.toBeNull();
    expect(screen.getByText('Unsigned · dev-mode')).not.toBeNull();
    expect(screen.queryByText('Signed · Solace')).toBeNull();
  });
});
