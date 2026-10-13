import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { DIVERGED_REPLICAS } from '@shared/testdoubles/data/designer';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import { RolloutCard } from '../RolloutCard';

import { DivergedBanner } from './DivergedBanner';

describe('DivergedBanner', () => {
  it('states the diverged condition and offers exactly three remediations, no fourth', () => {
    renderWithProviders(
      <DivergedBanner
        onRestage={() => undefined}
        onRestart={() => undefined}
        onRollback={() => undefined}
      />,
    );

    expect(screen.getByText('Configuration diverged')).not.toBeNull();
    expect(screen.getByText(/Pick one remediation — there is no "force healthy"/)).not.toBeNull();

    const buttons = screen.getAllByRole('button');
    expect(buttons).toHaveLength(3);
  });

  it('offers no "force healthy" button — the phrase appears only in the explanatory copy', () => {
    renderWithProviders(
      <DivergedBanner
        onRestage={() => undefined}
        onRestart={() => undefined}
        onRollback={() => undefined}
      />,
    );

    expect(screen.queryByRole('button', { name: /force healthy/i })).toBeNull();
    const buttonNames = screen.getAllByRole('button').map((button) => button.textContent);
    expect(buttonNames).toEqual([
      'Re-stage everywhere',
      'Restart the remaining',
      'Roll back to last uniform generation…',
    ]);
  });

  it('each button fires only its own callback', () => {
    const onRestage = vi.fn();
    const onRestart = vi.fn();
    const onRollback = vi.fn();
    renderWithProviders(
      <DivergedBanner onRestage={onRestage} onRestart={onRestart} onRollback={onRollback} />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Re-stage everywhere' }));
    expect(onRestage).toHaveBeenCalledTimes(1);
    expect(onRestart).not.toHaveBeenCalled();
    expect(onRollback).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Restart the remaining' }));
    expect(onRestart).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: 'Roll back to last uniform generation…' }));
    expect(onRollback).toHaveBeenCalledTimes(1);
    expect(onRestage).toHaveBeenCalledTimes(1);
    expect(onRestart).toHaveBeenCalledTimes(1);
  });

  it('renders alongside the replica table (RolloutCard) without hiding it — a banner above, never a replacement', () => {
    renderWithProviders(
      <>
        <DivergedBanner
          onRestage={() => undefined}
          onRestart={() => undefined}
          onRollback={() => undefined}
        />
        <RolloutCard replicas={DIVERGED_REPLICAS} reached="restart-observed" diverged defaultOpen />
      </>,
    );

    expect(screen.getByText('Configuration diverged')).not.toBeNull();
    expect(screen.getByRole('button', { name: /Rollout & replicas/ })).not.toBeNull();
    for (const replica of DIVERGED_REPLICAS) {
      expect(screen.getByText(replica.id)).not.toBeNull();
    }
  });
});
