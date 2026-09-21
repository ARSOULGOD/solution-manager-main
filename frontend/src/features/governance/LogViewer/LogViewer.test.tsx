import { describe, expect, it, vi } from 'vitest';

import { fireEvent, renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { GovernanceRepository, LogLevelState, LogLine } from '../governanceRepository';

import { LogViewer } from './LogViewer';

const COMPONENTS = ['cache-engine', 'management'];

function stubRepository(overrides: Partial<GovernanceRepository> = {}): GovernanceRepository {
  return {
    health: () => Promise.reject(new Error('not used')),
    configLadder: () => Promise.reject(new Error('not used')),
    logLevels: (instanceId, component): Promise<LogLevelState> =>
      Promise.resolve({ component, level: 'INFO', supported: true }),
    setLogLevel: () => Promise.resolve({ correlationId: 'corr-log-1' }),
    fetchLogs: () => Promise.resolve({ lines: [], nextBefore: null, hasOlder: false }),
    async *streamLogs() {},
    ...overrides,
  };
}

describe('LogViewer', () => {
  it('offers every declared component in the selector', async () => {
    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
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

  it('renders a stated degradation, not an error, when the component has no log endpoint', async () => {
    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        repository={stubRepository({
          logLevels: (instanceId, component) =>
            Promise.resolve({ component, level: 'INFO', supported: false }),
        })}
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('This component exposes no log endpoint')).not.toBeNull();
    });
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByLabelText('Level')).toBeNull();
  });

  it('requires confirmation before raising to DEBUG, and calls setLogLevel only on confirm', async () => {
    const setLogLevel = vi.fn(() => Promise.resolve({ correlationId: 'corr-debug-1' }));
    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
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
      expect(setLogLevel).toHaveBeenCalledWith('cache-prod-01', 'cache-engine', 'DEBUG');
    });
  });

  it('cancelling the DEBUG confirm never calls setLogLevel', async () => {
    const setLogLevel = vi.fn(() => Promise.resolve({ correlationId: 'corr-debug-2' }));
    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
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
    const setLogLevel = vi.fn(() => Promise.resolve({ correlationId: 'corr-warn-1' }));
    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
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
      expect(setLogLevel).toHaveBeenCalledWith('cache-prod-01', 'cache-engine', 'WARN');
    });
  });

  it('streams lines into the viewport and never persists them anywhere', async () => {
    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem');
    const lines: LogLine[] = [
      { ts: '10:00:00', level: 'INFO', message: 'started' },
      { ts: '10:00:01', level: 'WARN', message: 'slow response' },
    ];

    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        repository={stubRepository({
          async *streamLogs() {
            await Promise.resolve();
            for (const line of lines) {
              yield line;
            }
          },
        })}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Follow' })).not.toBeNull();
    });
    fireEvent.click(screen.getByRole('button', { name: 'Follow' }));

    await waitFor(() => {
      expect(screen.getByText('started')).not.toBeNull();
      expect(screen.getByText('slow response')).not.toBeNull();
    });
    expect(setItemSpy).not.toHaveBeenCalled();
    setItemSpy.mockRestore();
  });

  it('refreshes recent logs and prepends an older timestamp page', async () => {
    const fetchLogs = vi
      .fn<GovernanceRepository['fetchLogs']>()
      .mockResolvedValueOnce({
        lines: [{ ts: '2026-09-15T09:00:02Z', level: 'INFO', message: 'newest' }],
        nextBefore: '2026-09-15T09:00:02Z',
        hasOlder: true,
      })
      .mockResolvedValueOnce({
        lines: [{ ts: '2026-09-15T09:00:01Z', level: 'INFO', message: 'older' }],
        nextBefore: null,
        hasOlder: false,
      });

    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        repository={stubRepository({ fetchLogs })}
      />,
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Refresh logs' }));
    expect(await screen.findByText('newest')).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Load older' }));
    expect(await screen.findByText('older')).not.toBeNull();
    expect(fetchLogs).toHaveBeenNthCalledWith(
      2,
      'cache-prod-01',
      'cache-engine',
      { before: '2026-09-15T09:00:02Z', limit: 200 },
      expect.any(AbortSignal),
    );
  });

  it('announces stream start/stop to a live region, and never a per-line update (task 067)', async () => {
    const lines: LogLine[] = [
      { ts: '10:00:00', level: 'INFO', message: 'started' },
      { ts: '10:00:01', level: 'WARN', message: 'slow response' },
    ];
    let resolveSecondLine: () => void = () => undefined;
    const secondLineGate = new Promise<void>((resolve) => {
      resolveSecondLine = resolve;
    });

    renderWithProviders(
      <LogViewer
        instanceId="cache-prod-01"
        components={COMPONENTS}
        repository={stubRepository({
          async *streamLogs() {
            yield lines[0]!;
            await secondLineGate;
            yield lines[1]!;
            // Never completes on its own — the test drives the stop via
            // the Pause button, not by the generator returning.
            await new Promise<void>(() => undefined);
          },
        })}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Follow' })).not.toBeNull();
    });
    const live = document.querySelector('[aria-live="polite"]') as HTMLElement;
    expect(live.textContent).toBe('');

    fireEvent.click(screen.getByRole('button', { name: 'Follow' }));

    await waitFor(() => {
      expect(screen.getByText('started')).not.toBeNull();
    });
    expect(live.textContent).toBe('Streaming logs.');

    // A second line arriving must not touch the live region's text — only
    // start/stop transitions may.
    resolveSecondLine();
    await waitFor(() => {
      expect(screen.getByText('slow response')).not.toBeNull();
    });
    expect(live.textContent).toBe('Streaming logs.');

    fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
    await waitFor(() => {
      expect(live.textContent).toBe('Log stream stopped.');
    });
  });
});
