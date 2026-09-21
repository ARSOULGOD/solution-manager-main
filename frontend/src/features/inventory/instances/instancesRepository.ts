/**
 * `InstancesRepository` (task 033, §6.0.5, §9.2, UI §A.6). CONTRACT: TBD —
 * route, paging model and the not-found discriminator's wire name are
 * unspecified.
 *
 * `declare()` (task 115) posts an INVENTED field set. §16 Q10 is still
 * open: no API, no entitlement-check shape and no server-side field set
 * exist for declaring an instance. What is posted below is the identity
 * half of `InstanceRow` — the fields an operator can know before anything
 * has been observed — and nothing else. When Q10 closes, this shape is the
 * thing to reconcile, not a starting point to defend.
 *
 * **What is deliberately NOT in `DeclareInstanceInput`.** Every observed
 * fact: health, config state, `version.observed`, `lastSeen`. Those are
 * reported by the instance, never asserted by whoever declares it, and the
 * server derives them as "not yet observed" — see `derivePackageCatalog.ts`
 * for the same posture applied to a package's synthesized instance.
 *
 * `undeclare()` (task 117) is the exact inverse of `declare()` and NOTHING
 * MORE. It removes Solution Manager's record of an instance; it does not
 * stop, pause, delete or otherwise touch the running workload. That
 * boundary is not a UI nicety — SM is a config/governance plane, and
 * lifecycle control over an MI belongs to the customer's own CI/CD
 * (`SM-D16`, `I1`). The four lifecycle verbs the architecture does define
 * (`start | stop | restart | run-now`, `architecture.md` §5) are
 * PACKAGE-DECLARED per runtime, and the package contract cannot express
 * them yet (`ActionKind` carries no lifecycle member) — so none of them is
 * offered here, rather than one being approximated.
 */
import type { PlaneClient } from '@shared/api';
import type { Paged } from '@shared/api/contracts/common';
import type { DeclareInstanceInput } from '@shared/api/contracts/instances';
import type { InstanceRow } from '@shared/testdoubles/data/instances';

export type { DeclareInstanceInput };

export interface InventoryQuery {
  env?: 'DEV' | 'TEST' | 'PROD';
  search?: string;
}

/**
 * The created row, plus the server's correlation id — the declare dialog
 * reports its own outcome as a toast, and `ToastInput.correlationId` is
 * required, never client-fabricated (`FE-D13`).
 */
export interface DeclareInstanceResult {
  instance: InstanceRow;
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
  ): Promise<Paged<InstanceRow>>;
  get(instanceId: string, signal?: AbortSignal): Promise<InstanceRow>;
  declare(solutionId: string, input: DeclareInstanceInput): Promise<DeclareInstanceResult>;
  /** Removes SM's record of the instance. Never touches the workload — see the module header. */
  undeclare(instanceId: string): Promise<UndeclareInstanceResult>;
  /** SSH handshake only: returns the host key the operator must pin before declaration. */
  discoverHostKey?(host: string, port: number): Promise<string>;
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

export function createInstancesRepository(dpClient: PlaneClient, cpClient?: PlaneClient): InstancesRepository {
  return {
    list(solutionId, query, signal) {
      return dpClient.get<Paged<InstanceRow>>(
        `/solutions/${encodeURIComponent(solutionId)}/instances${toQueryString(query)}`,
        opts(signal),
      );
    },
    get(instanceId, signal) {
      return dpClient.get<InstanceRow>(
        `/instances/${encodeURIComponent(instanceId)}`,
        opts(signal),
      );
    },
    async declare(solutionId, input) {
      const { sshDocker, ...identity } = input;
      let body: Record<string, unknown> = identity;
      if (sshDocker !== undefined) {
        if (cpClient === undefined) {
          throw new Error('The configured runtime does not support SSH Docker registration.');
        }
        const keyReference = `key-${crypto.randomUUID()}`;
        const pem = await sshDocker.keyFile.text();
        await cpClient.postWithCorrelation('/ssh-keys', { reference: keyReference, pem });
        const { keyFile: _keyFile, ...transport } = sshDocker;
        body = { ...identity, sshDocker: { ...transport, sshKeyReference: keyReference } };
      }
      const { data, correlationId } = await dpClient.postWithCorrelation<InstanceRow>(
        `/solutions/${encodeURIComponent(solutionId)}/instances`,
        body,
      );
      return { instance: data, correlationId };
    },
    async undeclare(instanceId) {
      const { correlationId } = await dpClient.deleteWithCorrelation<unknown>(
        `/instances/${encodeURIComponent(instanceId)}`,
      );
      return { correlationId };
    },
    async discoverHostKey(host, port) {
      if (cpClient === undefined) {
        throw new Error('The configured runtime does not support SSH Docker registration.');
      }
      const result = await cpClient.post<{ fingerprint: string }>('/ssh-host-key', { host, port });
      return result.fingerprint;
    },
  };
}
