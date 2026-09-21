import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { DiffRow } from '@shared/ui';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { ConfigRepository } from '../configRepository';

import { AcceptRuntimeDialog } from './AcceptRuntimeDialog';

const repository: ConfigRepository = {
  loadDesigner: () => Promise.reject(new Error('not exercised in a11y assertions')),
  save: () => Promise.reject(new Error('not exercised in a11y assertions')),
  history: () => Promise.resolve([]),
  setSecret: () => Promise.reject(new Error('not exercised in a11y assertions')),
  acceptRuntime: () => Promise.reject(new Error('not exercised in a11y assertions')),
  restoreRuntime: () => Promise.reject(new Error('not exercised in a11y assertions')),
};

const ROWS: readonly DiffRow[] = [
  { path: 'cache-engine.ttl', kind: 'add', old: null, new: '120m' },
  { path: 'cache-engine.legacyFlag', kind: 'remove', old: 'true', new: '—' },
  { path: 'cache-engine.maxSize', kind: 'change', old: '100000', new: '5000000' },
  { path: 'cache-engine.oldKey', kind: 'rename', old: 'value', new: 'value' },
];

const modes = ['light', 'dark'] as const;

describe('AcceptRuntimeDialog a11y', () => {
  it.each(modes)('has no violations in %s mode, with a rename row present', async (mode) => {
    const { container } = renderWithProviders(
      <AcceptRuntimeDialog
        open
        instanceId="cache-prod-01"
        toVersion="2.3.0"
        repository={repository}
        rows={ROWS}
        onClose={() => undefined}
        onAccepted={() => undefined}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
