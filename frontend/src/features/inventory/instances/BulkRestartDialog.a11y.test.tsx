import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { Instance } from '@shared/api/contracts/instances';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { BulkRestartDialog } from './BulkRestartDialog';

const modes = ['light', 'dark'] as const;

const INSTANCES: readonly Instance[] = [
  {
    stateVersion: 1,
    instanceId: 'cache-prod-01',
    name: 'cache-prod-01',
    solutionId: 'cache',
    environmentId: 'env-1',
    declaredPackageVersion: 7,
    acquisition: { mode: 'direct-http' },
    placements: [{ placementId: 'p1', targetId: 't1', containerName: 'cache-prod-01', probeStrength: 'readiness' }],
  },
  {
    stateVersion: 1,
    instanceId: 'cache-prod-02',
    name: 'cache-prod-02',
    solutionId: 'cache',
    environmentId: 'env-1',
    declaredPackageVersion: 7,
    acquisition: { mode: 'direct-http' },
    placements: [{ placementId: 'p2', targetId: 't1', containerName: 'cache-prod-02', probeStrength: 'readiness' }],
  },
];

describe('BulkRestartDialog a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <BulkRestartDialog
        open
        instances={INSTANCES}
        onConfirm={() => undefined}
        onCancel={() => undefined}
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
