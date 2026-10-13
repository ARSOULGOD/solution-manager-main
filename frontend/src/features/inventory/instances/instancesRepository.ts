/**
 * `InstancesRepository` (task 033, §6.0.5, §9.2, UI §A.6).
 *
 * Rewritten for integration plan 007 (2026-10-09) against the real
 * `console.yaml` operations: `listInstances` (`GET
 * /dp/solutions/{solutionId}/instances` → `InstanceList`, `items` only —
 * no top-level count, `OR-55`'s envelope rule, same as `TargetList`),
 * `getInstance` (`GET /dp/instances/{instanceId}` → `Instance`), and
 * `declareInstance` (`POST /dp/environments/{environmentId}/instances` →
 * `201 Instance`). The route is environment-addressed (`OR-52`), not
 * solution-addressed — the pre-007 version posted to
 * `/solutions/{solutionId}/instances`, a drift `contractConformance.test.ts`
 * flagged from plan 002 onward.
 *
 * `undeclare()` (task 117) is the exact inverse of `declare()` and NOTHING
 * MORE. It removes Solution Manager's record of an instance; it does not
 * stop, pause, delete or otherwise touch the running workload. That
 * boundary is not a UI nicety — SM is a config/governance plane, and
 * lifecycle control over an MI belongs to the customer's own CI/CD
 * (`SM-D16`, `I1`). The backend requires the explicit
 * `workload=leave-running` acknowledgement so an operator cannot
 * accidentally imply that undeclare stops or deletes the workload.
 */
import type { PlaneClient } from '@shared/api';
import type {
  DeclareInstanceInput,
  Instance,
} from '@shared/api/contracts/instances';

export type { DeclareInstanceInput, Instance };

export interface InventoryQuery {
  /** Environment names come from the configured hierarchy; they are not a closed enum. */
  env?: string;
  search?: string;
}

/**
 * The created instance, plus the server's correlation id — the declare
 * dialog reports its own outcome as a toast, and `ToastInput.correlationId`
 * is required, never client-fabricated (`FE-D13`).
 */
export interface DeclareInstanceResult {
  instance: Instance;
  correlationId: string;
}

/** A removal reports its own outcome, so it carries the server's correlation id (`FE-D13`). */
export interface UndeclareInstanceResult {
  correlationId: string;
}

export interface InstancesRepository {
  list(
    solutionId: string,
    query: InventoryQuery,
    signal?: AbortSignal,
  ): Promise<readonly Instance[]>;
  get(instanceId: string, signal?: AbortSignal): Promise<Instance>;
  /** `environmentId` addresses the route (`OR-52`); `solutionId` is in `input` itself. */
  declare(environmentId: string, input: DeclareInstanceInput): Promise<DeclareInstanceResult>;
  /** Removes SM's record of the instance. Never touches the workload — see the module header. */
  undeclare(instanceId: string): Promise<UndeclareInstanceResult>;
}

interface InstanceListEnvelope {
  items: readonly Instance[];
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

function toQueryString(query: InventoryQuery): string {
  const params = new URLSearchParams();
  if (query.env !== undefined) params.set('env', query.env);
  if (query.search !== undefined && query.search.length > 0) params.set('search', query.search);
  const search = params.toString();
  return search.length > 0 ? `?${search}` : '';
}

export function createInstancesRepository(dpClient: PlaneClient): InstancesRepository {
  return {
    async list(solutionId, query, signal) {
      const envelope = await dpClient.get<InstanceListEnvelope>(
        `/solutions/${encodeURIComponent(solutionId)}/instances${toQueryString(query)}`,
        opts(signal),
      );
      return envelope.items;
    },
    get(instanceId, signal) {
      return dpClient.get<Instance>(`/instances/${encodeURIComponent(instanceId)}`, opts(signal));
    },
    async declare(environmentId, input) {
      const { data, correlationId } = await dpClient.postWithCorrelation<Instance>(
        `/environments/${encodeURIComponent(environmentId)}/instances`,
        input,
      );
      return { instance: data, correlationId };
    },
    async undeclare(instanceId) {
      const { correlationId } = await dpClient.deleteWithCorrelation<unknown>(
        `/instances/${encodeURIComponent(instanceId)}?workload=leave-running`,
      );
      return { correlationId };
    },
  };
}
