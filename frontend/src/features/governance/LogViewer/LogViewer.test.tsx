import { describe, expect, it, vi } from 'vitest';

import { fireEvent, renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { GovernanceRepository, LogLevelState, LogLine, LogPage } from '../governanceRepository';

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

describe('LogViewer', () => {
  it('offers every declared component in the selector', async () => {
    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        placementIds={['p1']}
        repository={stubRepository()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByLabelText('Component')).not.toBeNull();
    });
    fireEvent.mouseDown(screen.getByLabelText('Component'));
    expect(await screen.findByRole('option', { name: 'cache-engine' })).not.toBeNull();
    expect(screen.getByRole('option', { name: 'management' })).not.toBeNull();
  });

  it('offers a placement selector only when the instance has more than one placement', async () => {
    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        placementIds={['p1', 'p2']}
        repository={stubRepository()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByLabelText('Placement')).not.toBeNull();
    });
  });

  it('renders no placement selector for a single-placement instance', async () => {
    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        placementIds={['p1']}
        repository={stubRepository()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByLabelText('Component')).not.toBeNull();
    });
    expect(screen.queryByLabelText('Placement')).toBeNull();
  });

  it('renders a stated degradation, not an error, when the component has no log endpoint', async () => {
    renderWithProviders(
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
    );

    await waitFor(() => {
      expect(screen.getByText('This component exposes no log endpoint')).not.toBeNull();
    });
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByLabelText('Level')).toBeNull();
  });

  it('requires confirmation before raising to DEBUG, and calls setLogLevel (mode: config) only on confirm', async () => {
    const setLogLevel = vi.fn(() =>
      Promise.resolve({ applyId: 'apply-debug-1', stateVersion: 2, correlationId: 'corr-debug-1' }),
    );
    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        placementIds={['p1']}
        repository={stubRepository({ setLogLevel })}
      />,
    );

    await waitFor(() => {
      expect(screen.getByLabelText('Level')).not.toBeNull();
    });
    fireEvent.mouseDown(screen.getByLabelText('Level'));
    fireEvent.click(await screen.findByRole('option', { name: 'DEBUG' }));

    await waitFor(() => {
      expect(screen.getByRole('dialog', { name: 'Raise log level to DEBUG' })).not.toBeNull();
    });
    expect(setLogLevel).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Raise to DEBUG' }));

    await waitFor(() => {
      expect(setLogLevel).toHaveBeenCalledWith('cache-prod-01', 'cache-engine', {
        placementId: 'p1',
        level: 'DEBUG',
        mode: 'config',
      });
    });
  });

  it('cancelling the DEBUG confirm never calls setLogLevel', async () => {
    const setLogLevel = vi.fn(() =>
      Promise.resolve({ applyId: 'apply-debug-2', stateVersion: 2, correlationId: 'corr-debug-2' }),
    );
    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        placementIds={['p1']}
        repository={stubRepository({ setLogLevel })}
      />,
    );

    await waitFor(() => {
      expect(screen.getByLabelText('Level')).not.toBeNull();
    });
    fireEvent.mouseDown(screen.getByLabelText('Level'));
    fireEvent.click(await screen.findByRole('option', { name: 'DEBUG' }));
    await waitFor(() => {
      expect(screen.getByRole('dialog', { name: 'Raise log level to DEBUG' })).not.toBeNull();
    });

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(setLogLevel).not.toHaveBeenCalled();
  });

  it('lowering the level (e.g. to WARN) calls setLogLevel directly, without a confirm dialog', async () => {
    const setLogLevel = vi.fn(() =>
      Promise.resolve({ applyId: 'apply-warn-1', stateVersion: 2, correlationId: 'corr-warn-1' }),
    );
    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        placementIds={['p1']}
        repository={stubRepository({ setLogLevel })}
      />,
    );

    await waitFor(() => {
      expect(screen.getByLabelText('Level')).not.toBeNull();
    });
    fireEvent.mouseDown(screen.getByLabelText('Level'));
    fireEvent.click(await screen.findByRole('option', { name: 'WARN' }));

    expect(screen.queryByRole('dialog')).toBeNull();
    await waitFor(() => {
      expect(setLogLevel).toHaveBeenCalledWith('cache-prod-01', 'cache-engine', {
        placementId: 'p1',
        level: 'WARN',
        mode: 'config',
      });
    });
  });

  it('states that live mode is unavailable, rather than offering it as a choice', async () => {
    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        placementIds={['p1']}
        repository={stubRepository()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('config mode only')).not.toBeNull();
    });
  });

  it('tails lines into the viewport and never persists them anywhere', async () => {
    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem');
    const lines: LogLine[] = [
      { at: '2026-10-08T10:00:00Z', level: 'INFO', message: 'started' },
      { at: '2026-10-08T10:00:01Z', level: 'WARN', message: 'slow response' },
    ];

    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        placementIds={['p1']}
        repository={stubRepository({
          readLogs: () => Promise.resolve({ lines, unavailable: false }),
        })}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Stream logs' })).not.toBeNull();
    });
    fireEvent.click(screen.getByRole('button', { name: 'Stream logs' }));

    await waitFor(() => {
      expect(screen.getByText('started')).not.toBeNull();
      expect(screen.getByText('slow response')).not.toBeNull();
    });
    expect(setItemSpy).not.toHaveBeenCalled();
    setItemSpy.mockRestore();
  });

  it('announces tail start/stop to a live region, and never a per-line update (task 067)', async () => {
    renderWithProviders(
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
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Stream logs' })).not.toBeNull();
    });
    const live = document.querySelector('[aria-live="polite"]') as HTMLElement;
    expect(live.textContent).toBe('');

    fireEvent.click(screen.getByRole('button', { name: 'Stream logs' }));

    await waitFor(() => {
      expect(screen.getByText('started')).not.toBeNull();
    });
    expect(live.textContent).toBe('Streaming logs.');

    fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
    await waitFor(() => {
      expect(live.textContent).toBe('Log stream stopped.');
    });
  });
});
