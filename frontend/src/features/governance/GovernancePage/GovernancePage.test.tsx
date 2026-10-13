import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import type { ObservedState, SolutionPackage } from '@contracts/view-model/types';
import type { ConfigRepository, DesignerLoad } from '@features/designer';

import { fireEvent, renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { ConfigLadder, GovernanceRepository, HealthSnapshot } from '../governanceRepository';

import { GovernancePage } from './GovernancePage';

const LADDER_THROUGH_RESTART_OBSERVED: ConfigLadder = {
  stateVersion: 1,
  rungs: [
    { rung: 'desired', present: true },
    { rung: 'rendered', present: true },
    { rung: 'persisted', present: true },
    { rung: 'restart-observed', present: true },
    { rung: 'effective-verified', present: false },
  ],
  reached: 'restart-observed',
  placements: [{ placementId: 'p1', reached: 'restart-observed' }],
};

function stubRepository(overrides: Partial<GovernanceRepository> = {}): GovernanceRepository {
  return {
    health: () => Promise.reject(new Error('not used')),
    configLadder: () => Promise.resolve(LADDER_THROUGH_RESTART_OBSERVED),
    getLogLevel: () => Promise.reject(new Error('not used')),
    setLogLevel: () => Promise.reject(new Error('not used')),
    readLogs: () => Promise.resolve({ lines: [], unavailable: false }),
    ...overrides,
  };
}

const TEST_PACKAGE: SolutionPackage = {
  solution: 'cache',
  packageVersion: [1],
  appliesTo: '>=1.0 <2.0',
  provenance: { author: 'acme', signature: 'test-fixture' },
  authoring: { reviewedRows: 1, totalRows: 1 },
  components: [
    {
      type: 'cache-engine',
      cardinality: 'exactly-one',
      configPath: 'cache-engine',
      loggerPrefix: 'com.acme.cache',
      fields: [],
    },
    {
      type: 'management',
      cardinality: 'exactly-one',
      configPath: 'management',
      loggerPrefix: 'com.acme.mgmt',
      fields: [],
    },
  ],
};

const TEST_OBSERVED: ObservedState = {
  health: 'healthy',
  versionState: 'ok',
  configState: 'in_sync',
  declaredVersion: '1.0.0',
  observedVersion: '1.0.0',
};

const TEST_DESIGNER_LOAD: DesignerLoad = {
  stateVersion: 1,
  package: TEST_PACKAGE,
  packageVersion: 1,
  values: {},
  observed: TEST_OBSERVED,
  configGeneration: 'gen-1',
};

function stubConfigRepository(overrides: Partial<ConfigRepository> = {}): ConfigRepository {
  return {
    loadDesigner: () => Promise.resolve(TEST_DESIGNER_LOAD),
    save: () => Promise.reject(new Error('not used')),
    history: () => Promise.resolve([]),
    setSecret: () => Promise.reject(new Error('not used')),
    acceptRuntime: () => Promise.reject(new Error('not used')),
    restoreRuntime: () => Promise.reject(new Error('not used')),
    ...overrides,
  };
}

const CHECKED_AT = '2026-10-08T00:00:00Z';

const HEALTHY_WITH_STANDBY: HealthSnapshot = {
  placements: [
    { placementId: 'ledger-0', state: 'GREEN', checkedAt: CHECKED_AT, stateVersion: 1 },
    { placementId: 'ledger-1', state: 'INACTIVE_REPLICA', checkedAt: CHECKED_AT, stateVersion: 1 },
  ],
};

const NOT_APPLICABLE: HealthSnapshot = { placements: [] };

describe('GovernancePage', () => {
  it('renders a loading skeleton while the query is in flight', () => {
    renderWithProviders(
      <GovernancePage
        solutionId="cache"
        instanceId="cache-prod-01"
        repository={stubRepository({ health: () => new Promise(() => undefined) })}
        configRepository={stubConfigRepository()}
      />,
    );
    expect(document.querySelectorAll('[data-skeleton]').length).toBeGreaterThan(0);
  });

  it('renders the hero and replica cards on success', async () => {
    renderWithProviders(
      <GovernancePage
        solutionId="cache"
        instanceId="cache-prod-01"
        repository={stubRepository({ health: () => Promise.resolve(HEALTHY_WITH_STANDBY) })}
        configRepository={stubConfigRepository()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Governance' })).not.toBeNull();
    });
    expect(screen.getAllByText('Healthy').length).toBeGreaterThan(0);
    expect(screen.getByText('ledger-0')).not.toBeNull();
    expect(screen.getByText('ledger-1')).not.toBeNull();
  });

  it('omits the Replicas section when the instance has no live placements', async () => {
    renderWithProviders(
      <GovernancePage
        solutionId="cache"
        instanceId="cache-edge-02"
        repository={stubRepository({ health: () => Promise.resolve(NOT_APPLICABLE) })}
        configRepository={stubConfigRepository()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('Not applicable')).not.toBeNull();
    });
    expect(screen.queryByText('Replicas')).toBeNull();
  });

  it('renders an error panel with retry when the health query fails', async () => {
    renderWithProviders(
      <GovernancePage
        solutionId="cache"
        instanceId="cache-prod-01"
        repository={stubRepository({ health: () => Promise.reject(new Error('boom')) })}
        configRepository={stubConfigRepository()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('Something went wrong.')).not.toBeNull();
    });
    expect(screen.getByRole('button', { name: 'Retry' })).not.toBeNull();
  });

  it('renders an error panel with retry when the config-ladder query fails', async () => {
    renderWithProviders(
      <GovernancePage
        solutionId="cache"
        instanceId="cache-prod-01"
        repository={stubRepository({
          health: () => Promise.resolve(HEALTHY_WITH_STANDBY),
          configLadder: () => Promise.reject(new Error('boom')),
        })}
        configRepository={stubConfigRepository()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('Something went wrong.')).not.toBeNull();
    });
    expect(screen.getByRole('button', { name: 'Retry' })).not.toBeNull();
  });

  it('renders all five configuration-state rungs, never collapsed (task 065)', async () => {
    renderWithProviders(
      <GovernancePage
        solutionId="cache"
        instanceId="cache-prod-01"
        repository={stubRepository({ health: () => Promise.resolve(HEALTHY_WITH_STANDBY) })}
        configRepository={stubConfigRepository()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Configuration state' })).not.toBeNull();
    });
    for (const label of [
      'Desired',
      'Rendered',
      'Persisted',
      'Restart-observed',
      'Effective-verified',
    ]) {
      expect(screen.getByText(label)).not.toBeNull();
    }
  });

  it('never presents the fifth rung as reached, even when the wire claims it is (task 065)', async () => {
    renderWithProviders(
      <GovernancePage
        solutionId="cache"
        instanceId="cache-prod-01"
        repository={stubRepository({
          health: () => Promise.resolve(HEALTHY_WITH_STANDBY),
          configLadder: () =>
            Promise.resolve({
              ...LADDER_THROUGH_RESTART_OBSERVED,
              reached: 'effective-verified',
              placements: [{ placementId: 'p1', reached: 'effective-verified' }],
            }),
        })}
        configRepository={stubConfigRepository()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Configuration state' })).not.toBeNull();
    });
    expect(document.querySelector('[aria-label="Effective-verified — pending"]')).not.toBeNull();
  });

  it('renders one config stepper per live placement when more than one is live', async () => {
    renderWithProviders(
      <GovernancePage
        solutionId="cache"
        instanceId="cache-prod-01"
        repository={stubRepository({
          health: () => Promise.resolve(HEALTHY_WITH_STANDBY),
          configLadder: () =>
            Promise.resolve({
              ...LADDER_THROUGH_RESTART_OBSERVED,
              placements: [
                { placementId: 'placement-a', reached: 'restart-observed' },
                { placementId: 'placement-b', reached: 'rendered' },
              ],
            }),
        })}
        configRepository={stubConfigRepository()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('placement-a')).not.toBeNull();
      expect(screen.getByText('placement-b')).not.toBeNull();
    });
  });

  it('renders the log viewer with the package-declared components (task 066)', async () => {
    renderWithProviders(
      <GovernancePage
        solutionId="cache"
        instanceId="cache-prod-01"
        repository={stubRepository({
          health: () => Promise.resolve(HEALTHY_WITH_STANDBY),
          getLogLevel: (instanceId, component) =>
            Promise.resolve({
              component,
              placementId: 'ledger-0',
              liveLevel: 'INFO',
              configuredLevel: 'INFO',
              runtimeOverride: false,
              supported: true,
            }),
        })}
        configRepository={stubConfigRepository()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Logs' })).not.toBeNull();
    });
    await waitFor(() => {
      expect(screen.getByLabelText('Component')).not.toBeNull();
    });
  });

  it('offers only the components an instance is bound to in the log viewer (FE-D55)', async () => {
    // A package may declare components an instance does not all compose (one
    // per role). Governance must agree with the Designer about which ones this
    // instance has, or it would offer logs for a component that is not there.
    const bound: DesignerLoad = { ...TEST_DESIGNER_LOAD, componentTypes: ['management'] };
    renderWithProviders(
      <GovernancePage
        solutionId="cache"
        instanceId="cache-prod-01"
        repository={stubRepository({
          health: () => Promise.resolve(HEALTHY_WITH_STANDBY),
          getLogLevel: (instanceId, component) =>
            Promise.resolve({
              component,
              placementId: 'ledger-0',
              liveLevel: 'INFO',
              configuredLevel: 'INFO',
              runtimeOverride: false,
              supported: true,
            }),
        })}
        configRepository={stubConfigRepository({ loadDesigner: () => Promise.resolve(bound) })}
      />,
    );

    await waitFor(() => {
      expect(screen.getByLabelText('Component')).not.toBeNull();
    });
    fireEvent.mouseDown(screen.getByLabelText('Component'));
    expect(await screen.findByRole('option', { name: 'management' })).not.toBeNull();
    expect(screen.queryByRole('option', { name: 'cache-engine' })).toBeNull();
  });

  it('renders an error panel with retry when loading the package for the log viewer fails', async () => {
    renderWithProviders(
      <GovernancePage
        solutionId="cache"
        instanceId="cache-prod-01"
        repository={stubRepository({ health: () => Promise.resolve(HEALTHY_WITH_STANDBY) })}
        configRepository={stubConfigRepository({
          loadDesigner: () => Promise.reject(new Error('boom')),
        })}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Governance' })).not.toBeNull();
    });
    await waitFor(() => {
      expect(screen.getByText('Couldn’t load components for the log viewer')).not.toBeNull();
    });
    // The rest of the page still rendered — a failure to load the package
    // for the log viewer's component list does not hide health/config-state.
    expect(screen.getAllByText('Healthy').length).toBeGreaterThan(0);
  });

  it('reaches every interactive control by keyboard alone, in document order (task 067)', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <GovernancePage
        solutionId="cache"
        instanceId="cache-prod-01"
        repository={stubRepository({
          health: () => Promise.resolve(HEALTHY_WITH_STANDBY),
          getLogLevel: (instanceId, component) =>
            Promise.resolve({
              component,
              placementId: 'ledger-0',
              liveLevel: 'INFO',
              configuredLevel: 'INFO',
              runtimeOverride: false,
              supported: true,
            }),
        })}
        configRepository={stubConfigRepository()}
      />,
    );

    const openInMonitoring = await screen.findByRole('button', { name: 'Open in Monitoring' });
    const componentSelect = await screen.findByLabelText('Component');
    const levelSelect = await screen.findByLabelText('Level');
    const streamButton = await screen.findByRole('button', { name: 'Stream logs' });

    // No pointer-only path exists on this screen — every control below must
    // be reachable purely by Tab, in the order it appears on screen.
    const expectedOrder: Element[] = [openInMonitoring, componentSelect, levelSelect, streamButton];
    const visited: Element[] = [];
    for (let i = 0; i < 20 && visited.length < expectedOrder.length; i += 1) {
      await user.tab();
      const active: Element | null = document.activeElement;
      if (active !== null && expectedOrder.includes(active)) {
        visited.push(active);
      }
    }

    expect(visited).toEqual(expectedOrder);
  });
});
