import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '@shared/api';
import type { DiffRow } from '@shared/ui';

import { setViewportWidth } from '../../../test/matchMedia';
import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { ConfigRepository, VersionDiff } from '../configRepository';

import { AcceptRuntimeDialog } from './AcceptRuntimeDialog';

function stubRepository(acceptRuntime: ConfigRepository['acceptRuntime']): ConfigRepository {
  return {
    loadDesigner: () => Promise.reject(new Error('not used in these tests')),
    save: () => Promise.reject(new Error('not used in these tests')),
    history: () => Promise.resolve([]),
    setSecret: () => Promise.reject(new Error('not used in these tests')),
    acceptRuntime,
    restoreRuntime: () => Promise.reject(new Error('not used in these tests')),
  };
}

const NO_RENAME_ROWS: readonly DiffRow[] = [
  { path: 'cache-engine.ttl', kind: 'add', old: null, new: '120m' },
  { path: 'cache-engine.legacyFlag', kind: 'remove', old: 'true', new: '—' },
  { path: 'cache-engine.maxSize', kind: 'change', old: '100000', new: '5000000' },
];

const WITH_RENAME_ROW: readonly DiffRow[] = [
  ...NO_RENAME_ROWS,
  { path: 'cache-engine.oldKey', kind: 'rename', old: 'value', new: 'value' },
];

describe('AcceptRuntimeDialog', () => {
  it('renders the classified diff via DiffView', () => {
    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        toVersion="2.3.0"
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        rows={NO_RENAME_ROWS}
        onClose={() => undefined}
        onAccepted={() => undefined}
      />,
    );

    expect(document.querySelector('[data-diff-view]')).not.toBeNull();
    expect(document.querySelectorAll('[data-diff-kind]')).toHaveLength(3);
  });

  it('becomes a full-screen sheet below sm (task 108, FE-D29)', () => {
    setViewportWidth(320);
    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        toVersion="2.3.0"
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        rows={NO_RENAME_ROWS}
        onClose={() => undefined}
        onAccepted={() => undefined}
      />,
    );

    expect(document.querySelector('.MuiDialog-paperFullScreen')).not.toBeNull();
  });

  it('confirm is enabled with no rename rows', () => {
    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        toVersion="2.3.0"
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        rows={NO_RENAME_ROWS}
        onClose={() => undefined}
        onAccepted={() => undefined}
      />,
    );

    expect(
      screen.getByRole('button', { name: 'Re-render against 2.3.0' }).hasAttribute('disabled'),
    ).toBe(false);
  });

  it('confirm is disabled while a rename row has no mapping, and enables once every rename is mapped', () => {
    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        toVersion="2.3.0"
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        rows={WITH_RENAME_ROW}
        onClose={() => undefined}
        onAccepted={() => undefined}
      />,
    );

    const confirmButton = screen.getByRole('button', { name: 'Re-render against 2.3.0' });
    expect(confirmButton.hasAttribute('disabled')).toBe(true);

    fireEvent.change(screen.getByLabelText('New name for cache-engine.oldKey'), {
      target: { value: 'cache-engine.newKey' },
    });

    expect(confirmButton.hasAttribute('disabled')).toBe(false);
  });

  it('a whitespace-only mapping does not count as mapped', () => {
    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        toVersion="2.3.0"
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        rows={WITH_RENAME_ROW}
        onClose={() => undefined}
        onAccepted={() => undefined}
      />,
    );

    fireEvent.change(screen.getByLabelText('New name for cache-engine.oldKey'), {
      target: { value: '   ' },
    });

    expect(
      screen.getByRole('button', { name: 'Re-render against 2.3.0' }).hasAttribute('disabled'),
    ).toBe(true);
  });

  it('confirming sends the typed rename map to acceptRuntime and fires onAccepted', async () => {
    const result: VersionDiff = { rows: [] };
    const acceptRuntime = vi.fn<ConfigRepository['acceptRuntime']>(() => Promise.resolve(result));
    const onAccepted = vi.fn();

    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        toVersion="2.3.0"
        repository={stubRepository(acceptRuntime)}
        rows={WITH_RENAME_ROW}
        onClose={() => undefined}
        onAccepted={onAccepted}
      />,
    );

    fireEvent.change(screen.getByLabelText('New name for cache-engine.oldKey'), {
      target: { value: 'cache-engine.newKey' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Re-render against 2.3.0' }));

    await waitFor(() => {
      expect(acceptRuntime).toHaveBeenCalledWith('cache-prod-01', '2.3.0', {
        'cache-engine.oldKey': 'cache-engine.newKey',
      });
    });
    await waitFor(() => {
      expect(onAccepted).toHaveBeenCalledWith(result);
    });
  });

  it('a failed accept renders an inline error and does not fire onAccepted', async () => {
    const onAccepted = vi.fn();
    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        toVersion="2.3.0"
        repository={stubRepository(() => Promise.reject(new ApiError({ status: 500 })))}
        rows={NO_RENAME_ROWS}
        onClose={() => undefined}
        onAccepted={onAccepted}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Re-render against 2.3.0' }));

    expect(
      await screen.findByText('The platform could not re-render the declaration. Try again.'),
    ).not.toBeNull();
    expect(onAccepted).not.toHaveBeenCalled();
  });

  it('cancel fires onClose, never acceptRuntime', () => {
    const onClose = vi.fn();
    renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        toVersion="2.3.0"
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        rows={NO_RENAME_ROWS}
        onClose={onClose}
        onAccepted={() => undefined}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
