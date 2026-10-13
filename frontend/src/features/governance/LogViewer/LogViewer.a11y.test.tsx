import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { fireEvent, renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { GovernanceRepository, LogLevelState, LogPage } from '../governanceRepository';

import { LogViewer } from './LogViewer';

const COMPONENTS = ['cache-engine', 'management'];

function stubRepository(overrides: Partial<GovernanceRepository> = {}): GovernanceRepository {
  return {
    health: () => Promise.reject(new Error('not used')),
    configLadder: () => Promise.reject(new Error('not used')),
    getLogLevel: (instanceId, component): Promise<LogLevelState> =>
      Promise.resolve({
        component,
        placementId: 'p1',
        liveLevel: 'INFO',
        configuredLevel: 'INFO',
        runtimeOverride: false,
        supported: true,
      }),
    setLogLevel: () => Promise.resolve({ applyId: 'apply-1', stateVersion: 1, correlationId: 'corr-log-1' }),
    readLogs: (): Promise<LogPage> => Promise.resolve({ lines: [], unavailable: false }),
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
        placementIds={['p1']}
        repository={stubRepository()}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByLabelText('Component')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('a placement selector with two live placements has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        placementIds={['p1', 'p2']}
        repository={stubRepository()}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByLabelText('Placement')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the no-endpoint degraded state has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        placementIds={['p1']}
        repository={stubRepository({
          getLogLevel: (instanceId, component) =>
            Promise.resolve({
              component,
              placementId: 'p1',
              liveLevel: null,
              configuredLevel: null,
              runtimeOverride: false,
              supported: false,
            }),
        })}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByText('This component exposes no log endpoint')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the active tailing state has no violations in %s mode (task 067)', async (mode) => {
    const { container } = renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        placementIds={['p1']}
        repository={stubRepository({
          readLogs: () =>
            Promise.resolve({
              lines: [{ at: '2026-10-08T10:00:00Z', level: 'INFO', message: 'started' }],
              unavailable: false,
            }),
        })}
      />,
      { mode },
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Stream logs' })).not.toBeNull();
    });
    fireEvent.click(screen.getByRole('button', { name: 'Stream logs' }));

    await waitFor(() => {
      expect(screen.getByText('started')).not.toBeNull();
    });

    expect(await axe(container)).toHaveNoViolations();
  });
});
