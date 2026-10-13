import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';
import { RESTART_STRATEGIES } from '../restartStrategies';


import { StrategyPicker } from './StrategyPicker';

const modes = ['light', 'dark'] as const;

/** See `StrategyPicker.test.tsx`'s own copy of this comment. */
const CACHE_ROLLOUT_GATING = {
  requiresUniformConfig: true,
  healthMappingDeclared: true,
  healthGateUnsafeReason:
    "This MI's standby reports healthy while processing nothing, so a health gate would advance past a replica that took no traffic (open item 15).",
} as const;

describe('StrategyPicker a11y', () => {
  it.each(modes)('has no violations in %s mode, permissive model', async (mode) => {
    const { container } = renderWithProviders(
      <StrategyPicker
        strategies={RESTART_STRATEGIES}
        value="manual"
        onChange={() => undefined}
        requiresUniformConfig={false}
        healthMappingDeclared={true}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)(
    'has no violations in %s mode, Cache fixture (suppressed strategies)',
    async (mode) => {
      const { container } = renderWithProviders(
        <StrategyPicker
          strategies={RESTART_STRATEGIES}
          value="manual"
          onChange={() => undefined}
          requiresUniformConfig={CACHE_ROLLOUT_GATING.requiresUniformConfig}
          healthMappingDeclared={CACHE_ROLLOUT_GATING.healthMappingDeclared}
          healthGateUnsafeReason={CACHE_ROLLOUT_GATING.healthGateUnsafeReason}
        />,
        { mode },
      );

      expect(await axe(container)).toHaveNoViolations();
    },
  );
});
