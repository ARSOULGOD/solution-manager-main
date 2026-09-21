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

import { environmentsOf, organizationNames, subOrgNamesOf } from '../data/hierarchy';
import { ATTENTION_FEED, CACHE_INSTANCES } from '../data/instances';
import { RBAC_ROLES, SCOPE_TREE } from '../data/rbac';
import { SOLUTIONS, withInstanceCounts } from '../data/solutions';
import { TARGETS } from '../data/targets';

import * as rbac from './rbacStore';

export const cpHandlers = [
  // ---- Home (CONTRACT: TBD, §16 Q10) ----
  http.get('*/cp/home/summary', () =>
    HttpResponse.json({
      solutionsManaged: SOLUTIONS.length,
      instancesDeclared: CACHE_INSTANCES.length,
      needAttentionNow: ATTENTION_FEED.filter((a) => a.severity === 'err').length,
      configStagedNotApplied: CACHE_INSTANCES.filter((i) => i.configState === 'staged').length,
    }),
  ),
  http.get('*/cp/home/attention', () => HttpResponse.json(ATTENTION_FEED)),

  // ---- Solutions (CONTRACT: TBD, §16 Q10) ----
  // `instanceCount` is DERIVED, never the fixture's own number (FE-120) —
  // see `withInstanceCounts`. The default handler set is stateless, so the
  // instance list here is the fixture one.
  http.get('*/cp/solutions', () =>
    HttpResponse.json(withInstanceCounts(SOLUTIONS, CACHE_INSTANCES)),
  ),
  // A filename containing "dev"/"nightly"/"unsigned" returns the unsigned
  // dev-mode result — a naming convention this handler invents so the
  // FE-040 admission path is reachable from a real dev-server drop/pick,
  // not just from a directly-injected fake repository in a unit test.
  http.post('*/cp/solutions/load', async ({ request: req }) => {
    const body = (await req.json()) as { fileName?: string };
    const unsigned = /dev|nightly|unsigned/i.test(body.fileName ?? '');
    return HttpResponse.json(
      unsigned
        ? {
            verified: false,
            devMode: true,
            message: 'No Solace signature — admitted only in an explicit development mode.',
          }
        : {
            verified: true,
            devMode: false,
            signatureFingerprint: '3af9…c210',
            message: 'Signature verified · Solace-signed',
          },
    );
  }),

  // ---- Targets (read-only) ----
  http.get('*/cp/targets', () => HttpResponse.json(TARGETS)),

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
  // ---- Hierarchy cascade (CONTRACT: TBD, `FE-D46`) — backs
  // `shared/hierarchy/hierarchyRepository`. Three routes rather than one
  // tree, so the dependent selection performs real loads.
  http.get('*/cp/hierarchy/organizations', () => HttpResponse.json(organizationNames())),
  http.get('*/cp/hierarchy/organizations/:org/sub-orgs', ({ params }) =>
    HttpResponse.json(subOrgNamesOf(String(params['org']))),
  ),
  http.get('*/cp/hierarchy/organizations/:org/sub-orgs/:subOrg/environments', ({ params }) =>
    HttpResponse.json(environmentsOf(String(params['org']), String(params['subOrg']))),
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
