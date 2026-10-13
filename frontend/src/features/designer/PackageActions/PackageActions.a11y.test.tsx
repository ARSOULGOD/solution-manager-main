import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { ViewSection } from '@contracts/view-model/types';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { ActionsRepository } from '../actionsRepository';

import { PackageActions } from './PackageActions';

const SECTIONS: readonly ViewSection[] = [
  {
    title: 'General',
    order: 0,
    fields: [],
    actions: [
      {
        key: 'TEST_BROKER_CONNECTION',
        kind: 'test-connection',
        enabled: true,
        inputs: ['solace.host', 'solace.vpn', 'solace.username', 'solace.password'],
      },
      {
        key: 'BROWSE_REMOTE',
        kind: 'manage-catalog-entry',
        enabled: false,
        inputs: ['ftp.host'],
      },
    ],
  },
];

const repository: ActionsRepository = {
  run: () => Promise.reject(new Error('not exercised in a11y assertions')),
};

const modes = ['light', 'dark'] as const;

describe('PackageActions a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <PackageActions instanceId="cache-prod-01" sections={SECTIONS} repository={repository} />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
