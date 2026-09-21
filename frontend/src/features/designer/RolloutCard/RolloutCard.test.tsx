import { describe, expect, it } from 'vitest';

import { DIVERGED_REPLICAS, ROLLOUT_REPLICAS } from '@shared/testdoubles/data/designer';

import { fireEvent, renderWithProviders, screen } from '../../../test/renderWithProviders';

import { RolloutCard } from './RolloutCard';

describe('RolloutCard', () => {
  it('starts collapsed by default and expands on click, revealing the stepper and table', () => {
    renderWithProviders(
      <RolloutCard replicas={ROLLOUT_REPLICAS} reached="restart-observed" diverged={false} />,
    );
    expect(screen.queryByText('Effective-verified')).toBeNull();
    expect(screen.queryByText(ROLLOUT_REPLICAS[0]!.id)).toBeNull();

    const toggle = screen.getByRole('button', { name: /Rollout & replicas/ });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');

    expect(screen.getByText('Effective-verified')).not.toBeNull();
    expect(screen.getByText(ROLLOUT_REPLICAS[0]!.id)).not.toBeNull();
  });

  it('honours defaultOpen', () => {
    renderWithProviders(
      <RolloutCard
        replicas={ROLLOUT_REPLICAS}
        reached="restart-observed"
        diverged={false}
        defaultOpen
      />,
    );
    expect(screen.getByText('Effective-verified')).not.toBeNull();
  });

  it('shows a neutral replica-count chip when not diverged', () => {
    renderWithProviders(
      <RolloutCard replicas={ROLLOUT_REPLICAS} reached="desired" diverged={false} />,
    );
    expect(screen.getByText('4 replicas')).not.toBeNull();
    expect(screen.queryByText('Diverged')).toBeNull();
  });

  it('shows the Diverged chip instead of a replica count when diverged is true', () => {
    renderWithProviders(
      <RolloutCard replicas={DIVERGED_REPLICAS} reached="restart-observed" diverged />,
    );
    expect(screen.getByText('Diverged')).not.toBeNull();
    expect(screen.queryByText('4 replicas')).toBeNull();
  });
});
