import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { ViewStatus } from '@contracts/view-model/types';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { VersionPill } from './VersionPill';

const STATES: readonly ViewStatus[] = [
  { health: 'healthy', versionState: 'ok', configState: 'in_sync', declaredVersion: '2.3.0' },
  {
    health: 'healthy',
    versionState: 'drift',
    configState: 'in_sync',
    declaredVersion: '2.4.0',
    observedVersion: '2.3.0',
  },
  { health: 'healthy', versionState: 'unknown', configState: 'in_sync', declaredVersion: '9.9.0' },
  {
    health: 'healthy',
    versionState: 'unverified',
    configState: 'in_sync',
    declaredVersion: '2.4.0',
  },
];

const modes = ['light', 'dark'] as const;

describe('VersionPill a11y', () => {
  it.each(modes)('has no violations in %s mode, across every version state', async (mode) => {
    for (const status of STATES) {
      const { container, unmount } = renderWithProviders(<VersionPill status={status} />, { mode });
      expect(await axe(container)).toHaveNoViolations();
      unmount();
    }
  });
});
