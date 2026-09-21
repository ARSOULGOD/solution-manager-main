import { fireEvent } from '@testing-library/react';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type {
  ComponentSelection,
  ObservedState,
  SolutionPackage,
} from '@contracts/view-model/types';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { Composer } from './Composer';

const modes = ['light', 'dark'] as const;

const OBSERVED: ObservedState = { health: 'healthy', versionState: 'ok', configState: 'in_sync' };

const PACKAGE: SolutionPackage = {
  solution: 'file-mesh',
  packageVersion: [1],
  appliesTo: '>=1.0 <2.0',
  provenance: { author: 'solace', signature: 'test-fixture' },
  authoring: { reviewedRows: 4, totalRows: 4 },
  components: [
    {
      type: 'COMP_SOURCE_FTP',
      cardinality: 'one-of-many',
      role: 'source',
      configPath: 'source',
      loggerPrefix: 'com.solace.solutions.filemesh.source',
      fields: [
        { key: 'ENABLED', type: 'bool', default: true },
        { key: 'REMOTE_PATH', type: 'string', required: true },
      ],
    },
    {
      type: 'COMP_SINK_SOLACE',
      cardinality: 'one-of-many',
      role: 'sink',
      configPath: 'sink',
      loggerPrefix: 'com.solace.solutions.filemesh.sink',
      fields: [
        { key: 'ENABLED', type: 'bool', default: true },
        { key: 'TOPIC', type: 'string', required: true },
      ],
    },
  ],
};

const SELECTION: ComponentSelection = [
  { type: 'COMP_SOURCE_FTP', instanceId: 'src', role: 'source' },
  { type: 'COMP_SINK_SOLACE', instanceId: 'snk', role: 'sink' },
];

describe('Composer a11y', () => {
  it.each(modes)('has no violations in %s mode, canvas view', async (mode) => {
    const { container } = renderWithProviders(
      <Composer
        pkg={PACKAGE}
        componentSelection={SELECTION}
        packageVersion={1}
        getInstanceValues={(instanceId) =>
          instanceId === 'src' ? { REMOTE_PATH: '/inbound' } : {}
        }
        observedState={OBSERVED}
        onFieldChange={() => {}}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations in %s mode, "All fields" list view', async (mode) => {
    const { container } = renderWithProviders(
      <Composer
        pkg={PACKAGE}
        componentSelection={SELECTION}
        packageVersion={1}
        getInstanceValues={(instanceId) =>
          instanceId === 'src' ? { REMOTE_PATH: '/inbound' } : {}
        }
        observedState={OBSERVED}
        onFieldChange={() => {}}
      />,
      { mode },
    );
    fireEvent.click(screen.getByRole('button', { name: 'All fields' }));

    expect(await axe(container)).toHaveNoViolations();
  });
});
