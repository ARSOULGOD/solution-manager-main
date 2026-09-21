import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '@shared/api';
import { ROLLOUT_REPLICAS } from '@shared/testdoubles/data/designer';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { ApplyOutcome, ApplyRepository } from '../applyRepository';
import { RESTART_STRATEGIES } from '../restartStrategies';

import { RestartConfirm } from './RestartConfirm';

const MANUAL = RESTART_STRATEGIES.find((s) => s.key === 'manual')!;
const ROLLING_NO_VALIDATION = RESTART_STRATEGIES.find((s) => s.key === 'rolling-no-validation')!;

const RESTART_RESULT = {
  startedAt: '2026-08-24T00:01:00Z',
  observedHash: 'sha256:observed',
  replica: { ...ROLLOUT_REPLICAS[0]!, restartObserved: true },
};

function stubRepository(stage: ApplyRepository['stage'], restart: ApplyRepository['restart'] = () => Promise.resolve(RESTART_RESULT)): ApplyRepository {
  return {
    stage,
    restart,
    observe: () => Promise.reject(new Error('not used in these tests')),
    rollback: () => Promise.reject(new Error('not used in these tests')),
  };
}

const INDETERMINATE: ApplyOutcome = {
  kind: 'INDETERMINATE',
  reason: 'No generation ordering or idempotency key exists for dp-remote (open item 1).',
  observedAt: '2026-08-24T00:00:00Z',
  replicas: ROLLOUT_REPLICAS,
};

describe('RestartConfirm', () => {
  it('states the prescribed copy — requires restart · N replicas · expect unavailability · continue?', () => {
    renderWithProviders(
      <RestartConfirm
        open
        instanceId="cache-prod-01"
        strategy={MANUAL}
        replicas={ROLLOUT_REPLICAS}
        repository={stubRepository(() => Promise.resolve(INDETERMINATE))}
        onCancel={() => undefined}
        onApplied={() => undefined}
      />,
    );

    expect(
      screen.getByText('Requires restart · 4 replicas · expect unavailability · continue?'),
    ).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Apply & restart 4 replicas' })).not.toBeNull();
  });

  it('singular copy for one replica', () => {
    renderWithProviders(
      <RestartConfirm
        open
        instanceId="cache-prod-01"
        strategy={MANUAL}
        replicas={[ROLLOUT_REPLICAS[0]!]}
        repository={stubRepository(() => Promise.resolve(INDETERMINATE))}
        onCancel={() => undefined}
        onApplied={() => undefined}
      />,
    );

    expect(
      screen.getByText('Requires restart · 1 replica · expect unavailability · continue?'),
    ).not.toBeNull();
  });

  it('names the strategy in the body for a non-manual strategy', () => {
    renderWithProviders(
      <RestartConfirm
        open
        instanceId="cache-prod-01"
        strategy={ROLLING_NO_VALIDATION}
        replicas={ROLLOUT_REPLICAS}
        repository={stubRepository(() => Promise.resolve(INDETERMINATE))}
        onCancel={() => undefined}
        onApplied={() => undefined}
      />,
    );

    expect(screen.getByText(/rolling · no validation/)).not.toBeNull();
  });

  it('stages then performs the explicit restart and hands the outcome to onApplied', async () => {
    const stage = vi.fn<ApplyRepository['stage']>(() => Promise.resolve(INDETERMINATE));
    const restart = vi.fn<NonNullable<ApplyRepository['restart']>>(() => Promise.resolve(RESTART_RESULT));
    const onApplied = vi.fn();
    renderWithProviders(
      <RestartConfirm
        open
        instanceId="cache-prod-01"
        strategy={MANUAL}
        replicas={ROLLOUT_REPLICAS}
        repository={stubRepository(stage, restart)}
        onCancel={() => undefined}
        onApplied={onApplied}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Apply & restart 4 replicas' }));

    await waitFor(() => expect(stage).toHaveBeenCalledWith('cache-prod-01', 'manual'));
    await waitFor(() => expect(restart).toHaveBeenCalledWith('cache-prod-01'));
    await waitFor(() => expect(onApplied).toHaveBeenCalledWith({
      ...INDETERMINATE,
      replicas: [RESTART_RESULT.replica],
      restartObserved: true,
    }));
  });

  it('a failed stage() renders an inline error and does not fire onApplied', async () => {
    const onApplied = vi.fn();
    renderWithProviders(
      <RestartConfirm
        open
        instanceId="cache-prod-01"
        strategy={MANUAL}
        replicas={ROLLOUT_REPLICAS}
        repository={stubRepository(() => Promise.reject(new ApiError({ status: 500 })))}
        onCancel={() => undefined}
        onApplied={onApplied}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Apply & restart 4 replicas' }));

    expect(
      await screen.findByText('The platform could not start the apply. Try again.'),
    ).not.toBeNull();
    expect(onApplied).not.toHaveBeenCalled();
  });

  it('cancel fires onCancel, never stage()', () => {
    const stage = vi.fn<ApplyRepository['stage']>(() => Promise.resolve(INDETERMINATE));
    const onCancel = vi.fn();
    renderWithProviders(
      <RestartConfirm
        open
        instanceId="cache-prod-01"
        strategy={MANUAL}
        replicas={ROLLOUT_REPLICAS}
        repository={stubRepository(stage)}
        onCancel={onCancel}
        onApplied={() => undefined}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(stage).not.toHaveBeenCalled();
  });
});
