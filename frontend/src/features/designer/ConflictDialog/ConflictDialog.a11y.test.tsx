import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { SECRET_MASK, type DiffRow } from '@shared/ui';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { ConfigRepository } from '../configRepository';

import { ConflictDialog } from './ConflictDialog';

function stubRepository(): ConfigRepository {
  return {
    loadDesigner: () => Promise.reject(new Error('not used')),
    save: () => Promise.reject(new Error('not used')),
    history: () => Promise.resolve([]),
    setSecret: () => Promise.reject(new Error('not used')),
    acceptRuntime: () => Promise.reject(new Error('not used')),
    restoreRuntime: () => Promise.reject(new Error('not used')),
  };
}

const ROWS: readonly DiffRow[] = [
  { path: 'cache-engine.namespace', kind: 'change', old: 'ledger-v2', new: 'ledger-prod' },
  { path: 'cache-engine.ttl', kind: 'add', old: null, new: '120m' },
  {
    path: 'cache-engine.solace.password',
    kind: 'change',
    secret: true,
    old: SECRET_MASK,
    new: SECRET_MASK,
  },
];

const modes = ['light', 'dark'] as const;

describe('ConflictDialog a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <ConflictDialog
        open
        instanceId="cache-prod-01"
        repository={stubRepository()}
        rows={ROWS}
        onClose={() => undefined}
        onRefetched={() => undefined}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
