import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { ConfigLadderReachedKey } from '@shared/vocab';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { ConfigStepper } from './ConfigStepper';

const REACHABLE_KEYS: readonly ConfigLadderReachedKey[] = [
  'desired',
  'rendered',
  'persisted',
  'restart-observed',
];

const modes = ['light', 'dark'] as const;

describe('ConfigStepper a11y', () => {
  it.each(modes)('has no violations in %s mode, across every reached rung', async (mode) => {
    for (const reached of REACHABLE_KEYS) {
      const { container, unmount } = renderWithProviders(<ConfigStepper reached={reached} />, {
        mode,
      });
      expect(await axe(container)).toHaveNoViolations();
      unmount();
    }
  });
});
