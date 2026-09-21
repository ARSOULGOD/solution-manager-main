/**
 * `GovernanceRepository` (task 033, §6.0.5, §9.2, UI §A.9). CONTRACT: TBD —
 * the WHOLE console↔backend log seam is unspecified (§16 Q13): `UI-D10`
 * describes the MI's own actuator endpoints, not the console's route, and
 * the transport (SSE? chunked? WebSocket?) is unknown. `streamLogs()`
 * returns an `AsyncIterable` specifically so that unknown transport never
 * leaks past this one file — every consumer just iterates lines.
 */
import type { PlaneClient } from '@shared/api';
import type { HealthKey } from '@shared/vocab';

export type HealthRollupState =
  'GREEN' | 'RED' | 'RESTART_EXPECTED' | 'INACTIVE_REPLICA' | 'NOT_APPLICABLE';

export interface HealthSnapshot {
  rollup: HealthRollupState;
  availability: 'declared' | 'not-declared';
  replicas: readonly { id: string; health: HealthKey }[];
}

export interface ConfigLadderStep {
  key: 'desired' | 'rendered' | 'persisted' | 'restart-observed' | 'effective-verified';
  label: string;
  reached: boolean;
}

export interface ConfigLadder {
  steps: readonly ConfigLadderStep[];
}

export type LogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';

export interface LogLevelState {
  component: string;
  level: LogLevel;
  supported: boolean;
}

export interface LogLine {
  ts: string;
  level: LogLevel;
  message: string;
}

export interface LogPage {
  lines: readonly LogLine[];
  nextBefore: string | null;
  hasOlder: boolean;
}

export interface LogPageRequest {
  limit?: number;
  after?: string;
  before?: string;
}

export interface GovernanceRepository {
  health(instanceId: string, signal?: AbortSignal): Promise<HealthSnapshot>;
  configLadder(instanceId: string, signal?: AbortSignal): Promise<ConfigLadder>;
  logLevels(instanceId: string, component: string, signal?: AbortSignal): Promise<LogLevelState>;
  /**
   * Privileged, audited (design §15, `UI-D10`). Returns the server's
   * correlation id — the audit trail this action's whole point is to
   * leave — rather than `void`, so a caller's toast never has to fabricate
   * one (`ToastInput.correlationId` is required, `FE-D13`).
   */
  setLogLevel(
    instanceId: string,
    component: string,
    level: LogLevel,
  ): Promise<{ correlationId: string }>;
  fetchLogs(
    instanceId: string,
    component: string,
    page: LogPageRequest,
    signal?: AbortSignal,
  ): Promise<LogPage>;
  streamLogs(instanceId: string, component: string, signal: AbortSignal): AsyncIterable<LogLine>;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

export function createGovernanceRepository(dpClient: PlaneClient): GovernanceRepository {
  function fetchLogs(
    instanceId: string,
    component: string,
    page: LogPageRequest,
    signal?: AbortSignal,
  ): Promise<LogPage> {
    const params = new URLSearchParams({ limit: String(page.limit ?? 200) });
    if (page.after !== undefined) params.set('after', page.after);
    if (page.before !== undefined) params.set('before', page.before);
    return dpClient.get<LogPage>(
      `/instances/${encodeURIComponent(instanceId)}/logs/${encodeURIComponent(component)}?${params.toString()}`,
      opts(signal),
    );
  }

  return {
    health(instanceId, signal) {
      return dpClient.get<HealthSnapshot>(
        `/instances/${encodeURIComponent(instanceId)}/health`,
        opts(signal),
      );
    },
    configLadder(instanceId, signal) {
      return dpClient.get<ConfigLadder>(
        `/instances/${encodeURIComponent(instanceId)}/config-ladder`,
        opts(signal),
      );
    },
    logLevels(instanceId, component, signal) {
      return dpClient.get<LogLevelState>(
        `/instances/${encodeURIComponent(instanceId)}/log-levels/${encodeURIComponent(component)}`,
        opts(signal),
      );
    },
    async setLogLevel(instanceId, component, level) {
      const { correlationId } = await dpClient.postWithCorrelation<void>(
        `/instances/${encodeURIComponent(instanceId)}/log-levels/${encodeURIComponent(component)}`,
        { level },
      );
      return { correlationId };
    },
    fetchLogs,
    async *streamLogs(instanceId, component, signal) {
      let after: string | undefined;
      while (!signal.aborted) {
        const page = await fetchLogs(
          instanceId,
          component,
          { limit: 200, ...(after === undefined ? {} : { after }) },
          signal,
        );
        for (const line of page.lines) {
          if (signal.aborted) return;
          yield line;
        }
        const latest = page.lines.at(-1);
        if (latest !== undefined) after = latest.ts;
        await abortableDelay(3_000, signal);
      }
    },
  };
}

function abortableDelay(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) {
      resolve();
      return;
    }
    const timer = window.setTimeout(resolve, milliseconds);
    signal.addEventListener('abort', () => {
      window.clearTimeout(timer);
      resolve();
    }, { once: true });
  });
}
