import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { ObservedState, SolutionPackage } from '@contracts/view-model/types';

import { fireEvent, renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { ActionsRepository } from '../actionsRepository';
import type { ApplyRepository } from '../applyRepository';
import type { ConfigRepository, DesignerLoad } from '../configRepository';

import { DesignerPage } from './DesignerPage';

const OBSERVED_OK: ObservedState = {
  health: 'healthy',
  versionState: 'ok',
  configState: 'in_sync',
  declaredVersion: '2.3.0',
  observedVersion: '2.3.0',
};

const OBSERVED_DRIFT: ObservedState = { ...OBSERVED_OK, versionState: 'drift', observedVersion: '2.4.0' };

function testPackage(): SolutionPackage {
  return {
    solution: 'widget-mesh',
    packageVersion: [7],
    appliesTo: '>=1.0 <2.0',
    provenance: { author: 'acme', signature: 'test-fixture' },
    authoring: { reviewedRows: 1, totalRows: 1 },
    components: [
      {
        type: 'widget-connector',
        cardinality: 'exactly-one',
        configPath: 'widget-connector',
        loggerPrefix: 'com.acme.widget',
        actions: [{ key: 'TEST_CONNECTION', kind: 'test-connection', inputs: ['MODE'] }],
        fields: [
          {
            key: 'MODE',
            type: 'enum',
            options: ['push', 'pull'],
            default: 'pull',
            discriminator: true,
            required: true,
            label: 'Mode',
            ui: { section: 'General', order: 0 },
          },
          {
            key: 'POLL_INTERVAL',
            type: 'duration',
            default: '10s',
            when: { MODE: 'pull' },
            label: 'Poll interval',
            ui: { section: 'General', order: 0 },
          },
        ],
      },
    ],
  };
}

function load(observed: ObservedState): DesignerLoad {
  return {
    package: testPackage(),
    packageVersion: 7,
    values: {},
    observed,
    configGeneration: 'gen-1',
  };
}

/** Two `one-of-many` components — the Composer canvas branch (FE-124). */
function multiComponentPackage(): SolutionPackage {
  return {
    solution: 'file-mesh',
    packageVersion: [1],
    appliesTo: '>=1.0 <2.0',
    provenance: { author: 'acme', signature: 'test-fixture' },
    authoring: { reviewedRows: 1, totalRows: 1 },
    components: [
      {
        type: 'COMP_SOURCE',
        cardinality: 'one-of-many',
        role: 'source',
        configPath: 'source',
        loggerPrefix: 'com.acme.source',
        fields: [{ key: 'REMOTE_PATH', type: 'string', required: true, label: 'Remote path' }],
      },
      {
        type: 'COMP_SINK',
        cardinality: 'one-of-many',
        role: 'sink',
        configPath: 'sink',
        loggerPrefix: 'com.acme.sink',
        fields: [{ key: 'TOPIC', type: 'string', required: true, label: 'Topic' }],
      },
    ],
  };
}

function multiComponentLoad(): DesignerLoad {
  return {
    package: multiComponentPackage(),
    packageVersion: 1,
    values: {},
    observed: OBSERVED_OK,
    configGeneration: 'gen-1',
  };
}

function stubConfigRepository(loadDesigner: ConfigRepository['loadDesigner']): ConfigRepository {
  return {
    loadDesigner,
    save: () => Promise.reject(new Error('not used')),
    history: () => Promise.resolve([]),
    setSecret: () => Promise.reject(new Error('not used')),
    acceptRuntime: () => Promise.reject(new Error('not used')),
    restoreRuntime: () => Promise.reject(new Error('not used')),
  };
}

function stubActionsRepository(): ActionsRepository {
  return { run: () => Promise.resolve({ ok: true, message: 'ok' }) };
}

function stubApplyRepository(): ApplyRepository {
  return {
    stage: () => Promise.reject(new Error('not used')),
    observe: () => Promise.reject(new Error('not used')),
    rollback: () => Promise.reject(new Error('not used')),
  };
}

const modes = ['light', 'dark'] as const;

describe('DesignerPage a11y', () => {
  it.each(modes)('editable state has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <DesignerPage
        instanceId="widget-mesh-dev-01"
        configRepository={stubConfigRepository(() => Promise.resolve(load(OBSERVED_OK)))}
        applyRepository={stubApplyRepository()}
        actionsRepository={stubActionsRepository()}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Save draft' })).not.toBeNull();
    });
    fireEvent.click(screen.getByRole('button', { name: /General/, expanded: false }));
    await waitFor(() => {
      expect(screen.getByLabelText('Mode')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('blocked (version drift) state has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <DesignerPage
        instanceId="widget-mesh-dev-01"
        configRepository={stubConfigRepository(() => Promise.resolve(load(OBSERVED_DRIFT)))}
        applyRepository={stubApplyRepository()}
        actionsRepository={stubActionsRepository()}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByText('Editing blocked · version drift')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the Composer canvas (FE-124) has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <DesignerPage
        instanceId="filemesh-dev-01"
        configRepository={stubConfigRepository(() => Promise.resolve(multiComponentLoad()))}
        applyRepository={stubApplyRepository()}
        actionsRepository={stubActionsRepository()}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByRole('navigation', { name: 'Component types' })).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });
});
