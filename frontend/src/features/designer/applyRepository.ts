/**
 * The Designer's apply client. The data-plane API is deliberately
 * asynchronous: POST returns an accepted request, while the result is read
 * from `/apply/observe`. Keeping that protocol here prevents the form and
 * confirmation dialog from inventing a second apply contract.
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
      /**
       * `true` when the backend's own state is a MEASUREMENT of zero effect
       * (`FAILED_NO_EFFECT`, `FAILED_ROLLED_BACK`, `STOP_FAILED`) rather than
       * a genuine dp-remote ambiguity (`APPLY_INDETERMINATE`). The outcome
       * still requires the same operator confirmation either way — nothing
       * here claims success or skips that step — but the copy and severity
       * shown must not call a already-proven failure "unconfirmed".
       */
      definite: boolean;
    }
  | { kind: 'CONFIRMED_BY_OPERATOR'; confirmedAt: string; replicas: readonly ReplicaObservationFixture[] }
  | { kind: 'DIVERGED'; replicas: readonly ReplicaObservationFixture[] };

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
  /** Submits the saved generation for one live placement and observes it. */
  stage(
    instanceId: string,
    placementId: string,
    generation: number,
    strategy: RestartStrategy['key'],
  ): Promise<ApplyOutcome>;
  /** The ONLY truth source for what actually happened. */
  observe(instanceId: string, signal?: AbortSignal): Promise<ReplicaObservationFixture[]>;
  /** Rehydrates the latest terminal result for this saved generation after a page refresh. */
  observeLatest?(
    instanceId: string,
    generation: number,
    signal?: AbortSignal,
  ): Promise<ApplyOutcome | undefined>;
  rollback(instanceId: string, toGeneration: string): Promise<ApplyOutcome>;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

interface ApplyAcceptedWire {
  applyId: string;
  stateVersion: number;
}

interface ApplyStepWire {
  step: string;
  placementId?: string;
  outcome: string;
  reason?: string;
}

export interface ApplyObservationWire {
  applyId: string;
  placementId?: string;
  generation?: number;
  requestedAt?: string;
  state: string;
  terminal: boolean;
  reconcileReason?: string;
  steps?: readonly ApplyStepWire[];
}

interface ApplyObservationListWire {
  items: readonly ApplyObservationWire[];
}

function sleep(milliseconds: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('The operation was aborted', 'AbortError'));
      return;
    }
    const timer = window.setTimeout(resolve, milliseconds);
    signal?.addEventListener(
      'abort',
      () => {
        window.clearTimeout(timer);
        reject(new DOMException('The operation was aborted', 'AbortError'));
      },
      { once: true },
    );
  });
}

function replicasFromObservation(
  instanceId: string,
  observation: ApplyObservationWire,
): ReplicaObservationFixture[] {
  const placementIds = new Set<string>();
  if (observation.placementId !== undefined) placementIds.add(observation.placementId);
  for (const step of observation.steps ?? []) {
    if (step.placementId !== undefined) placementIds.add(step.placementId);
  }
  if (placementIds.size === 0) placementIds.add(instanceId);

  const failed =
    observation.state.startsWith('FAILED') ||
    observation.state === 'CONFIG_DIVERGED' ||
    observation.state === 'STOP_FAILED';
  const restartObserved =
    observation.state === 'APPLIED' || observation.state === 'INDETERMINATE_RESOLVED';
  const generation = observation.generation === undefined ? '' : String(observation.generation);

  return [...placementIds].map((id) => ({
    id,
    generation,
    restartObserved,
    health: failed ? 'RED' : restartObserved ? 'GREEN' : 'RESTART_EXPECTED',
  }));
}

/**
 * States the backend itself classifies as a PROVEN zero-effect outcome (preflight/materialize never
 * reached a mutation, or a stop that could not complete) — `ApplyServiceImpl`'s own comment calls this
 * "a measurement, not a guess". `CONFIG_DIVERGED` is excluded: that is a real divergence needing the
 * rollback/diff flow, not a confirmed no-op, and `APPLY_INDETERMINATE` is excluded because it is the
 * genuine dp-remote ambiguity this outcome kind exists to represent.
 */
const DEFINITE_FAILURE_STATES = new Set(['FAILED_NO_EFFECT', 'FAILED_ROLLED_BACK', 'STOP_FAILED']);

/** Exported for direct unit testing of the `definite` classification (applyRepository.test.ts); not part of the repository's public contract. */
export function outcomeFromObservation(instanceId: string, observation: ApplyObservationWire): ApplyOutcome {
  const stepReason = (observation.steps ?? [])
    .map((step) => step.reason)
    .filter((reason): reason is string => reason !== undefined && reason.length > 0)
    .join(', ');
  const reason = [
    `Backend apply state: ${observation.state}`,
    observation.reconcileReason,
    stepReason,
  ]
    .filter((part): part is string => part !== undefined && part.length > 0)
    .join('. ');
  return {
    kind: 'INDETERMINATE',
    reason,
    observedAt: new Date().toISOString(),
    replicas: replicasFromObservation(instanceId, observation),
    definite: DEFINITE_FAILURE_STATES.has(observation.state),
  };
}

export function createApplyRepository(dpClient: PlaneClient): ApplyRepository {
  return {
    async stage(instanceId, placementId, generation, strategy) {
      const accepted = await dpClient.post<ApplyAcceptedWire>(
        `/instances/${encodeURIComponent(instanceId)}/apply`,
        {
          generation,
          placementId,
          strategy,
          ...(strategy === 'restart-all' ? { outageConfirmedBy: 'designer-confirmed' } : {}),
        },
      );

      // The server intentionally answers 202 before the worker reaches the
      // target. Poll the request selected by applyId so the confirmation
      // dialog reflects the actual journaled state rather than HTTP 202.
      const deadline = Date.now() + 30_000;
      let last: ApplyObservationWire | undefined;
      while (Date.now() < deadline) {
        const list = await dpClient.get<ApplyObservationListWire>(
          `/instances/${encodeURIComponent(instanceId)}/apply/observe?applyId=${encodeURIComponent(accepted.applyId)}`,
        );
        last = list.items.find((item) => item.applyId === accepted.applyId) ?? list.items[0];
        if (last?.terminal === true) return outcomeFromObservation(instanceId, last);
        await sleep(250);
      }

      return {
        kind: 'INDETERMINATE',
        reason: `Apply request ${accepted.applyId} is still in flight after 30 seconds.`,
        observedAt: new Date().toISOString(),
        definite: false,
        replicas: last === undefined ? [] : replicasFromObservation(instanceId, last),
      };
    },
    observe(instanceId, signal) {
      return dpClient
        .get<ApplyObservationListWire>(
          `/instances/${encodeURIComponent(instanceId)}/apply/observe`,
          opts(signal),
        )
        .then((list) => {
          const latest = list.items.at(-1);
          return latest === undefined ? [] : replicasFromObservation(instanceId, latest);
        });
    },
    observeLatest(instanceId, generation, signal) {
      return dpClient
        .get<ApplyObservationListWire>(
          `/instances/${encodeURIComponent(instanceId)}/apply/observe`,
          opts(signal),
        )
        .then((list) => {
          const latest = list.items.at(-1);
          if (
            latest === undefined ||
            latest.terminal !== true ||
            latest.generation !== generation
          ) {
            return undefined;
          }
          return outcomeFromObservation(instanceId, latest);
        });
    },
    rollback(instanceId, toGeneration) {
      return dpClient.post<ApplyOutcome>(`/instances/${encodeURIComponent(instanceId)}/apply/rollback`, {
        toGeneration,
      });
    },
  };
}
