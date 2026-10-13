import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { ROLLOUT_REPLICAS } from '@shared/testdoubles/data/designer';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import type { ApplyOutcome } from '../applyRepository';

import { ApplyOutcomePanel } from './ApplyOutcomePanel';

const INDETERMINATE: ApplyOutcome = {
  kind: 'INDETERMINATE',
  reason: 'No generation ordering or idempotency key exists for dp-remote (open item 1).',
  observedAt: '2026-08-24T00:00:00Z',
  definite: false,
  replicas: ROLLOUT_REPLICAS,
};

const DEFINITE_FAILURE: ApplyOutcome = {
  kind: 'INDETERMINATE',
  reason: 'Backend apply state: FAILED_NO_EFFECT. ssh.docker.container.missing',
  observedAt: '2026-08-24T00:00:00Z',
  definite: true,
  replicas: ROLLOUT_REPLICAS,
};

const CONFIRMED: ApplyOutcome = {
  kind: 'CONFIRMED_BY_OPERATOR',
  confirmedAt: '2026-08-24T00:05:00Z',
  replicas: ROLLOUT_REPLICAS,
};

describe('ApplyOutcomePanel', () => {
  it('renders nothing for NOT_STARTED, IN_FLIGHT, or DIVERGED — those are not this task', () => {
    const cases: ApplyOutcome[] = [
      { kind: 'NOT_STARTED' },
      { kind: 'IN_FLIGHT', startedAt: '2026-08-24T00:00:00Z' },
      { kind: 'DIVERGED', replicas: ROLLOUT_REPLICAS },
    ];

    for (const outcome of cases) {
      const { container } = renderWithProviders(
        <ApplyOutcomePanel
          outcome={outcome}
          onConfirm={() => undefined}
          onRetry={() => undefined}
        />,
      );
      expect(container.textContent).toBe('');
    }
  });

  it('INDETERMINATE never claims success or failure — states the server reason and observed time verbatim', () => {
    renderWithProviders(
      <ApplyOutcomePanel
        outcome={INDETERMINATE}
        onConfirm={() => undefined}
        onRetry={() => undefined}
      />,
    );

    expect(screen.getByText('Outcome not confirmed')).not.toBeNull();
    expect(screen.queryByText('Success')).toBeNull();
    expect(screen.queryByText('Failed')).toBeNull();
    expect(
      screen.getByText(
        'No generation ordering or idempotency key exists for dp-remote (open item 1).',
      ),
    ).not.toBeNull();
    expect(screen.getByText(/Observed 2026-08-24T00:00:00Z/)).not.toBeNull();
  });

  it('INDETERMINATE shows the observed per-replica state, not a spinner', () => {
    renderWithProviders(
      <ApplyOutcomePanel
        outcome={INDETERMINATE}
        onConfirm={() => undefined}
        onRetry={() => undefined}
      />,
    );

    for (const replica of ROLLOUT_REPLICAS) {
      expect(screen.getByText(replica.id)).not.toBeNull();
    }
  });

  it('INDETERMINATE offers no retry action — only confirm', () => {
    renderWithProviders(
      <ApplyOutcomePanel
        outcome={INDETERMINATE}
        onConfirm={() => undefined}
        onRetry={() => undefined}
      />,
    );

    expect(screen.getByRole('button', { name: 'Confirm actual outcome' })).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Retry apply' })).toBeNull();
  });

  it('confirming calls onConfirm, not onRetry', () => {
    const onConfirm = vi.fn();
    const onRetry = vi.fn();
    renderWithProviders(
      <ApplyOutcomePanel outcome={INDETERMINATE} onConfirm={onConfirm} onRetry={onRetry} />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Confirm actual outcome' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onRetry).not.toHaveBeenCalled();
  });

  it('a definite INDETERMINATE (a measured failure) is titled and severed as a failure, not "not confirmed"', () => {
    renderWithProviders(
      <ApplyOutcomePanel
        outcome={DEFINITE_FAILURE}
        onConfirm={() => undefined}
        onRetry={() => undefined}
      />,
    );

    expect(screen.getByText('Apply failed')).not.toBeNull();
    expect(screen.queryByText('Outcome not confirmed')).toBeNull();
    expect(
      screen.getByText('Backend apply state: FAILED_NO_EFFECT. ssh.docker.container.missing'),
    ).not.toBeNull();
    // still requires the same operator confirmation as a genuine ambiguity — nothing here auto-resolves it
    expect(screen.getByRole('button', { name: 'Confirm actual outcome' })).not.toBeNull();
  });

  it('CONFIRMED_BY_OPERATOR offers retry only, no confirm action, and states it is operator-initiated', () => {
    renderWithProviders(
      <ApplyOutcomePanel
        outcome={CONFIRMED}
        onConfirm={() => undefined}
        onRetry={() => undefined}
      />,
    );

    expect(screen.getByRole('button', { name: 'Retry apply' })).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Confirm actual outcome' })).toBeNull();
    expect(screen.getByText(/An automatic retry would risk a second write/)).not.toBeNull();
  });

  it('retrying calls onRetry, not onConfirm', () => {
    const onConfirm = vi.fn();
    const onRetry = vi.fn();
    renderWithProviders(
      <ApplyOutcomePanel outcome={CONFIRMED} onConfirm={onConfirm} onRetry={onRetry} />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Retry apply' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
