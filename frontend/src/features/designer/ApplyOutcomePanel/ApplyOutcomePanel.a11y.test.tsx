import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { ROLLOUT_REPLICAS } from '@shared/testdoubles/data/designer';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { ApplyOutcome } from '../applyRepository';

import { ApplyOutcomePanel } from './ApplyOutcomePanel';

const INDETERMINATE: ApplyOutcome = {
  kind: 'INDETERMINATE',
  reason: 'No generation ordering or idempotency key exists for dp-remote (open item 1).',
  observedAt: '2026-08-24T00:00:00Z',
  definite: false,
  replicas: ROLLOUT_REPLICAS,
};

const CONFIRMED: ApplyOutcome = {
  kind: 'CONFIRMED_BY_OPERATOR',
  confirmedAt: '2026-08-24T00:05:00Z',
  replicas: ROLLOUT_REPLICAS,
};

const modes = ['light', 'dark'] as const;

describe('ApplyOutcomePanel a11y', () => {
  it.each(modes)('has no violations in %s mode, INDETERMINATE', async (mode) => {
    const { container } = renderWithProviders(
      <ApplyOutcomePanel
        outcome={INDETERMINATE}
        onConfirm={() => undefined}
        onRetry={() => undefined}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations in %s mode, CONFIRMED_BY_OPERATOR', async (mode) => {
    const { container } = renderWithProviders(
      <ApplyOutcomePanel
        outcome={CONFIRMED}
        onConfirm={() => undefined}
        onRetry={() => undefined}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
