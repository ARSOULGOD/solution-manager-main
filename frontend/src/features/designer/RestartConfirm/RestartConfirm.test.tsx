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

function stubRepository(stage: ApplyRepository['stage']): ApplyRepository {
  return {
    stage,
    observe: () => Promise.reject(new Error('not used in these tests')),
    rollback: () => Promise.reject(new Error('not used in these tests')),
  };
}

const INDETERMINATE: ApplyOutcome = {
  kind: 'INDETERMINATE',
  reason: 'No generation ordering or idempotency key exists for dp-remote (open item 1).',
  observedAt: '2026-08-24T00:00:00Z',
  definite: false,
  replicas: ROLLOUT_REPLICAS,
};

describe('RestartConfirm', () => {
  it('states the prescribed copy — requires restart · N replicas · expect unavailability · continue?', () => {
    renderWithProviders(
      <RestartConfirm
        open
        instanceId="cache-prod-01"
        placementId="placement-0"
        generation={1}
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
        placementId="placement-0"
        generation={1}
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
        placementId="placement-0"
        generation={1}
        strategy={ROLLING_NO_VALIDATION}
        replicas={ROLLOUT_REPLICAS}
        repository={stubRepository(() => Promise.resolve(INDETERMINATE))}
        onCancel={() => undefined}
        onApplied={() => undefined}
      />,
    );

    expect(screen.getByText(/rolling · no validation/)).not.toBeNull();
  });

  it('confirming calls stage() with the strategy key and hands the outcome to onApplied — never a success claim', async () => {
    const stage = vi.fn<ApplyRepository['stage']>(() => Promise.resolve(INDETERMINATE));
    const onApplied = vi.fn();
    renderWithProviders(
      <RestartConfirm
        open
        instanceId="cache-prod-01"
        placementId="placement-0"
        generation={1}
        strategy={MANUAL}
        replicas={ROLLOUT_REPLICAS}
        repository={stubRepository(stage)}
        onCancel={() => undefined}
        onApplied={onApplied}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Apply & restart 4 replicas' }));

    await waitFor(() => expect(stage).toHaveBeenCalledWith('cache-prod-01', 'placement-0', 1, 'manual'));
    await waitFor(() => expect(onApplied).toHaveBeenCalledWith(INDETERMINATE));
  });

  it('a failed stage() renders an inline error and does not fire onApplied', async () => {
    const onApplied = vi.fn();
    renderWithProviders(
      <RestartConfirm
        open
        instanceId="cache-prod-01"
        placementId="placement-0"
        generation={1}
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
        placementId="placement-0"
        generation={1}
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
