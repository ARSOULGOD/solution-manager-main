import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { ViewStatus } from '@contracts/view-model/types';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { BlockedBanner } from './BlockedBanner';

const DRIFT: ViewStatus = {
  health: 'healthy',
  versionState: 'drift',
  configState: 'in_sync',
  declaredVersion: '2.4.0',
  observedVersion: '2.3.0',
};

const UNKNOWN: ViewStatus = {
  health: 'healthy',
  versionState: 'unknown',
  configState: 'in_sync',
  declaredVersion: '9.9.0',
};

const modes = ['light', 'dark'] as const;

describe('BlockedBanner a11y', () => {
  it.each(modes)('has no violations in %s mode, for drift and unknown', async (mode) => {
    for (const status of [DRIFT, UNKNOWN]) {
      const { container, unmount } = renderWithProviders(
        <BlockedBanner status={status} onAccept={() => undefined} onRestore={() => undefined} />,
        { mode },
      );
      expect(await axe(container)).toHaveNoViolations();
      unmount();
    }
  });
});
