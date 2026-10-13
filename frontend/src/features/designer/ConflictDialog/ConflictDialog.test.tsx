import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { SECRET_MASK, type DiffRow } from '@shared/ui';

import { setViewportWidth } from '../../../test/matchMedia';
import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { ConfigRepository, DesignerLoad } from '../configRepository';

import { ConflictDialog } from './ConflictDialog';

function stubRepository(loadDesigner: ConfigRepository['loadDesigner']): ConfigRepository {
  return {
    loadDesigner,
    save: () => Promise.reject(new Error('not used in these tests')),
    history: () => Promise.resolve([]),
    setSecret: () => Promise.reject(new Error('not used in these tests')),
    acceptRuntime: () => Promise.reject(new Error('not used in these tests')),
    restoreRuntime: () => Promise.reject(new Error('not used in these tests')),
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

const LOAD_RESULT: DesignerLoad = {
  stateVersion: 1,
  package: {
    solution: 'cache',
    packageVersion: [8],
    appliesTo: '>=2.2 <2.5',
    provenance: { author: 'solace', signature: 'test-fixture' },
    authoring: { reviewedRows: 0, totalRows: 0 },
    components: [],
  },
  packageVersion: 8,
  values: {},
  observed: { health: 'healthy', versionState: 'ok', configState: 'in_sync' },
  configGeneration: 'gen-99',
};

describe('ConflictDialog', () => {
  it('explains that nothing was overwritten and renders the draft-vs-current diff, secrets masked', () => {
    renderWithProviders(
      <ConflictDialog
        open
        instanceId="cache-prod-01"
        repository={stubRepository(() => Promise.reject(new Error('not used')))}
        rows={ROWS}
        onClose={() => undefined}
        onRefetched={() => undefined}
      />,
    );

    expect(screen.getByText('Someone else saved first')).not.toBeNull();
    expect(screen.getByText(/Nothing was overwritten/)).not.toBeNull();
    expect(screen.getByText('cache-engine.namespace')).not.toBeNull();
    expect(screen.getAllByText('••••••••')).toHaveLength(2);
  });

  it('renders the three unspecified resolutions disabled, with no invented behaviour', () => {
    renderWithProviders(
      <ConflictDialog
        open
        instanceId="cache-prod-01"
        repository={stubRepository(() => Promise.reject(new Error('not used')))}
        rows={ROWS}
        onClose={() => undefined}
        onRefetched={() => undefined}
      />,
    );

    for (const label of ['Overwrite anyway', 'Take theirs', 'Merge per field']) {
      const button = screen.getByRole('button', { name: label });
      expect(button.hasAttribute('disabled')).toBe(true);
    }
  });

  it('becomes a full-screen sheet below sm (task 108, FE-D29)', () => {
    setViewportWidth(320);
    renderWithProviders(
      <ConflictDialog
        open
        instanceId="cache-prod-01"
        repository={stubRepository(() => Promise.reject(new Error('not used')))}
        rows={ROWS}
        onClose={() => undefined}
        onRefetched={() => undefined}
      />,
    );

    expect(document.querySelector('.MuiDialog-paperFullScreen')).not.toBeNull();
  });

  it('"Refetch & review" is the only enabled resolution; on success it hands the fresh load to the caller', async () => {
    const loadDesigner = vi.fn(() => Promise.resolve(LOAD_RESULT));
    const onRefetched = vi.fn();
    renderWithProviders(
      <ConflictDialog
        open
        instanceId="cache-prod-01"
        repository={stubRepository(loadDesigner)}
        rows={ROWS}
        onClose={() => undefined}
        onRefetched={onRefetched}
      />,
    );

    const refetchButton = screen.getByRole('button', { name: 'Refetch & review' });
    expect(refetchButton.hasAttribute('disabled')).toBe(false);
    fireEvent.click(refetchButton);

    await waitFor(() => {
      expect(loadDesigner).toHaveBeenCalledWith('cache-prod-01');
    });
    await waitFor(() => {
      expect(onRefetched).toHaveBeenCalledWith(LOAD_RESULT);
    });
  });

  it('on a refetch failure: renders an inline error and never calls onRefetched', async () => {
    const onRefetched = vi.fn();
    renderWithProviders(
      <ConflictDialog
        open
        instanceId="cache-prod-01"
        repository={stubRepository(() => Promise.reject(new Error('network down')))}
        rows={ROWS}
        onClose={() => undefined}
        onRefetched={onRefetched}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Refetch & review' }));

    await waitFor(() => {
      expect(screen.getByText('Could not refetch the current record')).not.toBeNull();
    });
    expect(onRefetched).not.toHaveBeenCalled();
  });

  it('closing does not call onRefetched — dismissing a conflict is not a resolution', () => {
    const onClose = vi.fn();
    const onRefetched = vi.fn();
    renderWithProviders(
      <ConflictDialog
        open
        instanceId="cache-prod-01"
        repository={stubRepository(() => Promise.reject(new Error('not used')))}
        rows={ROWS}
        onClose={onClose}
        onRefetched={onRefetched}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Close' }));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onRefetched).not.toHaveBeenCalled();
  });
});
