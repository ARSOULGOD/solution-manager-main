import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { fireEvent, renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { GovernanceRepository } from '../governanceRepository';

import { LogViewer } from './LogViewer';

const COMPONENTS = ['cache-engine', 'management'];

function stubRepository(overrides: Partial<GovernanceRepository> = {}): GovernanceRepository {
  return {
    health: () => Promise.reject(new Error('not used')),
    configLadder: () => Promise.reject(new Error('not used')),
    logLevels: (instanceId, component) =>
      Promise.resolve({ component, level: 'INFO', supported: true }),
    setLogLevel: () => Promise.resolve({ correlationId: 'corr-log-1' }),
    fetchLogs: () => Promise.resolve({ lines: [], nextBefore: null, hasOlder: false }),
    async *streamLogs() {},
    ...overrides,
  };
}

const modes = ['light', 'dark'] as const;

describe('LogViewer a11y', () => {
  it.each(modes)('the controls and viewport have no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        repository={stubRepository()}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByLabelText('Component')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the no-endpoint degraded state has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        repository={stubRepository({
          logLevels: (instanceId, component) =>
            Promise.resolve({ component, level: 'INFO', supported: false }),
        })}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByText('This component exposes no log endpoint')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the active streaming state has no violations in %s mode (task 067)', async (mode) => {
    const { container } = renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        repository={stubRepository({
          async *streamLogs() {
            await Promise.resolve();
            yield { ts: '10:00:00', level: 'INFO', message: 'started' };
          },
        })}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Follow' })).not.toBeNull();
    });
    fireEvent.click(screen.getByRole('button', { name: 'Follow' }));

    await waitFor(() => {
      expect(screen.getByText('started')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });
});
