import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { SECRET_MASK, type DiffRow } from '@shared/ui';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { ApplyRepository } from '../applyRepository';

import { RollbackDialog } from './RollbackDialog';

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

const repository: ApplyRepository = {
  stage: () => Promise.reject(new Error('not exercised in a11y assertions')),
  observe: () => Promise.reject(new Error('not exercised in a11y assertions')),
  rollback: () => Promise.reject(new Error('not exercised in a11y assertions')),
};

const modes = ['light', 'dark'] as const;

describe('RollbackDialog a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <RollbackDialog
        open
        instanceId="cache-prod-01"
        currentGeneration="gen-41"
        targetGeneration="gen-40"
        rows={ROWS}
        repository={repository}
        onCancel={() => undefined}
        onApplied={() => undefined}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
