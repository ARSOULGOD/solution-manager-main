import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '@shared/api';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { ConfigRepository } from '../configRepository';

import { RestoreRuntimeDialog } from './RestoreRuntimeDialog';

function stubRepository(restoreRuntime: ConfigRepository['restoreRuntime']): ConfigRepository {
  return {
    loadDesigner: () => Promise.reject(new Error('not used in these tests')),
    save: () => Promise.reject(new Error('not used in these tests')),
    history: () => Promise.resolve([]),
    setSecret: () => Promise.reject(new Error('not used in these tests')),
    acceptRuntime: () => Promise.reject(new Error('not used in these tests')),
    restoreRuntime,
  };
}

describe('RestoreRuntimeDialog', () => {
  it('requires the consequence banner — this is a high-consequence action', () => {
    renderWithProviders(
      <RestoreRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId="cache-prod-01-placement-0"
        declaredVersion="2.4.0"
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        onCancel={() => undefined}
        onRestored={() => undefined}
      />,
    );

    expect(document.querySelector('[data-consequence="outage"]')).not.toBeNull();
  });

  it('names the declared version being restored', () => {
    renderWithProviders(
      <RestoreRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId="cache-prod-01-placement-0"
        declaredVersion="2.4.0"
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        onCancel={() => undefined}
        onRestored={() => undefined}
      />,
    );

    expect(screen.getByText(/overwriting whatever is there now/)).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Restore 2.4.0' })).not.toBeNull();
  });

  it('confirming sends {placementId, strategy: manual} and fires onRestored', async () => {
    const restoreRuntime = vi.fn<ConfigRepository['restoreRuntime']>(() =>
      Promise.resolve({ applyId: 'apply-1' }),
    );
    const onRestored = vi.fn();
    renderWithProviders(
      <RestoreRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId="cache-prod-01-placement-0"
        declaredVersion="2.4.0"
        repository={stubRepository(restoreRuntime)}
        onCancel={() => undefined}
        onRestored={onRestored}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Restore 2.4.0' }));

    await waitFor(() => {
      expect(restoreRuntime).toHaveBeenCalledWith('cache-prod-01', {
        placementId: 'cache-prod-01-placement-0',
        strategy: 'manual',
      });
    });
    await waitFor(() => {
      expect(onRestored).toHaveBeenCalledTimes(1);
    });
  });

  it('with no placementId, confirming never calls restoreRuntime', () => {
    const restoreRuntime = vi.fn<ConfigRepository['restoreRuntime']>(() =>
      Promise.reject(new Error('must not be called')),
    );
    renderWithProviders(
      <RestoreRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId={undefined}
        declaredVersion="2.4.0"
        repository={stubRepository(restoreRuntime)}
        onCancel={() => undefined}
        onRestored={() => undefined}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Restore 2.4.0' }));

    expect(restoreRuntime).not.toHaveBeenCalled();
  });

  it('a failed restore renders an inline error and does not fire onRestored', async () => {
    const onRestored = vi.fn();
    renderWithProviders(
      <RestoreRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId="cache-prod-01-placement-0"
        declaredVersion="2.4.0"
        repository={stubRepository(() => Promise.reject(new ApiError({ status: 500 })))}
        onCancel={() => undefined}
        onRestored={onRestored}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Restore 2.4.0' }));

    expect(
      await screen.findByText('The platform could not start the restore. Try again.'),
    ).not.toBeNull();
    expect(onRestored).not.toHaveBeenCalled();
  });

  it('cancel fires onCancel, never restoreRuntime', () => {
    const onCancel = vi.fn();
    renderWithProviders(
      <RestoreRuntimeDialog
        open
        instanceId="cache-prod-01"
        placementId="cache-prod-01-placement-0"
        declaredVersion="2.4.0"
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        onCancel={onCancel}
        onRestored={() => undefined}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});
