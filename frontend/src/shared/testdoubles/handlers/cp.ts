/**
 * MSW handlers for the control-plane routes (task 033, design §13):
 * catalog, packages, entitlement, RBAC, identity, home, targets. Every
 * handler here backs a repository built in this same task — see each
 * repository file's header for which fields are SPECIFIED vs INVENTED.
 *
 * `*` glob prefixes match any `apiBasePath` (`/api`, or whatever
 * `config.json` sets) — these handlers do not care which one is active.
 */
import { HttpResponse, http } from 'msw';

import { PERMISSION_CATALOG } from '@shared/authz/catalog';

import { DESCRIPTORS, DEV_CONNECTIONS, type ConnectionRow } from '../data/connections';
import type {
  ConnectionRow as RealConnectionRow,
  ConnectionTypeKey as RealConnectionTypeKey,
} from '@features/connections/connectionsRepository';
import { environmentNodesOf, organizationNodes, subOrgNodesOf } from '../data/hierarchy';
import { CACHE_INSTANCES, type InstanceRow } from '../data/instances';
import { packageForSolution } from '../data/packages';
import { RBAC_ROLES, SCOPE_TREE } from '../data/rbac';
import { SOLUTIONS, withInstanceCounts } from '../data/solutions';
import { TARGETS } from '../data/targets';

import * as rbac from './rbacStore';

let connectionRows: ConnectionRow[] = DEV_CONNECTIONS.map((row) => ({ ...row }));

export function resetConnectionStore(): void {
  connectionRows = DEV_CONNECTIONS.map((row) => ({ ...row }));
  realConnectionRows = SEED_REAL_CONNECTIONS.map((row) => ({ ...row }));
}

/**
 * Plan 013 Task 7: the REAL `console.yaml` `connections` tag's fixture state, deliberately SEPARATE
 * from `connectionRows`/`DEV_CONNECTIONS` above. The two shapes differ (`connectionId` vs `id`, no
 * `revision`, `ownershipScope` vs `scope`, secret redaction as `{ set: true }` rather than a
 * `secretStatus` sibling map) and `devPackageHandlers.ts`'s Designer connection-binding dropdown reads
 * `getConnectionRows()` (the OLD shape) directly as a function call, never through HTTP — so leaving
 * `connectionRows` untouched here keeps that dropdown's test coverage intact while these handlers serve
 * the Connections SCREEN's six real operations in the real wire shape.
 */
const SEED_REAL_CONNECTIONS: readonly RealConnectionRow[] = [
  {
    connectionId: 'f47e2f4e-0000-4000-8000-000000000001',
    name: 'prod-broker-01',
    connectionType: 'solace-broker',
    ownershipScope: { environmentId: 'env-1' },
    attributes: {
      host: 'tcps://broker.acme.internal',
      port: 55443,
      vpn: 'payments-prod',
      username: 'svc-cache',
      password: { set: true },
    },
    active: true,
    usedByCount: 3,
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-23T10:14:00Z',
  },
  {
    connectionId: 'f47e2f4e-0000-4000-8000-000000000002',
    name: 'retired-broker',
    connectionType: 'solace-broker',
    ownershipScope: { environmentId: 'env-1' },
    attributes: { host: 'tcps://old.acme.internal', port: 55555, vpn: 'default' },
    active: false,
    usedByCount: 0,
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-02T12:00:00Z',
  },
  {
    connectionId: 'f47e2f4e-0000-4000-8000-000000000003',
    name: 'sftp-inbound-prod',
    connectionType: 'remote-server',
    ownershipScope: { environmentId: 'env-1' },
    attributes: {
      host: 'sftp.acme.internal',
      port: 22,
      username: 'ingest',
      authMethod: 'private-key',
      privateKey: { set: true },
    },
    active: true,
    usedByCount: 2,
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-20T16:40:00Z',
  },
];

let realConnectionRows: RealConnectionRow[] = SEED_REAL_CONNECTIONS.map((row) => ({ ...row }));

function nextRealConnectionId(): string {
  return `f47e2f4e-0000-4000-8000-${String(realConnectionRows.length + 1).padStart(12, '0')}`;
}

/**
 * The LIVE catalog, for a dev handler that needs it outside this module's
 * own routes (`devPackageHandlers.ts`'s declare and designer-load handlers,
 * `UI-D27`/`UI-D29`) — never the static `CONNECTIONS` import, which would
 * miss an edit made through the Connections screen this session.
 */
export function getConnectionRows(): readonly ConnectionRow[] {
  return connectionRows;
}

// Maps `InstanceRow`'s legacy fixture vocabulary (`shared/vocab`'s
// lowercase `HealthKey`/`NotFoundKey`) onto the real wire vocabulary
// `HomeSummary.byHealth` / `HomeAttention.items[].state` use
// (`contracts/openapi/console.yaml`'s nine-value notify set) — see
// `homeRepository.ts`'s own header for the contract citation. Kept local to
// this handler file: it is test-fixture plumbing, not part of the contract.
function healthWireKeyOf(instance: InstanceRow): string {
  if (instance.healthRollup === 'notfound') {
    return instance.notFoundReason ?? 'missing';
  }
  switch (instance.healthRollup) {
    case 'healthy':
      return 'GREEN';
    case 'not_responding':
      return 'RED';
    case 'restart_expected':
      return 'RESTART_EXPECTED';
    case 'inactive_replica':
      return 'INACTIVE_REPLICA';
    case 'not_applicable':
      return 'NOT_APPLICABLE';
    default:
      return 'NOT_APPLICABLE';
  }
}

interface WireAttentionFixtureItem {
  instanceId: string;
  reason: 'health' | 'config-diverged';
  state?: string;
  stateVersion: number;
}

export const cpHandlers = [
  // ---- Home (plan 004: real `HomeSummary`/`HomeAttention` envelope, per
  // contracts/openapi/console.yaml — superseding the old fixture-era shape
  // ATTENTION_FEED/the four-field flat summary used before this plan) ----
  http.get('*/cp/home/summary', () =>
    HttpResponse.json({
      solutions: SOLUTIONS.map((solution) => {
        const instances = CACHE_INSTANCES.filter((instance) => instance.solutionId === solution.id);
        const byHealth: Record<string, number> = {};
        for (const instance of instances) {
          const key = healthWireKeyOf(instance);
          byHealth[key] = (byHealth[key] ?? 0) + 1;
        }
        return {
          solutionId: solution.id,
          name: solution.name,
          instanceCount: instances.length,
          byHealth,
          stateVersion: 1,
        };
      }),
    }),
  ),
  http.get('*/cp/home/attention', () => {
    const items = CACHE_INSTANCES.flatMap((instance): WireAttentionFixtureItem[] => {
      if (instance.configState === 'diverged') {
        return [{ instanceId: instance.id, reason: 'config-diverged', stateVersion: 1 }];
      }
      const key = healthWireKeyOf(instance);
      if (key === 'RED' || key === 'unreachable' || key === 'not_exposed' || key === 'missing') {
        return [{ instanceId: instance.id, reason: 'health', state: key, stateVersion: 1 }];
      }
      return [];
    });
    return HttpResponse.json({ items });
  }),

  // ---- Solutions — console.yaml `listSolutions` / `loadPackage` (plan 005 Task 2) ----
  // `withInstanceCounts(SOLUTIONS, CACHE_INSTANCES)` stays the fixture home's own rich
  // shape (`home/summary` above still reads `SOLUTIONS` directly); this handler maps it
  // down to the `SolutionList` envelope of `Solution` rows the real backend returns —
  // only the fields `Solution` declares, no synthesized `color`/`icon`/`authoring`/etc.
  // (plan 005 Task 2's restatement of plan 002's prohibition).
  http.get('*/cp/solutions', () =>
    HttpResponse.json({
      items: withInstanceCounts(SOLUTIONS, CACHE_INSTANCES).map((pkg) => ({
        solutionId: pkg.id,
        solution: pkg.solution,
        name: pkg.name,
        packageVersions: pkg.packageVersion,
        latestPackageVersion: pkg.packageVersion[pkg.packageVersion.length - 1],
        signature: 'unsigned' as const,
        stateVersion: 1,
      })),
    }),
  ),
  // `loadPackage` (`console.yaml`): the request body is `{ package: <document> }` — the
  // full canonical Solution Package, not file metadata. v1 loads no signed packages, so
  // `signature` is always `unsigned` (console.yaml `Solution.signature`); there is no
  // server-side verified/devMode distinction to emulate (see `solutionsRepository.ts`'s
  // header for why the pre-005 fixture shape invented one).
  // `getSolutionPackage` (D-4, resolved 2026-10-09): the raw document, reach-scoped (no
  // per-item filter to apply — a catalog entry has none of its own), `packageVersion`
  // query param optional and defaulting to the entry's latest. `packageForSolution` keys
  // on the fixture `id`, not a real UUID, so this stays a direct lookup rather than a
  // version-aware one — the fixture layer has one package per solution, not per version.
  http.get('*/cp/solutions/:solutionId/package', ({ params }) => {
    const solutionId = String(params['solutionId']);
    const pkg = packageForSolution(solutionId);
    if (pkg === undefined) {
      return new HttpResponse(null, { status: 404 });
    }
    return HttpResponse.json({
      stateVersion: 1,
      solutionId,
      packageVersion: pkg.packageVersion[pkg.packageVersion.length - 1] ?? 1,
      package: pkg,
    });
  }),
  http.post('*/cp/solutions/load', async ({ request: req }) => {
    const body = (await req.json()) as {
      package?: { solution?: string; packageVersion?: readonly (string | number)[] };
    };
    const pkg = body.package ?? {};
    return HttpResponse.json(
      {
        solutionId: `loaded-${pkg.solution ?? 'unknown'}`,
        packageVersion: Array.isArray(pkg.packageVersion) ? pkg.packageVersion : [1],
        signature: 'unsigned' as const,
        stateVersion: 1,
      },
      { status: 201 },
    );
  }),

  // ---- Targets — console.yaml `listTargets` (read-only; plan 005 Task 3) ----
  // Mapped to the `TargetList` envelope of `Target` rows; the fixture's `location`/
  // `driver`/`locus` have no counterpart on `Target` and are dropped, not invented.
  // `ownershipScope` is CONTRACT: TBD here — the fixture only has display-name path
  // segments, not the `ScopePath` ids `Target.ownershipScope` requires, so it is left
  // empty rather than fabricating ids.
  http.get('*/cp/targets', () =>
    HttpResponse.json({
      items: TARGETS.map((target) => ({
        targetId: target.id,
        name: target.id,
        kind: 'docker' as const,
        transport: 'ssh-docker' as const,
        ownershipScope: {},
      })),
    }),
  ),

  // ---- Connection Objects — real `console.yaml` `connections` tag (plan 013 Task 7) ----
  // Six operations, the real wire shape. `I5`: `listConnections` takes no query parameters at all,
  // and no row here carries a filter the client could have asked for — `connectionsRepository.test.ts`
  // pins exactly that. A GET that DOES carry `?type=`/`?status=` can only be the pre-Task-5 Designer
  // drift-detection suite (`devPackageFlow.test.ts`, UI-D27/28/29), which still talks to the OLD fixture
  // shape/store (`connectionRows`) directly over HTTP — not merely via `getConnectionRows()` — so it is
  // answered from that store, unchanged, rather than broken by this task.
  http.get('*/cp/connections', ({ request }) => {
    const url = new URL(request.url);
    const type = url.searchParams.get('type');
    const status = url.searchParams.get('status');
    if (type !== null || status !== null) {
      return HttpResponse.json(
        connectionRows.filter(
          (row) =>
            (type === null || row.connectionType === type) &&
            (status === null || row.status === status),
        ),
      );
    }
    return HttpResponse.json({ items: realConnectionRows });
  }),
  // Legacy PUT (old Designer drift-detection fixture, OLD store) — distinct from the real PATCH below,
  // so the two never collide on method.
  http.put('*/cp/connections/:id', async ({ params, request }) => {
    const id = String(params['id']);
    const input = (await request.json()) as {
      name: string;
      attributes: Record<string, unknown>;
    };
    const current = connectionRows.find((row) => row.id === id);
    if (current === undefined) {
      return HttpResponse.json(
        { code: 'NOT_FOUND', message: 'No such connection.' },
        { status: 404 },
      );
    }
    const updated: ConnectionRow = {
      ...current,
      name: input.name,
      attributes: input.attributes,
      revision: current.revision + 1,
      updatedAt: new Date().toISOString(),
      updatedBy: 'devon.ramirez@acme.example',
    };
    connectionRows = connectionRows.map((row) => (row.id === id ? updated : row));
    return HttpResponse.json(updated);
  }),
  http.post('*/cp/connections', async ({ request }) => {
    const input = (await request.json()) as {
      name: string;
      connectionType: RealConnectionTypeKey;
      ownershipScope?: Record<string, string>;
      attributes: Record<string, unknown>;
      testBeforeSave?: boolean;
    };
    if (input.testBeforeSave === true && String(input.attributes['host']).includes('unreachable')) {
      return HttpResponse.json(
        {
          type: 'about:blank',
          title: 'Service unavailable',
          status: 503,
          code: 'SERVICE_UNAVAILABLE',
          correlationId: '11111111-1111-1111-1111-111111111111',
        },
        { status: 503 },
      );
    }
    if (input.testBeforeSave === true && String(input.attributes['host']).includes('refuses')) {
      return HttpResponse.json(
        {
          type: 'about:blank',
          title: 'Validation failed',
          status: 422,
          code: 'CONNECTION_TEST_FAILED',
          correlationId: '11111111-1111-1111-1111-111111111111',
          detail: 'Connection refused by peer.',
          name: input.name,
          attributes: input.attributes,
        },
        { status: 422 },
      );
    }
    const now = new Date().toISOString();
    const descriptor = DESCRIPTORS[input.connectionType];
    const secretKeys = new Set(
      descriptor.attributes.filter((attribute) => attribute.secret === true).map((attribute) => attribute.key),
    );
    const attributes: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(input.attributes)) {
      attributes[key] = secretKeys.has(key) ? { set: true } : value;
    }
    const row: RealConnectionRow = {
      connectionId: nextRealConnectionId(),
      name: input.name,
      connectionType: input.connectionType,
      ownershipScope: input.ownershipScope ?? {},
      attributes,
      active: true,
      usedByCount: 0,
      createdAt: now,
      updatedAt: now,
    };
    realConnectionRows = [...realConnectionRows, row];
    return HttpResponse.json(row, { status: 201 });
  }),
  http.patch('*/cp/connections/:id', async ({ params, request }) => {
    const id = String(params['id']);
    const input = (await request.json()) as {
      name?: string;
      attributes?: Record<string, unknown>;
      testBeforeSave?: boolean;
    };
    const current = realConnectionRows.find((row) => row.connectionId === id);
    if (current === undefined) {
      return HttpResponse.json(
        { code: 'NOT_FOUND', message: 'No such connection.' },
        { status: 404 },
      );
    }
    const descriptor = DESCRIPTORS[current.connectionType];
    const secretKeys = new Set(
      descriptor.attributes.filter((attribute) => attribute.secret === true).map((attribute) => attribute.key),
    );
    const mergedAttributes: Record<string, unknown> = { ...current.attributes };
    for (const [key, value] of Object.entries(input.attributes ?? {})) {
      mergedAttributes[key] = secretKeys.has(key) ? { set: true } : value;
    }
    const updated: RealConnectionRow = {
      ...current,
      ...(input.name === undefined ? {} : { name: input.name }),
      attributes: mergedAttributes,
      updatedAt: new Date().toISOString(),
    };
    realConnectionRows = realConnectionRows.map((row) => (row.connectionId === id ? updated : row));
    return HttpResponse.json(updated);
  }),
  http.post('*/cp/connections/:id/deactivate', ({ params }) => {
    const id = String(params['id']);
    const current = realConnectionRows.find((row) => row.connectionId === id);
    if (current === undefined) {
      return HttpResponse.json(
        { code: 'NOT_FOUND', message: 'No such connection.' },
        { status: 404 },
      );
    }
    const updated = { ...current, active: false, updatedAt: new Date().toISOString() };
    realConnectionRows = realConnectionRows.map((row) => (row.connectionId === id ? updated : row));
    return HttpResponse.json(updated);
  }),
  http.delete('*/cp/connections/:id', ({ params }) => {
    const id = String(params['id']);
    const current = realConnectionRows.find((row) => row.connectionId === id);
    if (current !== undefined && current.usedByCount > 0) {
      return HttpResponse.json(
        { code: 'CONFLICT', message: `referenced by ${String(current.usedByCount)} instance(s)` },
        { status: 409 },
      );
    }
    realConnectionRows = realConnectionRows.filter((row) => row.connectionId !== id);
    return new HttpResponse(null, { status: 204 });
  }),
  http.get('*/cp/connection-types', () => HttpResponse.json(Object.values(DESCRIPTORS))),

  // ---- Admin / RBAC (CONTRACT: TBD, §16 Q4/Q5) ----
  http.get('*/cp/admin/permission-catalog', () => HttpResponse.json(PERMISSION_CATALOG)),
  http.get('*/cp/admin/roles', () => HttpResponse.json(RBAC_ROLES)),
  http.post('*/cp/admin/roles', async ({ request }) => {
    const body = (await request.json()) as {
      name: string;
      description: string;
      permissionIds: string[];
    };
    return HttpResponse.json({
      id: `custom-${body.name.toLowerCase().replace(/\s+/g, '-')}`,
      name: body.name,
      kind: 'custom' as const,
      description: body.description,
      permissionIds: body.permissionIds,
    });
  }),
  // ---- Hierarchy cascade (`console.yaml` `listOrganizations` / `listSubOrganizations` /
  // `listEnvironments`) — backs `shared/hierarchy/hierarchyRepository`. Three routes rather than one
  // tree, so the dependent selection performs real loads. Responses carry the server's
  // `ScopeNodeList` envelope (`{ items: [{ id, name }] }`, no `stateVersion` — OR-38) and are
  // addressed by node ID, exactly as the real gateway is; a bare name array here makes the
  // repository throw in no-backend mode.
  http.get('*/cp/hierarchy/organizations', () => HttpResponse.json({ items: organizationNodes() })),
  http.get('*/cp/hierarchy/organizations/:org/sub-orgs', ({ params }) =>
    HttpResponse.json({ items: subOrgNodesOf(String(params['org'])) }),
  ),
  http.get('*/cp/hierarchy/organizations/:org/sub-orgs/:subOrg/environments', ({ params }) =>
    HttpResponse.json({
      items: environmentNodesOf(String(params['org']), String(params['subOrg'])),
    }),
  ),

  http.get('*/cp/admin/principals', () => HttpResponse.json(rbac.listPrincipals())),
  http.get('*/cp/admin/scope-tree', () => HttpResponse.json(SCOPE_TREE)),
  http.get('*/cp/admin/assignments', () => HttpResponse.json(rbac.listAssignments())),
  http.post('*/cp/admin/assignments', async ({ request }) => {
    const body = (await request.json()) as { principalId: string; roleId: string; scope: string[] };
    // The duplicate rule is enforced HERE as well as in the dialog (§7) —
    // a client-only check is a race, not a rule.
    if (rbac.assignmentExists(body)) {
      return HttpResponse.json(
        { code: 'CONFLICT', message: 'That principal already holds that role at that scope.' },
        { status: 409 },
      );
    }
    return HttpResponse.json(rbac.createAssignment(body));
  }),
  http.delete('*/cp/admin/assignments/:id', ({ params }) => {
    rbac.revokeAssignment(String(params['id']));
    return new HttpResponse(null, { status: 204 });
  }),
  // Effective permissions at a scope: every ACTIVE assignment whose scope
  // REACHES the queried scope, taken from the principal's own assignments
  // AND from every active group they belong to, each role expanded to its
  // permissions and `'*'` expanded to the whole catalog. A grant made at
  // the queried scope is `direct`; one made above it is `inherited`; `via`
  // names whether it arrived directly or through a group. See
  // `rbacStore.effectiveGrantsFor` for the union rule.
  http.get('*/cp/admin/effective-permissions', ({ request }) => {
    const url = new URL(request.url);
    const principalId = url.searchParams.get('principalId') ?? '';
    const scopeParam = url.searchParams.get('scope') ?? '';
    const scope = scopeParam === '' ? [] : scopeParam.split('/');
    return HttpResponse.json(rbac.effectiveGrantsFor(principalId, scope));
  }),

  // ---- Groups (CONTRACT: TBD, §16 Q4/Q5) ----
  http.get('*/cp/admin/groups', () => HttpResponse.json(rbac.listGroups())),
  http.post('*/cp/admin/groups', async ({ request }) => {
    const body = (await request.json()) as rbac.GroupWrite;
    if (rbac.groupNameTaken(body.name, body.organization)) {
      return HttpResponse.json(
        { code: 'CONFLICT', message: 'A group with that name already exists here.' },
        { status: 409 },
      );
    }
    return HttpResponse.json(rbac.createGroup(body));
  }),
  http.get('*/cp/admin/groups/:groupId', ({ params }) => {
    const group = rbac.findGroup(String(params['groupId']));
    return group === undefined
      ? HttpResponse.json({ code: 'NOT_FOUND', message: 'No such group.' }, { status: 404 })
      : HttpResponse.json(group);
  }),
  http.patch('*/cp/admin/groups/:groupId', async ({ params, request }) => {
    const groupId = String(params['groupId']);
    const body = (await request.json()) as Partial<rbac.GroupWrite>;
    const existing = rbac.findGroup(groupId);
    if (existing === undefined) {
      return HttpResponse.json({ code: 'NOT_FOUND', message: 'No such group.' }, { status: 404 });
    }
    if (body.name !== undefined && rbac.groupNameTaken(body.name, existing.organization, groupId)) {
      return HttpResponse.json(
        { code: 'CONFLICT', message: 'A group with that name already exists here.' },
        { status: 409 },
      );
    }
    return HttpResponse.json(rbac.updateGroup(groupId, body));
  }),
  http.post('*/cp/admin/groups/:groupId/deactivate', ({ params }) =>
    HttpResponse.json(rbac.setGroupStatus(String(params['groupId']), 'deactivated')),
  ),
  http.post('*/cp/admin/groups/:groupId/reactivate', ({ params }) =>
    HttpResponse.json(rbac.setGroupStatus(String(params['groupId']), 'active')),
  ),
  http.get('*/cp/admin/groups/:groupId/members', ({ params }) =>
    HttpResponse.json(rbac.listMembers(String(params['groupId']))),
  ),
  http.post('*/cp/admin/groups/:groupId/members', async ({ params, request }) => {
    const groupId = String(params['groupId']);
    const body = (await request.json()) as { userIds: string[] };
    for (const userId of body.userIds) rbac.addMember(groupId, userId);
    return HttpResponse.json(rbac.listMembers(groupId));
  }),
  http.delete('*/cp/admin/groups/:groupId/members/:userId', ({ params }) => {
    rbac.removeMember(String(params['groupId']), String(params['userId']));
    return new HttpResponse(null, { status: 204 });
  }),
  http.get('*/cp/admin/groups/:groupId/assignments', ({ params }) =>
    HttpResponse.json(rbac.listGroupAssignments(String(params['groupId']))),
  ),

  http.get('*/cp/admin/users', () => HttpResponse.json(rbac.listUsers())),
  http.post('*/cp/admin/users', async ({ request }) => {
    const body = (await request.json()) as {
      name: string;
      email: string;
      organization: string;
      credentialPath: string;
    };
    return HttpResponse.json(rbac.createUser(body));
  }),
  http.patch('*/cp/admin/users/:id', async ({ params, request }) => {
    const body = (await request.json()) as { active: boolean };
    // `409` when the last active Bootstrap Super Admin would be
    // deactivated (`D19` 🔒, REST contract §13.11). The UI disables the
    // control as well; this is the half that is a rule rather than a hint.
    if (!rbac.setUserActive(String(params['id']), body.active)) {
      return HttpResponse.json(
        {
          code: 'CONFLICT',
          message:
            'The last active bootstrap super admin cannot be deactivated. Grant another account the super-admin role at the root scope first.',
        },
        { status: 409 },
      );
    }
    return new HttpResponse(null, { status: 204 });
  }),

  // ---- Auth (CONTRACT: TBD, §16 Q3) — backs shared/auth/localSessionAdapter ----
  http.get('*/cp/auth/identity', () =>
    HttpResponse.json({
      name: 'Devon Ramirez',
      email: 'devon.ramirez@acme.example',
      role: 'Operator',
      organization: 'Acme Financial · self-hosted',
      superAdmin: true,
      permissions: PERMISSION_CATALOG.map((permission) => permission.id),
    }),
  ),
  http.post('*/cp/auth/login', () =>
    HttpResponse.json({
      name: 'Devon Ramirez',
      email: 'devon.ramirez@acme.example',
      role: 'Operator',
      organization: 'Acme Financial · self-hosted',
      superAdmin: true,
      permissions: PERMISSION_CATALOG.map((permission) => permission.id),
    }),
  ),
  http.post('*/cp/auth/logout', () => new HttpResponse(null, { status: 204 })),
];
