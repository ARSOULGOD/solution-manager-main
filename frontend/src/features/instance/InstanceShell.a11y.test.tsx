import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';

import {
  createActionsRepository,
  createApplyRepository,
  createConfigRepository,
} from '@features/designer';
import { createGovernanceRepository } from '@features/governance';
import type { InstancesRepository } from '@features/inventory';
import { createConsoleClient, createDpClient } from '@shared/api';
import { CACHE_INSTANCES } from '@shared/testdoubles/data/instances';

import { renderWithProviders, screen, waitFor } from '../../test/renderWithProviders';

import { InstanceShell } from './InstanceShell';

// The monitoring category (task 085/086) reaches a real `ChartRenderer` for
// the fixture's `mark: 'line'` widget — mocked the same way every other
// suite that renders it does, since jsdom has no canvas 2D context.
vi.mock('echarts', () => ({ init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })) }));

const found = CACHE_INSTANCES.find((row) => row.id === 'cache-prod-01');
if (found === undefined) {
  throw new Error('InstanceShell.a11y.test.tsx: expected fixture row is missing');
}
const healthy = found;

function stubRepository(): InstancesRepository {
  return {
    list: () => Promise.reject(new Error('not used')),
    get: () => Promise.resolve(healthy),
    declare: () => Promise.reject(new Error('not used')),
    undeclare: () => Promise.reject(new Error('not used')),
  };
}

const dpClient = createDpClient('/api');
const modes = ['light', 'dark'] as const;

describe('InstanceShell a11y', () => {
  it.each(modes)('loaded state has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <InstanceShell
        solutionId="cache"
        instanceId="cache-prod-01"
        category="designer"
        repository={stubRepository()}
        configRepository={createConfigRepository(dpClient)}
        applyRepository={createApplyRepository(dpClient)}
        actionsRepository={createActionsRepository(dpClient)}
        governanceRepository={createGovernanceRepository(dpClient)}
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
        configRepository={createConfigRepository(dpClient)}
        applyRepository={createApplyRepository(dpClient)}
        actionsRepository={createActionsRepository(dpClient)}
        governanceRepository={createGovernanceRepository(dpClient)}
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
        configRepository={createConfigRepository(dpClient)}
        applyRepository={createApplyRepository(dpClient)}
        actionsRepository={createActionsRepository(dpClient)}
        governanceRepository={createGovernanceRepository(dpClient)}
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
