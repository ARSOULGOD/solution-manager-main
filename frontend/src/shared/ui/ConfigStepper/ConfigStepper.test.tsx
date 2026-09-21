import { describe, expect, it } from 'vitest';

import type { ConfigLadderReachedKey } from '@shared/vocab';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { ConfigStepper } from './ConfigStepper';

const REACHABLE_KEYS: readonly ConfigLadderReachedKey[] = [
  'desired',
  'rendered',
  'persisted',
  'restart-observed',
];

function effectiveVerifiedItem(): Element {
  const item = screen
    .getAllByRole('listitem')
    .find((candidate) => candidate.getAttribute('aria-label')?.startsWith('Effective-verified'));
  if (item === undefined) {
    throw new Error('effective-verified list item not found');
  }
  return item;
}

describe('ConfigStepper', () => {
  it('renders all five rungs, including effective-verified, regardless of what is reached', () => {
    renderWithProviders(<ConfigStepper reached="desired" />);
    expect(screen.getByText('Desired')).not.toBeNull();
    expect(screen.getByText('Rendered')).not.toBeNull();
    expect(screen.getByText('Persisted')).not.toBeNull();
    expect(screen.getByText('Restart-observed')).not.toBeNull();
    expect(screen.getByText('Effective-verified')).not.toBeNull();
  });

  it.each(REACHABLE_KEYS)(
    'never marks effective-verified done or current when reached is %s — from every fixture in the suite',
    (reached) => {
      renderWithProviders(<ConfigStepper reached={reached} />);
      const label = effectiveVerifiedItem().getAttribute('aria-label');
      expect(label).toBe('Effective-verified — pending');
    },
  );

  it('marks the reached rung current, and every rung before it done', () => {
    renderWithProviders(<ConfigStepper reached="persisted" />);
    expect(screen.getByRole('listitem', { name: 'Desired — done' })).not.toBeNull();
    expect(screen.getByRole('listitem', { name: 'Rendered — done' })).not.toBeNull();
    expect(screen.getByRole('listitem', { name: 'Persisted — current' })).not.toBeNull();
    expect(screen.getByRole('listitem', { name: 'Restart-observed — pending' })).not.toBeNull();
  });

  it('states the restart-observed ≠ effective-verified caveat and names APPLY_INDETERMINATE', () => {
    renderWithProviders(<ConfigStepper reached="restart-observed" />);
    expect(screen.getByText(/APPLY_INDETERMINATE/)).not.toBeNull();
  });
});
