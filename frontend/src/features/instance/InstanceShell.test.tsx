import { act } from 'react';
import { describe, expect, it, vi } from 'vitest';

import {
  createActionsRepository,
  createApplyRepository,
  createConfigRepository,
} from '@features/designer';
import { createGovernanceRepository } from '@features/governance';
import type { Instance, InstancesRepository } from '@features/inventory';
import { createSolutionsRepository } from '@features/inventory/solutions';
import { ApiError, createConsoleClient, createCpClient, createDpClient } from '@shared/api';
import type { HierarchyRepository } from '@shared/hierarchy';
import { pushOutcome, useOutcomeStore } from '@shared/ui';

import { renderWithProviders, screen, waitFor } from '../../test/renderWithProviders';

import { InstanceShell } from './InstanceShell';

// The monitoring category (task 085/086) reaches a real `ChartRenderer` for
// the fixture's `mark: 'line'` widget — mocked the same way every other
// suite that renders it does, since jsdom has no canvas 2D context.
vi.mock('echarts', () => ({ init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })) }));

// Real-contract-shaped (plan 007) — `Instance`, not the retired fixture `InstanceRow`.
const healthy: Instance = {
  stateVersion: 1,
  instanceId: 'cache-prod-01',
  name: 'cache-prod-01',
  solutionId: 'cache',
  environmentId: 'env-prod-1',
  declaredPackageVersion: 7,
  acquisition: { mode: 'direct-http' },
  placements: [{ placementId: 'p1', targetId: 't1', containerName: 'cache-prod-01', probeStrength: 'readiness' }],
};

function stubRepository(overrides: Partial<InstancesRepository> = {}): InstancesRepository {
  return {
    list: () => Promise.reject(new Error('not used')),
    get: () => Promise.resolve(healthy),
    declare: () => Promise.reject(new Error('not used')),
    undeclare: () => Promise.reject(new Error('not used')),
    ...overrides,
  };
}

const hierarchyRepository: HierarchyRepository = {
  organizations: () => Promise.resolve(['Acme Financial']),
  subOrgs: () => Promise.resolve(['Payments']),
  environments: () => Promise.resolve(['PROD']),
  environmentId: () => Promise.resolve('env-prod-1'),
  environmentPath: () => Promise.resolve({ org: 'Acme Financial', subOrg: 'Payments', env: 'PROD' }),
};

// Real, fixture-backed repositories (the default MSW handlers serve
// `/dp/instances/:instanceId/designer` and `/health`) rather than stubs —
// `designer` renders the real `DesignerPage` (task 123) and `governance`
// renders the real `GovernancePage` (task 064).
const dpClient = createDpClient('/api');
const solutions = createSolutionsRepository(createCpClient('/api'));
const designerRepos = {
  configRepository: createConfigRepository(dpClient, solutions.getPackage.bind(solutions)),
  applyRepository: createApplyRepository(dpClient),
  actionsRepository: createActionsRepository(dpClient),
  governanceRepository: createGovernanceRepository(dpClient),
  hierarchyRepository,
  // Monitoring category (task 085) — real, fixture-backed, same posture
  // as every other repository above.
  consoleClient: createConsoleClient('/api'),
};

describe('InstanceShell', () => {
  it('renders a loading skeleton while the query is in flight', () => {
    renderWithProviders(
      <InstanceShell
        solutionId="cache"
        instanceId="cache-prod-01"
        category="designer"
        repository={stubRepository({ get: () => new Promise(() => undefined) })}
        {...designerRepos}
      />,
    );

    expect(document.querySelectorAll('[data-skeleton]').length).toBeGreaterThan(0);
  });

  it('renders an error panel with retry when the query fails', async () => {
    renderWithProviders(
      <InstanceShell
        solutionId="cache"
        instanceId="cache-prod-01"
        category="designer"
        repository={stubRepository({
          get: () => Promise.reject(new ApiError({ status: 500, message: 'boom' })),
        })}
        {...designerRepos}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('boom')).not.toBeNull();
    });
    expect(screen.getByRole('button', { name: 'Retry' })).not.toBeNull();
  });

  it('renders the real Designer, not a placeholder, for the designer category (task 123)', async () => {
    renderWithProviders(
      <InstanceShell
        solutionId="cache"
        instanceId="cache-prod-01"
        category="designer"
        repository={stubRepository()}
        {...designerRepos}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Save draft' })).not.toBeNull();
    });
    expect(screen.queryByText(/is coming in a later phase/)).toBeNull();
  });

  it('renders the real Governance page, not a placeholder, for the governance category (task 064)', async () => {
    renderWithProviders(
      <InstanceShell
        solutionId="cache"
        instanceId="cache-prod-01"
        category="governance"
        repository={stubRepository()}
        {...designerRepos}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'cache-prod-01' })).not.toBeNull();
      expect(screen.getByRole('heading', { name: 'Governance' })).not.toBeNull();
    });
    expect(screen.queryByText(/is coming in a later phase/)).toBeNull();
  });

  it('renders the real DashboardView (the package’s default dashboard) for monitoring (task 085)', async () => {
    renderWithProviders(
      <InstanceShell
        solutionId="cache"
        instanceId="cache-prod-01"
        category="monitoring"
        repository={stubRepository()}
        {...designerRepos}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'cache-prod-01' })).not.toBeNull();
    });
    await waitFor(() => {
      expect(screen.getByText('Hit rate')).not.toBeNull();
    });
    // Task 131 — the landing note moved from a permanent full-width
    // `Alert` to an `InfoTip` on the Monitoring tab bar, so it rides on
    // the trigger's accessible name rather than being visible text.
    expect(
      document.querySelector(
        '[aria-label^="Monitoring renders the package’s default dashboard directly"]',
      ),
    ).not.toBeNull();
  });

  it('keeps the outcome badge on the header across a category (route) change within the same instance', async () => {
    useOutcomeStore.setState({ byResource: {} });
    act(() => {
      pushOutcome('cache-prod-01', {
        title: 'Config applied',
        correlationId: 'corr-xyz',
        severity: 'ok',
      });
    });

    const { rerender } = renderWithProviders(
      <InstanceShell
        solutionId="cache"
        instanceId="cache-prod-01"
        category="designer"
        repository={stubRepository()}
        {...designerRepos}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('Config applied')).not.toBeNull();
    });

    rerender(
      <InstanceShell
        solutionId="cache"
        instanceId="cache-prod-01"
        category="monitoring"
        repository={stubRepository()}
        {...designerRepos}
      />,
    );

    expect(screen.getByText('Config applied')).not.toBeNull();
    expect(screen.getByText('Correlation id corr-xyz')).not.toBeNull();
    await waitFor(() => {
      expect(screen.getByText('Hit rate')).not.toBeNull();
    });
  });
});
