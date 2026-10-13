/**
 * Fixture connections (`UI-D27`). Fixture-backed exactly like every other
 * feature in this repo — there is no server behind this.
 *
 * **No secret VALUE appears here, ever.** `secretStatus` reports set/unset
 * and nothing else, because that is all the real API will return (`I6`,
 * spec §9.4). A fixture that carried a password would teach the screen a
 * shape the server can never serve.
 */
import remoteServerDescriptor from '@contracts/connection-types/remote-server.json';
import solaceBrokerDescriptor from '@contracts/connection-types/solace-broker.json';
import type { CatalogEntry } from '@contracts/view-model/types';

import { ORGANIZATIONS } from './hierarchy';

export type ConnectionTypeKey = 'solace-broker' | 'remote-server';

export interface ConnectionDescriptorAttribute {
  readonly key: string;
  readonly type: 'string' | 'int' | 'bool' | 'enum' | 'duration' | 'secret';
  readonly options?: readonly (string | number | boolean)[];
  readonly default?: string | number | boolean | null;
  readonly required?: boolean;
  readonly secret?: boolean;
  readonly discriminator?: boolean;
  readonly when?: Readonly<Record<string, string | number | boolean>>;
  readonly ui?: { readonly label?: string; readonly helperText?: string;
                  readonly order?: number; readonly listColumn?: boolean };
}

export interface ConnectionDescriptor {
  readonly connectionType: ConnectionTypeKey;
  readonly label: string;
  readonly descriptorVersion: number;
  readonly attributes: readonly ConnectionDescriptorAttribute[];
}

export interface ConnectionRow {
  readonly id: string;
  readonly name: string;
  readonly connectionType: ConnectionTypeKey;
  readonly descriptorVersion: number;
  /** Server-derived (`I5`). The client never sends this as a filter. */
  readonly scope: { readonly org: string; readonly subOrg: string; readonly env: string };
  readonly description?: string;
  readonly attributes: Readonly<Record<string, unknown>>;
  readonly secretStatus: Readonly<Record<string, 'set' | 'unset'>>;
  readonly status: 'active' | 'inactive';
  /** `UI-D29` — bumps on every save; what makes the fingerprint move. */
  readonly revision: number;
  readonly updatedAt: string;
  readonly updatedBy: string;
  /** Gates deletion and sizes the impact confirmation (spec §6.2). */
  readonly usedByCount: number;
}

export const DESCRIPTORS: Readonly<Record<ConnectionTypeKey, ConnectionDescriptor>> = {
  'solace-broker': solaceBrokerDescriptor as ConnectionDescriptor,
  'remote-server': remoteServerDescriptor as ConnectionDescriptor,
};

export const CONNECTIONS: readonly ConnectionRow[] = [
  {
    id: 'conn-7', name: 'prod-broker-01', connectionType: 'solace-broker', descriptorVersion: 1,
    scope: { org: 'Acme Financial', subOrg: 'Payments', env: 'PROD' },
    description: 'Payments production broker, Frankfurt.',
    attributes: { host: 'tcps://broker.acme.internal', port: 55443, vpn: 'payments-prod', username: 'svc-cache' },
    secretStatus: { password: 'set' },
    status: 'active', revision: 4,
    updatedAt: '2026-09-23T10:14:00Z', updatedBy: 'ops@acme.example', usedByCount: 3,
  },
  {
    id: 'conn-8', name: 'stage-broker-01', connectionType: 'solace-broker', descriptorVersion: 1,
    scope: { org: 'Acme Financial', subOrg: 'Payments', env: 'TEST' },
    attributes: { host: 'tcps://broker-st.acme.internal', port: 55443, vpn: 'payments-stage' },
    // `username` deliberately absent - exercises precedence row 3 end to end.
    secretStatus: { password: 'set' },
    status: 'active', revision: 1,
    updatedAt: '2026-09-18T09:00:00Z', updatedBy: 'ops@acme.example', usedByCount: 1,
  },
  {
    id: 'conn-9', name: 'retired-broker', connectionType: 'solace-broker', descriptorVersion: 1,
    scope: { org: 'Acme Financial', subOrg: 'Payments', env: 'DEV' },
    attributes: { host: 'tcps://old.acme.internal', port: 55555, vpn: 'default' },
    secretStatus: { password: 'unset' },
    status: 'inactive', revision: 2,
    updatedAt: '2026-08-02T12:00:00Z', updatedBy: 'ops@acme.example', usedByCount: 0,
  },
  {
    id: 'conn-12', name: 'sftp-inbound-prod', connectionType: 'remote-server', descriptorVersion: 1,
    scope: { org: 'Acme Financial', subOrg: 'Payments', env: 'PROD' },
    description: 'Inbound settlement files.',
    attributes: { host: 'sftp.acme.internal', port: 22, username: 'ingest', authMethod: 'private-key' },
    secretStatus: { privateKey: 'set', passphrase: 'unset' },
    status: 'active', revision: 2,
    updatedAt: '2026-09-20T16:40:00Z', updatedBy: 'ops@acme.example', usedByCount: 2,
  },
  {
    id: 'conn-13', name: 'ftp-archive', connectionType: 'remote-server', descriptorVersion: 1,
    scope: { org: 'Acme Financial', subOrg: 'Payments', env: 'TEST' },
    attributes: { host: 'archive.acme.internal', port: 22, username: 'archive', authMethod: 'basic' },
    secretStatus: { password: 'set' },
    status: 'active', revision: 1,
    updatedAt: '2026-09-11T08:30:00Z', updatedBy: 'ops@acme.example', usedByCount: 0,
  },
];

/**
 * One active Solace broker per `Organization / Sub-organization / Environment` in the hierarchy
 * fixture that the five rows above do not already cover, so a Declare-instance dialog (and the
 * Designer's `valueFrom` bindings) has a broker to offer for ANY scope an operator can pick in
 * no-backend mode — not just Acme Financial / Payments.
 *
 * Kept OUT of `CONNECTIONS` on purpose: component tests pin that list's exact counts ("Solace
 * brokers (2)"). The MSW store starts from `DEV_CONNECTIONS` instead.
 *
 * A connection's scope is the hierarchy's own DISPLAY names, exactly what the cascade hands the
 * dialog — the dialog's scope filter is an exact string match (`I5`/`UI-D27`), so any other
 * spelling (the fixtures once used `acme`/`payments`) silently offers nothing.
 */
const slug = (text: string): string => text.toLowerCase().replace(/[^a-z0-9]+/g, '-');

export const HIERARCHY_BROKERS: readonly ConnectionRow[] = ORGANIZATIONS.flatMap((org) =>
  org.subOrgs.flatMap((subOrg) =>
    subOrg.environments
      .filter(
        (env) =>
          !CONNECTIONS.some(
            (row) =>
              row.connectionType === 'solace-broker' &&
              row.status === 'active' &&
              row.scope.org === org.name &&
              row.scope.subOrg === subOrg.name &&
              row.scope.env === env,
          ),
      )
      .map((env): ConnectionRow => {
        const name = `${slug(org.name)}-${slug(subOrg.name)}-${env.toLowerCase()}-broker`;
        return {
          id: `conn-b-${slug(org.name)}-${slug(subOrg.name)}-${env.toLowerCase()}`,
          name,
          connectionType: 'solace-broker',
          descriptorVersion: 1,
          scope: { org: org.name, subOrg: subOrg.name, env },
          description: `${org.name} / ${subOrg.name} ${env} broker (generated fixture).`,
          attributes: {
            host: `tcps://${name}.example.internal`,
            port: 55443,
            vpn: `${slug(subOrg.name)}-${env.toLowerCase()}`,
            username: 'svc-sm',
          },
          secretStatus: { password: 'set' },
          status: 'active',
          revision: 1,
          updatedAt: '2026-09-30T09:00:00Z',
          updatedBy: 'ops@example.test',
          usedByCount: 0,
        };
      }),
  ),
);

/** What the no-backend MSW store starts from: the curated rows, then one broker per remaining scope. */
export const DEV_CONNECTIONS: readonly ConnectionRow[] = [...CONNECTIONS, ...HIERARCHY_BROKERS];

/**
 * The connections a Designer may OFFER for an instance: the ACTIVE ones scoped to the instance's own
 * `Organization / Sub-organization / Environment`, plus any the instance has ALREADY selected (kept
 * even if since deactivated or moved, so its `valueFrom` bindings still resolve and the saved choice
 * still displays — the selection is the operator's, not something to silently drop). Same rule the
 * Declare dialog applies client-side (`I5`/`UI-D27`): an operator must never be offered, say, a PROD
 * broker for a DEV instance. The real server derives this scope from the caller; the dev handler
 * stands in for it.
 */
export function connectionsForInstance(
  rows: readonly ConnectionRow[],
  instance: {
    readonly org: string;
    readonly subOrg: string;
    readonly env: string;
    readonly connections?: Readonly<Record<string, string>> | undefined;
  },
): ConnectionRow[] {
  const selected = new Set(Object.values(instance.connections ?? {}));
  return rows.filter(
    (row) =>
      selected.has(row.id) ||
      (row.status === 'active' &&
        row.scope.org === instance.org &&
        row.scope.subOrg === instance.subOrg &&
        row.scope.env === instance.env),
  );
}

/**
 * `UI-D28` feeds `project()` from here. Secret attributes contribute
 * nothing — `project()` emits `secretUnset` for them regardless (`I6`).
 *
 * `secretAttributes` is derived from the DESCRIPTOR, never from the row's
 * own `secretStatus`/`attributes` keys: the descriptor is SM-owned and
 * Task 1's schema enforces `type: "secret"` <=> `secret: true` in both
 * directions, so this list can neither be omitted nor drift from reality.
 * `project()` refuses (`SECRET_BINDING_REFUSED`) to resolve any attribute
 * named here into a field that is not itself secret (`I6`).
 */
export function toCatalogEntries(rows: readonly ConnectionRow[]): CatalogEntry[] {
  return rows.map((row) => ({
    id: row.id,
    label: row.name,
    connectionType: row.connectionType,
    attributes: row.attributes,
    revision: row.revision,
    secretAttributes: DESCRIPTORS[row.connectionType].attributes
      .filter((attribute) => attribute.secret === true)
      .map((attribute) => attribute.key),
  }));
}
