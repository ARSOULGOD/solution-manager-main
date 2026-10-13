import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { ObservedState, SolutionPackage } from '@contracts/view-model/types';
import type { ConfigRepository, DesignerLoad } from '@features/designer';

import { fireEvent, renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { GovernanceRepository, HealthSnapshot } from '../governanceRepository';

import { GovernancePage } from './GovernancePage';

const LADDER_THROUGH_RESTART_OBSERVED = {
  stateVersion: 1,
  rungs: [
    { rung: 'desired' as const, present: true },
    { rung: 'rendered' as const, present: true },
    { rung: 'persisted' as const, present: true },
    { rung: 'restart-observed' as const, present: true },
    { rung: 'effective-verified' as const, present: false },
  ],
  reached: 'restart-observed' as const,
  placements: [{ placementId: 'p1', reached: 'restart-observed' as const }],
};

function stubRepository(health: GovernanceRepository['health']): GovernanceRepository {
  return {
    health,
    configLadder: () => Promise.resolve(LADDER_THROUGH_RESTART_OBSERVED),
    getLogLevel: (instanceId, component) =>
      Promise.resolve({
        component,
        placementId: 'p1',
        liveLevel: 'INFO',
        configuredLevel: 'INFO',
        runtimeOverride: false,
        supported: true,
      }),
    setLogLevel: () => Promise.reject(new Error('not used')),
    readLogs: () => Promise.resolve({ lines: [], unavailable: false }),
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

function stubConfigRepository(): ConfigRepository {
  return {
    loadDesigner: () => Promise.resolve(TEST_DESIGNER_LOAD),
    save: () => Promise.reject(new Error('not used')),
    history: () => Promise.resolve([]),
    setSecret: () => Promise.reject(new Error('not used')),
    acceptRuntime: () => Promise.reject(new Error('not used')),
    restoreRuntime: () => Promise.reject(new Error('not used')),
  };
}

const CHECKED_AT = '2026-10-08T00:00:00Z';

const HEALTHY_WITH_STANDBY: HealthSnapshot = {
  placements: [
    { placementId: 'ledger-0', state: 'GREEN', checkedAt: CHECKED_AT, stateVersion: 1 },
    { placementId: 'ledger-1', state: 'INACTIVE_REPLICA', checkedAt: CHECKED_AT, stateVersion: 1 },
  ],
};

const RED_NO_REPLICAS: HealthSnapshot = {
  placements: [{ placementId: 'ledger-0', state: 'RED', checkedAt: CHECKED_AT, stateVersion: 1 }],
};

const NOT_APPLICABLE: HealthSnapshot = { placements: [] };

const modes = ['light', 'dark'] as const;

describe('GovernancePage a11y', () => {
  it.each(modes)('the loading skeleton has no violations in %s mode (task 067)', async (mode) => {
    const { container } = renderWithProviders(
      <GovernancePage
        solutionId="cache"
        instanceId="cache-prod-01"
        repository={stubRepository(() => new Promise(() => undefined))}
        configRepository={stubConfigRepository()}
      />,
      { mode },
    );

    expect(document.querySelectorAll('[data-skeleton]').length).toBeGreaterThan(0);
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)(
    'the health-query error state has no violations in %s mode (task 067)',
    async (mode) => {
      const { container } = renderWithProviders(
        <GovernancePage
          solutionId="cache"
          instanceId="cache-prod-01"
          repository={stubRepository(() => Promise.reject(new Error('boom')))}
          configRepository={stubConfigRepository()}
        />,
        { mode },
      );

      await waitFor(() => {
        expect(screen.getByRole('alert')).not.toBeNull();
      });

      expect(await axe(container)).toHaveNoViolations();
    },
  );

  it.each(modes)(
    'the no-live-placements state has no violations in %s mode (task 067)',
    async (mode) => {
      const { container } = renderWithProviders(
        <GovernancePage
          solutionId="cache"
          instanceId="cache-edge-02"
          repository={stubRepository(() => Promise.resolve(NOT_APPLICABLE))}
          configRepository={stubConfigRepository()}
        />,
        { mode },
      );

      await waitFor(() => {
        expect(screen.getByText('Not applicable')).not.toBeNull();
      });
      expect(screen.queryByText('Replicas')).toBeNull();

      expect(await axe(container)).toHaveNoViolations();
    },
  );

  it.each(modes)('a healthy snapshot with a standby placement has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <GovernancePage
        solutionId="cache"
        instanceId="cache-prod-01"
        repository={stubRepository(() => Promise.resolve(HEALTHY_WITH_STANDBY))}
        configRepository={stubConfigRepository()}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Governance' })).not.toBeNull();
    });
    await waitFor(() => {
      expect(screen.getByLabelText('Component')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('a RED state with the primary CTA has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <GovernancePage
        solutionId="cache"
        instanceId="cache-stage-01"
        repository={stubRepository(() => Promise.resolve(RED_NO_REPLICAS))}
        configRepository={stubConfigRepository()}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Open in Monitoring' })).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)(
    'the DEBUG confirm dialog (task 066) has no violations in %s mode',
    async (mode) => {
      const { container } = renderWithProviders(
        <GovernancePage
          solutionId="cache"
          instanceId="cache-prod-01"
          repository={stubRepository(() => Promise.resolve(HEALTHY_WITH_STANDBY))}
          configRepository={stubConfigRepository()}
        />,
        { mode },
      );

      await waitFor(() => {
        expect(screen.getByLabelText('Level')).not.toBeNull();
      });
      fireEvent.mouseDown(screen.getByLabelText('Level'));
      fireEvent.click(await screen.findByRole('option', { name: 'DEBUG' }));

      await waitFor(() => {
        expect(screen.getByRole('dialog', { name: 'Raise log level to DEBUG' })).not.toBeNull();
      });

      expect(await axe(container)).toHaveNoViolations();
    },
  );
});
