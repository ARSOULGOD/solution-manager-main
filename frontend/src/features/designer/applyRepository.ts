/**
 * `ApplyRepository` (task 033, §6.0.4, §6.0.5, §9.2). ⛔ **BLOCKED** —
 * `DECISIONS.md` open item 1: no generation ordering, no idempotency key,
 * no lease, no crash-safe resume exists for `dp-remote`. The type below
 * encodes the one hard rule that stays true regardless: **there is no
 * "succeeded" variant.** A `dp-remote` apply terminates in `INDETERMINATE`
 * and is resolved from OBSERVED STATE by an operator (G11), never from the
 * HTTP response.
 */
import type { PlaneClient } from '@shared/api';
import type { ReplicaObservationFixture } from '@shared/testdoubles/data/designer';

import type { RestartStrategy } from './restartStrategies';

export type ApplyOutcome =
  | { kind: 'NOT_STARTED' }
  | { kind: 'IN_FLIGHT'; startedAt: string }
  | {
      kind: 'INDETERMINATE';
      reason: string;
      observedAt: string;
      replicas: readonly ReplicaObservationFixture[];
      /** The separate, explicitly requested restart endpoint returned successfully. */
      restartObserved?: boolean;
    }
  | { kind: 'CONFIRMED_BY_OPERATOR'; confirmedAt: string; replicas: readonly ReplicaObservationFixture[] }
  | { kind: 'DIVERGED'; replicas: readonly ReplicaObservationFixture[] };

export interface RestartResult {
  startedAt: string | null;
  observedHash: string | null;
  replica: ReplicaObservationFixture;
}

/**
 * The ONLY transition into a terminal non-diverged state. Requires an
 * operator act — never called automatically from a response handler.
 */
export function confirmActual(
  outcome: ApplyOutcome,
  at: string,
): Extract<ApplyOutcome, { kind: 'CONFIRMED_BY_OPERATOR' }> {
  if (outcome.kind !== 'INDETERMINATE') {
    throw new Error(
      'confirmActual() only transitions an INDETERMINATE outcome (§6.0.4) — nothing else may claim success.',
    );
  }
  return { kind: 'CONFIRMED_BY_OPERATOR', confirmedAt: at, replicas: outcome.replicas };
}

export interface ApplyRepository {
  /** Never returns a "succeeded" outcome — see header. */
  stage(instanceId: string, strategy: RestartStrategy['key']): Promise<ApplyOutcome>;
  /** Explicit operator action; implementations may omit it only for test fixtures. */
  restart?(instanceId: string): Promise<RestartResult>;
  /** The ONLY truth source for what actually happened. */
  observe(instanceId: string, signal?: AbortSignal): Promise<ReplicaObservationFixture[]>;
  rollback(instanceId: string, toGeneration: string): Promise<ApplyOutcome>;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

export function createApplyRepository(dpClient: PlaneClient): ApplyRepository {
  return {
    stage(instanceId, strategy) {
      return dpClient.post<ApplyOutcome>(`/instances/${encodeURIComponent(instanceId)}/apply`, {
        strategy,
      });
    },
    restart(instanceId) {
      return dpClient.post<RestartResult>(`/instances/${encodeURIComponent(instanceId)}/restart`);
    },
    observe(instanceId, signal) {
      return dpClient.get<ReplicaObservationFixture[]>(
        `/instances/${encodeURIComponent(instanceId)}/apply/observe`,
        opts(signal),
      );
    },
    rollback(instanceId, toGeneration) {
      return dpClient.post<ApplyOutcome>(`/instances/${encodeURIComponent(instanceId)}/apply/rollback`, {
        toGeneration,
      });
    },
  };
}
