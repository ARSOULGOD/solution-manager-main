import { describe, expect, it, vi } from 'vitest';

import { fireEvent, renderWithProviders, screen } from '../../../test/renderWithProviders';

import { SolutionCard } from './SolutionCard';
import type { SolutionCatalogItem } from './solutionsRepository';

const CACHE: SolutionCatalogItem = {
  solutionId: 'cache',
  solution: 'cache',
  name: 'Cache Engine',
  packageVersions: [6, 7],
  latestPackageVersion: 7,
  signature: 'unsigned',
  stateVersion: 1,
};

describe('SolutionCard', () => {
  it('renders only the fields `Solution` declares — name, solution, versions, signature', () => {
    renderWithProviders(<SolutionCard solution={CACHE} onOpen={() => undefined} />);

    expect(screen.getByText('Cache Engine')).not.toBeNull();
    expect(screen.getByText('cache')).not.toBeNull();
    // Every version the one package file serves, each its own chip (FE-118),
    // the latest one labelled rather than only colour-distinguished.
    expect(screen.getByText('6')).not.toBeNull();
    expect(screen.getByText('7 · latest')).not.toBeNull();
    expect(screen.getByText('Unsigned (v1 loads no signed packages)')).not.toBeNull();
  });

  it("calls onOpen with the solution id when the card's Open button is clicked", () => {
    const onOpen = vi.fn();
    renderWithProviders(<SolutionCard solution={CACHE} onOpen={onOpen} />);

    fireEvent.click(screen.getByRole('button', { name: 'Open' }));

    expect(onOpen).toHaveBeenCalledWith('cache');
  });
});
