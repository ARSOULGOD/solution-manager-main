import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';

import {
  createActionsRepository,
  createApplyRepository,
  createConfigRepository,
} from '@features/designer';
import { createGovernanceRepository } from '@features/governance';
import type { Instance, InstancesRepository } from '@features/inventory';
import { createSolutionsRepository } from '@features/inventory/solutions';
import { createConsoleClient, createCpClient, createDpClient } from '@shared/api';
import type { HierarchyRepository } from '@shared/hierarchy';

import { renderWithProviders, screen, waitFor } from '../../test/renderWithProviders';

import { InstanceShell } from './InstanceShell';

// The monitoring category (task 085/086) reaches a real `ChartRenderer` for
// the fixture's `mark: 'line'` widget — mocked the same way every other
// suite that renders it does, since jsdom has no canvas 2D context.
vi.mock('echarts', () => ({ init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })) }));

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

function stubRepository(): InstancesRepository {
  return {
    list: () => Promise.reject(new Error('not used')),
    get: () => Promise.resolve(healthy),
    declare: () => Promise.reject(new Error('not used')),
    undeclare: () => Promise.reject(new Error('not used')),
  };
}

const hierarchyRepository: HierarchyRepository = {
  organizations: () => Promise.resolve(['Acme Financial']),
  subOrgs: () => Promise.resolve(['Payments']),
  environments: () => Promise.resolve(['PROD']),
  environmentId: () => Promise.resolve('env-prod-1'),
  environmentPath: () => Promise.resolve({ org: 'Acme Financial', subOrg: 'Payments', env: 'PROD' }),
};

const dpClient = createDpClient('/api');
const solutions = createSolutionsRepository(createCpClient('/api'));
const modes = ['light', 'dark'] as const;

describe('InstanceShell a11y', () => {
  it.each(modes)('loaded state has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <InstanceShell
        solutionId="cache"
        instanceId="cache-prod-01"
        category="designer"
        repository={stubRepository()}
        configRepository={createConfigRepository(dpClient, solutions.getPackage.bind(solutions))}
        applyRepository={createApplyRepository(dpClient)}
        actionsRepository={createActionsRepository(dpClient)}
        governanceRepository={createGovernanceRepository(dpClient)}
        hierarchyRepository={hierarchyRepository}
        consoleClient={createConsoleClient('/api')}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'cache-prod-01' })).not.toBeNull();
    });
    // Real fixture-backed load — waits for the DESIGNER body itself, not
    // just the header, so this actually audits the assembled screen.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Save draft' })).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('governance category has no violations in %s mode (task 064)', async (mode) => {
    const { container } = renderWithProviders(
      <InstanceShell
        solutionId="cache"
        instanceId="cache-prod-01"
        category="governance"
        repository={stubRepository()}
        configRepository={createConfigRepository(dpClient, solutions.getPackage.bind(solutions))}
        applyRepository={createApplyRepository(dpClient)}
        actionsRepository={createActionsRepository(dpClient)}
        governanceRepository={createGovernanceRepository(dpClient)}
        hierarchyRepository={hierarchyRepository}
        consoleClient={createConsoleClient('/api')}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'cache-prod-01' })).not.toBeNull();
    });
    // Real fixture-backed load — waits for the GOVERNANCE body itself, not
    // just the header, so this actually audits the assembled screen.
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Governance' })).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('monitoring category (DashboardView) has no violations in %s mode (task 085)', async (mode) => {
    const { container } = renderWithProviders(
      <InstanceShell
        solutionId="cache"
        instanceId="cache-prod-01"
        category="monitoring"
        repository={stubRepository()}
        configRepository={createConfigRepository(dpClient, solutions.getPackage.bind(solutions))}
        applyRepository={createApplyRepository(dpClient)}
        actionsRepository={createActionsRepository(dpClient)}
        governanceRepository={createGovernanceRepository(dpClient)}
        hierarchyRepository={hierarchyRepository}
        consoleClient={createConsoleClient('/api')}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'cache-prod-01' })).not.toBeNull();
    });
    // Real fixture-backed load — waits for the DASHBOARD body itself, not
    // just the header, so this actually audits the assembled screen.
    await waitFor(() => {
      expect(screen.getByText('Hit rate')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });
});
