/**
 * `ConnectionsRepository` (`UI-D27`) — the read/write seam for the
 * connection catalog, wired to `console.yaml`'s real `connections` tag
 * (plan 013 Tasks 1-4, `BE-D66`, `OR-130`): `listConnections`,
 * `createConnection`, `updateConnection`, `deactivateConnection`,
 * `deleteConnection` and `getConnectionTypes` — six operations, no more
 * (there is no single-connection GET).
 *
 * Types here are the REAL wire shapes, not the pre-Task-5 fixture ones in
 * `@shared/testdoubles/data/connections` (which stays as the Designer
 * feature's own no-backend fixture and is untouched by this file) — same
 * split `targetPickerRepository.ts` (`TargetRow` defined locally) already
 * uses.
 *
 * **Scope is never sent as a filter** (`I5`). **`list` takes no parameters at
 * all** — `listConnections` declares none, and the real handler
 * (`ConnectionController.listConnections(Session)`) takes only the session;
 * there is no server-side `type`/`status` filtering to ask for. A pre-fix-
 * round revision of this file built `?type=X&status=active` query strings
 * the server silently ignored. Filtering by type/active is
 * `ConnectionsPage.tsx`'s job, client-side, after the one unfiltered fetch.
 *
 * **Secret values never cross this seam on read.** `Connection.attributes`
 * redacts a secret-typed key to `{ set: true }` at the gateway
 * (`ConnectionOrchestrator.wire`, `I6`) — never the value. A write sends a
 * secret's new plaintext once, keyed exactly like every other attribute (the
 * contract has no separate `secrets` field); omitting the key leaves it
 * unchanged.
 *
 * **No `description` field** — `CreateConnectionRequest`/`UpdateConnectionRequest`/
 * `Connection` are all `additionalProperties: false` and none declares one.
 * A pre-Task-5 revision of this file and the Drawer invented one; the real
 * contract has nothing to carry it, so it is gone.
 */
import type { PlaneClient } from '@shared/api';

export type ConnectionTypeKey = 'solace-broker' | 'remote-server';

/** Mirrors `console.yaml`'s `ScopePath` — the five-level grant hierarchy, each level optional. */
export interface ConnectionOwnershipScope {
  readonly organizationId?: string;
  readonly subOrganizationId?: string;
  readonly environmentId?: string;
  readonly solutionId?: string;
  readonly instanceId?: string;
}

/**
 * Mirrors `console.yaml`'s `ScopeRef` — `ScopePath` without `organizationId`
 * (`I5`: the organization is the session's, never sent). An empty object
 * names the organization itself.
 */
export interface ConnectionScopeRef {
  readonly subOrganizationId?: string;
  readonly environmentId?: string;
  readonly solutionId?: string;
  readonly instanceId?: string;
}

/** Mirrors `console.yaml`'s `ConnectionTypeAttribute` field for field. */
export interface ConnectionDescriptorAttribute {
  readonly key: string;
  readonly type: 'string' | 'int' | 'bool' | 'enum' | 'duration' | 'secret';
  readonly options?: readonly (string | number | boolean)[];
  readonly default?: string | number | boolean | null;
  readonly required?: boolean;
  readonly secret?: boolean;
  readonly discriminator?: boolean;
  readonly when?: Readonly<Record<string, string | number | boolean>>;
  readonly min?: number;
  readonly max?: number;
  readonly pattern?: string;
  readonly ui?: {
    readonly label?: string;
    readonly helperText?: string;
    readonly order?: number;
    readonly listColumn?: boolean;
  };
}

/** Mirrors `console.yaml`'s `ConnectionType` — served verbatim from `contracts/connection-types/*.json`. */
export interface ConnectionDescriptor {
  readonly connectionType: ConnectionTypeKey;
  readonly label: string;
  readonly descriptorVersion: number;
  readonly attributes: readonly ConnectionDescriptorAttribute[];
}

/** Mirrors `console.yaml`'s `Connection`. */
export interface ConnectionRow {
  readonly connectionId: string;
  readonly name: string;
  readonly connectionType: ConnectionTypeKey;
  readonly ownershipScope: ConnectionOwnershipScope;
  /**
   * Non-secret attributes at their current values; a secret-typed attribute
   * is present only as `{ set: true }` (`I6`) — never its value, never
   * merely absent (absence and "never configured" are not the same thing
   * the way a boolean flag distinguishes them).
   */
  readonly attributes: Readonly<Record<string, unknown>>;
  readonly active: boolean;
  readonly usedByCount: number;
  readonly createdAt?: string;
  readonly updatedAt?: string;
}

export interface ConnectionWriteInput {
  readonly name: string;
  /** Required by `createConnection`; `updateConnection` refuses it (`400`) and never sends it. */
  readonly connectionType?: ConnectionTypeKey;
  /** Required by `createConnection`; `updateConnection` has no such property. */
  readonly ownershipScope?: ConnectionScopeRef;
  readonly attributes: Readonly<Record<string, unknown>>;
  /** The one flag backing both "Save" (`false`) and "Test & Save" (`true`) — no separate round trip. */
  readonly testBeforeSave: boolean;
}

export interface ConnectionsRepository {
  /** `listConnections` takes no query parameters — there is no server-side filtering; filter client-side. */
  list(signal?: AbortSignal): Promise<ConnectionRow[]>;
  create(input: ConnectionWriteInput, signal?: AbortSignal): Promise<ConnectionRow>;
  /** `connectionType`/`ownershipScope` on `input` are ignored — `updateConnection` has no such properties. */
  update(id: string, input: ConnectionWriteInput, signal?: AbortSignal): Promise<ConnectionRow>;
  /** One-directional (`deactivateConnection`): there is no reactivate operation in the real contract. */
  deactivate(id: string, signal?: AbortSignal): Promise<ConnectionRow>;
  /** Refuses at `usedByCount > 0` (spec §6.3) before any request is made. */
  remove(id: string, usedByCount: number, signal?: AbortSignal): Promise<void>;
  getConnectionTypes(signal?: AbortSignal): Promise<ConnectionDescriptor[]>;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

export function createConnectionsRepository(cpClient: PlaneClient): ConnectionsRepository {
  return {
    async list(signal) {
      const envelope = await cpClient.get<{ items: readonly ConnectionRow[] }>('/connections', opts(signal));
      return [...envelope.items];
    },
    create(input, signal) {
      const body = {
        name: input.name,
        connectionType: input.connectionType,
        ownershipScope: input.ownershipScope ?? {},
        attributes: input.attributes,
        testBeforeSave: input.testBeforeSave,
      };
      return cpClient.post<ConnectionRow>('/connections', body, opts(signal));
    },
    update(id, input, signal) {
      // `updateConnection` refuses a body that names `connectionType` or an
      // id (`400`) — only `name`/`attributes`/`testBeforeSave` are sent.
      const body = {
        name: input.name,
        attributes: input.attributes,
        testBeforeSave: input.testBeforeSave,
      };
      return cpClient.patch<ConnectionRow>(`/connections/${id}`, body, opts(signal));
    },
    deactivate(id, signal) {
      return cpClient.post<ConnectionRow>(`/connections/${id}/deactivate`, undefined, opts(signal));
    },
    async remove(id, usedByCount, signal) {
      // Checked here, not only server-side: the count is already on screen,
      // and a request that the server will certainly refuse (409) is a
      // worse error message than this one. This is a UX courtesy, not a
      // replacement for the server's own enforcement.
      if (usedByCount > 0) {
        throw new Error(`Cannot delete: referenced by ${usedByCount} instance(s). Deactivate it instead.`);
      }
      await cpClient.delete(`/connections/${id}`, opts(signal));
    },
    getConnectionTypes(signal) {
      return cpClient.get<ConnectionDescriptor[]>('/connection-types', opts(signal));
    },
  };
}
