/**
 * One render + one `axe` pass per test case, matching every other a11y
 * test in the repo. The earlier shape looped both fixtures *inside* one
 * `it`, so a single case carried two `DataTable` renders and two full axe
 * passes — over the 5 s default timeout whenever the vitest pool was
 * under load, and a timed-out case then left axe mid-run, cascading
 * "Axe is already running" into the next one. The cross-product below is
 * the same coverage (2 modes x 2 rollouts) with each case a quarter of
 * the work.
 */
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { DIVERGED_REPLICAS, ROLLOUT_REPLICAS } from '@shared/testdoubles/data/designer';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { ReplicaTable } from './ReplicaTable';

const cases = [
  ['light', 'normal', ROLLOUT_REPLICAS],
  ['light', 'diverged', DIVERGED_REPLICAS],
  ['dark', 'normal', ROLLOUT_REPLICAS],
  ['dark', 'diverged', DIVERGED_REPLICAS],
] as const;

describe('ReplicaTable a11y', () => {
  it.each(cases)('has no violations in %s mode, for a %s rollout', async (mode, _rollout, replicas) => {
    const { container } = renderWithProviders(<ReplicaTable replicas={replicas} />, { mode });
    expect(await axe(container)).toHaveNoViolations();
  });
});
