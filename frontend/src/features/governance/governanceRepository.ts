/**
 * `GovernanceRepository` (task 033, §6.0.5, §9.2, UI §A.9; re-wired to the
 * production contract by integration plan 010). CONTRACT: `contracts/openapi/console.yaml`
 * operations `getHealth`, `getConfigLadder`, `readLogs`, `getLogLevel`, `setLogLevel`
 * (tag `governance`).
 *
 * Shape notes, all re-verified against the live `console.yaml` (not carried over from
 * the old fixture scaffold):
 * - `Health` is placement-scoped, with NO top-level `state`/`stateVersion`
 *   (ruling `OR-39`/`OR-55`) — every entry is one placement's own watermarked state.
 *   The state vocabulary is nine values: the five SCREAMING rollup keys plus the four
 *   NOTFOUND sub-states (`unreachable`, `not_exposed`, `missing`, `bootstrap`) as
 *   distinct values — `unreachable` is `OR-39`'s "`UNKNOWN`, not `DOWN`", and gets its
 *   own visual treatment rather than falling back to a generic error tone.
 * - `ConfigLadder` carries an instance-wide `rungs[]` (presence/generation/configHash
 *   per rung), a top-level `reached` (the lowest rung across live placements), and
 *   `placements[]` with each placement's own `reached` (ruling `OR-37`: the same five
 *   rungs everywhere).
 * - `readLogs` returns a bounded page (`LogPage`), not a stream — the transport
 *   question (`Q13`) the original header here flagged is answered: it is an ordinary
 *   GET with `limit`/`placementId`/`before`/`after`, polled by `LogViewer` for a
 *   tail-like experience. An unreachable host is `200` with `availability: unavailable`
 *   rather than an empty page that reads as "no logs" — this repository threads that
 *   through as `LogPage.unavailable` rather than swallowing it.
 * - `getLogLevel`/`setLogLevel` are placement-scoped (`placementId`, required on
 *   `setLogLevel`, defaulted to the instance's only live placement on `getLogLevel`
 *   when it has just one). `setLogLevel` answers `202 ApplyAccepted` (`{ stateVersion,
 *   applyId }`), never a bare `Versioned` — a level change is an ordinary configuration
 *   apply (`OR-103`), not a live write.
 *
 * ⚠️ **`mode` availability is the OPPOSITE of what a stale reading of this plan's own
 * text and `API-INVENTORY.md` bucket D says.** Both say `mode: config` is the `T9.4`
 * 501 gap. The live contract prose AND the production handler
 * (`backend/console-gateway/.../LogLevelOrchestrator.change`) show the reverse:
 * `mode: config` is the one that is BUILT (it admits a real apply, `202`); `mode: live`
 * is the one that is refused — `501` normally, `422` on a direct-HTTP-only placement
 * (`OR-43`). This repository and every caller below render `live` as the unavailable
 * option and only ever submit `config`. Re-verify `API-INVENTORY.md` / this plan's
 * prose before trusting either again — they are stale against `T9.4`'s actual state.
 */
import type { PlaneClient } from '@shared/api';

export type HealthState =
  | 'GREEN'
  | 'RED'
  | 'RESTART_EXPECTED'
  | 'INACTIVE_REPLICA'
  | 'NOT_APPLICABLE'
  | 'unreachable'
  | 'not_exposed'
  | 'missing'
  | 'bootstrap';

export interface HealthPlacement {
  placementId: string;
  state: HealthState;
  checkedAt: string;
  stateVersion: number;
}

/** No top-level rollup on the wire (`OR-39`/`OR-55`) — just the placements. */
export interface HealthSnapshot {
  placements: readonly HealthPlacement[];
}

export const CONFIG_LADDER_RUNGS = [
  'desired',
  'rendered',
  'persisted',
  'restart-observed',
  'effective-verified',
] as const;

export type ConfigLadderRungKey = (typeof CONFIG_LADDER_RUNGS)[number];

export interface ConfigLadderRung {
  rung: ConfigLadderRungKey;
  present: boolean;
  generation?: number;
  configHash?: string;
}

export interface ConfigLadderPlacement {
  placementId: string;
  reached: ConfigLadderRungKey;
}

export interface ConfigLadder {
  stateVersion: number;
  rungs: readonly ConfigLadderRung[];
  /** The lowest rung across live placements. */
  reached: ConfigLadderRungKey;
  placements: readonly ConfigLadderPlacement[];
}

export type LogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';

export type SetLogLevelMode = 'live' | 'config';

export interface LogLine {
  at: string;
  level?: string;
  message: string;
}

export interface LogPage {
  lines: readonly LogLine[];
  /** True when the host could not be reached — `200` with `availability: unavailable`, never a silent empty page. */
  unavailable: boolean;
}

export interface ReadLogsParams {
  /** Required when the instance has more than one live placement. */
  placementId?: string;
  limit?: number;
  before?: string;
  after?: string;
}

export interface LogLevelState {
  component: string;
  placementId: string;
  liveLevel: LogLevel | null;
  configuredLevel: LogLevel | null;
  runtimeOverride: boolean;
  /** False when the package declares no `logs.loggers` for this component — a stated degradation, never an error. */
  supported: boolean;
}

export interface SetLogLevelInput {
  placementId: string;
  level: LogLevel;
  mode: SetLogLevelMode;
}

export interface ApplyAccepted {
  applyId: string;
  stateVersion: number;
}

/**
 * `postWithCorrelation`'s server id, carried alongside the contract's `ApplyAccepted`
 * body — `setLogLevel` is privileged and audited (design §15), and the caller's
 * outcome toast needs the SERVER's correlation id (`FE-D13`), never a client-fabricated
 * one. `ApplyAccepted` itself stays exactly the wire shape; this is the one place the
 * repository adds to it.
 */
export type SetLogLevelResult = ApplyAccepted & { correlationId: string };

export interface GovernanceRepository {
  health(instanceId: string, signal?: AbortSignal): Promise<HealthSnapshot>;
  configLadder(instanceId: string, signal?: AbortSignal): Promise<ConfigLadder>;
  readLogs(
    instanceId: string,
    component: string,
    params?: ReadLogsParams,
    signal?: AbortSignal,
  ): Promise<LogPage>;
  getLogLevel(
    instanceId: string,
    component: string,
    placementId?: string,
    signal?: AbortSignal,
  ): Promise<LogLevelState>;
  /**
   * Privileged, audited (design §15, `UI-D10`). `mode: 'live'` is never sent by any
   * caller in this app — see the header note — only `mode: 'config'`.
   */
  setLogLevel(
    instanceId: string,
    component: string,
    input: SetLogLevelInput,
  ): Promise<SetLogLevelResult>;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) {
      search.set(key, String(value));
    }
  }
  const qs = search.toString();
  return qs === '' ? '' : `?${qs}`;
}

/**
 * `Availability.state` is `live | degraded | unavailable` (its own schema); absence of
 * the whole `availability` object on an `AvailabilityOnly`/`Versioned` response means
 * fully available — there is no `'live'`-shaped absence to special-case beyond that.
 */
function isUnavailable(availability: { state?: string } | null | undefined): boolean {
  return availability != null && availability.state !== 'live';
}

interface WireLogPage {
  availability?: { state: string; reason?: string } | null;
  lines: readonly { at: string; level?: string; message: string }[];
}

interface WireLogLevel {
  availability?: { state: string; reason?: string } | null;
  component: string;
  placementId: string;
  liveLevel?: LogLevel | null;
  configuredLevel?: LogLevel | null;
  runtimeOverride: boolean;
}

export function createGovernanceRepository(dpClient: PlaneClient): GovernanceRepository {
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
    async readLogs(instanceId, component, params = {}, signal) {
      const page = await dpClient.get<WireLogPage>(
        `/instances/${encodeURIComponent(instanceId)}/logs/${encodeURIComponent(component)}${query(
          {
            placementId: params.placementId,
            limit: params.limit,
            before: params.before,
            after: params.after,
          },
        )}`,
        opts(signal),
      );
      return { lines: page.lines, unavailable: isUnavailable(page.availability) };
    },
    async getLogLevel(instanceId, component, placementId, signal) {
      const result = await dpClient.get<WireLogLevel>(
        `/instances/${encodeURIComponent(instanceId)}/log-levels/${encodeURIComponent(
          component,
        )}${query({ placementId })}`,
        opts(signal),
      );
      return {
        component: result.component,
        placementId: result.placementId,
        liveLevel: result.liveLevel ?? null,
        configuredLevel: result.configuredLevel ?? null,
        runtimeOverride: result.runtimeOverride,
        supported: !isUnavailable(result.availability),
      };
    },
    async setLogLevel(instanceId, component, input) {
      const { data, correlationId } = await dpClient.postWithCorrelation<ApplyAccepted>(
        `/instances/${encodeURIComponent(instanceId)}/log-levels/${encodeURIComponent(component)}`,
        input,
      );
      return { ...data, correlationId };
    },
  };
}
