import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '@shared/api';
import { SECRET_MASK, type DiffRow } from '@shared/ui';

import { setViewportWidth } from '../../../test/matchMedia';
import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { ApplyOutcome, ApplyRepository } from '../applyRepository';

import { RollbackDialog } from './RollbackDialog';

function stubRepository(rollback: ApplyRepository['rollback']): ApplyRepository {
  return {
    stage: () => Promise.reject(new Error('not used in these tests')),
    observe: () => Promise.reject(new Error('not used in these tests')),
    rollback,
  };
}

const ROWS: readonly DiffRow[] = [
  { path: 'cache-engine.namespace', kind: 'change', old: 'ledger-v2', new: 'ledger-prod' },
  {
    path: 'cache-engine.solace.password',
    kind: 'change',
    secret: true,
    old: SECRET_MASK,
    new: SECRET_MASK,
  },
];

const INDETERMINATE: ApplyOutcome = {
  kind: 'INDETERMINATE',
  reason: 'Rollback outcome cannot be confirmed from the HTTP response alone.',
  observedAt: '2026-08-24T00:00:00Z',
  replicas: [],
};

describe('RollbackDialog', () => {
  it('always renders the diff alongside the confirm action — never gated behind a second step', () => {
    renderWithProviders(
      <RollbackDialog
        open
        instanceId="cache-prod-01"
        currentGeneration="gen-41"
        targetGeneration="gen-40"
        rows={ROWS}
        repository={stubRepository(() => Promise.resolve(INDETERMINATE))}
        onCancel={() => undefined}
        onApplied={() => undefined}
      />,
    );

    expect(screen.getByText('cache-engine.namespace')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Roll back & restart all' })).not.toBeNull();
  });

  it('becomes a full-screen sheet below sm (task 108, FE-D29)', () => {
    setViewportWidth(320);
    renderWithProviders(
      <RollbackDialog
        open
        instanceId="cache-prod-01"
        currentGeneration="gen-41"
        targetGeneration="gen-40"
        rows={ROWS}
        repository={stubRepository(() => Promise.resolve(INDETERMINATE))}
        onCancel={() => undefined}
        onApplied={() => undefined}
      />,
    );

    expect(document.querySelector('.MuiDialog-paperFullScreen')).not.toBeNull();
  });

  it('states rollback gets no lighter confirmation for being a revert', () => {
    renderWithProviders(
      <RollbackDialog
        open
        instanceId="cache-prod-01"
        currentGeneration="gen-41"
        targetGeneration="gen-40"
        rows={ROWS}
        repository={stubRepository(() => Promise.resolve(INDETERMINATE))}
        onCancel={() => undefined}
        onApplied={() => undefined}
      />,
    );

    expect(
      screen.getByText('A rollback is a full change — reviewed like any other'),
    ).not.toBeNull();
    expect(screen.getByText(/reverts every replica to gen-40/)).not.toBeNull();
    expect(screen.getByText('gen-41 (current) → gen-40 (target)')).not.toBeNull();
  });

  it('secrets stay masked in the rollback diff, never a resolved value', () => {
    renderWithProviders(
      <RollbackDialog
        open
        instanceId="cache-prod-01"
        currentGeneration="gen-41"
        targetGeneration="gen-40"
        rows={ROWS}
        repository={stubRepository(() => Promise.resolve(INDETERMINATE))}
        onCancel={() => undefined}
        onApplied={() => undefined}
      />,
    );

    expect(screen.getByText('cache-engine.solace.password')).not.toBeNull();
    expect(screen.queryByText(/secret-value|plaintext/i)).toBeNull();
  });

  it('confirming calls rollback() with the target generation and hands the outcome to onApplied — never a success claim', async () => {
    const rollback = vi.fn<ApplyRepository['rollback']>(() => Promise.resolve(INDETERMINATE));
    const onApplied = vi.fn();
    renderWithProviders(
      <RollbackDialog
        open
        instanceId="cache-prod-01"
        currentGeneration="gen-41"
        targetGeneration="gen-40"
        rows={ROWS}
        repository={stubRepository(rollback)}
        onCancel={() => undefined}
        onApplied={onApplied}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Roll back & restart all' }));

    await waitFor(() => expect(rollback).toHaveBeenCalledWith('cache-prod-01', 'gen-40'));
    await waitFor(() => expect(onApplied).toHaveBeenCalledWith(INDETERMINATE));
  });

  it('a failed rollback() renders an inline error and does not fire onApplied', async () => {
    const onApplied = vi.fn();
    renderWithProviders(
      <RollbackDialog
        open
        instanceId="cache-prod-01"
        currentGeneration="gen-41"
        targetGeneration="gen-40"
        rows={ROWS}
        repository={stubRepository(() => Promise.reject(new ApiError({ status: 500 })))}
        onCancel={() => undefined}
        onApplied={onApplied}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Roll back & restart all' }));

    expect(
      await screen.findByText('The platform could not start the rollback. Try again.'),
    ).not.toBeNull();
    expect(onApplied).not.toHaveBeenCalled();
  });

  it('cancel fires onCancel, never rollback()', () => {
    const rollback = vi.fn<ApplyRepository['rollback']>(() => Promise.resolve(INDETERMINATE));
    const onCancel = vi.fn();
    renderWithProviders(
      <RollbackDialog
        open
        instanceId="cache-prod-01"
        currentGeneration="gen-41"
        targetGeneration="gen-40"
        rows={ROWS}
        repository={stubRepository(rollback)}
        onCancel={onCancel}
        onApplied={() => undefined}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(rollback).not.toHaveBeenCalled();
  });
});
