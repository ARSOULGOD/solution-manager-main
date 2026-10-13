/**
 * The RBAC fixture's mutable state (Groups management).
 *
 * Before this, the admin handlers were write-only theatre in exactly the
 * way `dashboardStore.ts` describes: `POST /admin/users` echoed an invented
 * row and dropped it, `PATCH /admin/users/:id` returned `204` and changed
 * nothing, and `POST /admin/assignments` returned an id nobody could ever
 * read back. Groups cannot be built on that — "add a member, watch the
 * user's effective permissions change" is the whole feature, and it only
 * lands if the writes are remembered.
 *
 * Same posture as `dashboardStore.ts`: module-level state behind named
 * functions, no top-level side effects, memory-only. **Reset between tests
 * is mandatory** — `server.resetHandlers()` restores handlers, never the
 * module state they close over — so `resetRbacStore()` is called from
 * `src/test/setup.ts`'s `afterEach` alongside the other two.
 *
 * ⚠️ This is a FIXTURE, not an authorization engine. The server is the
 * security boundary (design §15); what this file models is the *shape* of
 * the answer the backend will give, so the screens can be built and proved
 * against it while `AdminRepository` stays `CONTRACT: TBD` (§16 Q4/Q5).
 */
import { PERMISSION_CATALOG } from '@shared/authz/catalog';

import { GROUP_MEMBERSHIPS, LOCAL_GROUPS } from '../data/groups';
import type { GroupMember, UserGroup } from '../data/groups';
import { LOCAL_USERS, RBAC_ASSIGNMENTS, RBAC_ROLES } from '../data/rbac';
import type { LocalUser, RbacAssignment, RbacPrincipal } from '../data/rbac';

/**
 * Who the fixture believes is signed in. The real server takes the actor
 * from the session; this is the one place the fixture invents one, so
 * `createdBy` / `addedBy` / `assignedBy` are never fabricated at the call
 * site.
 */
const ACTOR = 'Devon Ramirez';

interface RbacState {
  users: LocalUser[];
  groups: UserGroup[];
  memberships: GroupMember[];
  assignments: RbacAssignment[];
}

function seed(): RbacState {
  return {
    users: LOCAL_USERS.map((user) => ({ ...user })),
    groups: LOCAL_GROUPS.map((group) => ({ ...group })),
    memberships: GROUP_MEMBERSHIPS.map((member) => ({ ...member })),
    assignments: RBAC_ASSIGNMENTS.map((assignment) => ({
      ...assignment,
      scope: [...assignment.scope],
    })),
  };
}

let state: RbacState = seed();

export function resetRbacStore(): void {
  state = seed();
}

/** A stable, monotonic id — `Date.now()` collides inside one synchronous test. */
let sequence = 0;
function nextId(prefix: string): string {
  sequence += 1;
  return `${prefix}-${String(sequence)}`;
}

function now(): string {
  return new Date().toISOString();
}

// ── Reads ────────────────────────────────────────────────────────────────

export function listUsers(): LocalUser[] {
  return state.users.map((user) => ({ ...user }));
}

/**
 * `memberCount` / `assignmentCount` are DERIVED on every read rather than
 * stored, for the reason FE-120 records against `instanceCount`: a stored
 * count is a second source of truth that drifts the first time a write
 * forgets to update it.
 */
export function listGroups(): UserGroup[] {
  return state.groups.map((group) => ({
    ...group,
    memberCount: state.memberships.filter((member) => member.groupId === group.id).length,
    assignmentCount: state.assignments.filter(
      (assignment) => assignment.principalId === group.id && assignment.status === 'active',
    ).length,
  }));
}

export function findGroup(groupId: string): UserGroup | undefined {
  return listGroups().find((group) => group.id === groupId);
}

export function listMembers(groupId: string): GroupMember[] {
  return state.memberships
    .filter((member) => member.groupId === groupId)
    .map((member) => ({ ...member }));
}

/** Every assignment, revoked ones included — the audit record outlives the grant. */
export function listAssignments(): RbacAssignment[] {
  return state.assignments.map((assignment) => ({ ...assignment, scope: [...assignment.scope] }));
}

export function listGroupAssignments(groupId: string): RbacAssignment[] {
  return listAssignments().filter((assignment) => assignment.principalId === groupId);
}

/**
 * Principals are DERIVED from the users and groups tables — never a third
 * hand-written list. A group created on the Groups screen is therefore
 * immediately assignable on the Assignments screen, which is the whole
 * point of §7 of the brief.
 */
export function listPrincipals(): RbacPrincipal[] {
  const users: RbacPrincipal[] = state.users.map((user) => ({
    id: user.id,
    kind: 'user',
    name: user.name,
    sub: user.email,
    source: 'local',
    status: user.status,
  }));
  const groups: RbacPrincipal[] = listGroups().map((group) => ({
    id: group.id,
    kind: 'group',
    name: group.name,
    sub:
      group.source === 'idp'
        ? `IdP group · ${group.externalId ?? 'synchronized'}`
        : `${group.organization} · ${String(group.memberCount)} members`,
    source: group.source,
    status: group.status === 'active' ? 'active' : 'deactivated',
    organization: group.organization,
  }));
  return [...users, ...groups];
}

// ── Writes: groups ───────────────────────────────────────────────────────

export interface GroupWrite {
  name: string;
  description?: string;
  organization: string;
  memberIds?: readonly string[];
}

/** Case-insensitive uniqueness WITHIN one organization — the rule §3 states. */
export function groupNameTaken(name: string, organization: string, exceptId?: string): boolean {
  const wanted = name.trim().toLocaleLowerCase();
  return state.groups.some(
    (group) =>
      group.id !== exceptId &&
      group.organization === organization &&
      group.name.trim().toLocaleLowerCase() === wanted,
  );
}

export function createGroup(draft: GroupWrite): UserGroup {
  const timestamp = now();
  const group: UserGroup = {
    id: nextId('grp'),
    name: draft.name.trim(),
    ...(draft.description !== undefined && draft.description.trim() !== ''
      ? { description: draft.description.trim() }
      : {}),
    organization: draft.organization,
    status: 'active',
    source: 'local',
    memberCount: 0,
    assignmentCount: 0,
    createdBy: ACTOR,
    createdAt: timestamp,
    updatedAt: timestamp,
  };
  state.groups = [group, ...state.groups];
  for (const userId of draft.memberIds ?? []) {
    addMember(group.id, userId);
  }
  return findGroup(group.id) as UserGroup;
}

export function updateGroup(groupId: string, patch: Partial<GroupWrite>): UserGroup | undefined {
  const index = state.groups.findIndex((group) => group.id === groupId);
  if (index === -1) return undefined;
  const current = state.groups[index] as UserGroup;
  const next: UserGroup = {
    ...current,
    ...(patch.name !== undefined ? { name: patch.name.trim() } : {}),
    updatedBy: ACTOR,
    updatedAt: now(),
  };
  // Cleared descriptions are DELETED rather than set to `undefined` —
  // `exactOptionalPropertyTypes` treats the two as different types.
  if (patch.description !== undefined) {
    if (patch.description.trim() === '') delete next.description;
    else next.description = patch.description.trim();
  }
  state.groups[index] = next;
  if (patch.memberIds !== undefined) {
    setMembers(groupId, patch.memberIds);
  }
  return findGroup(groupId);
}

/** Deactivate / reactivate — a group is never hard-deleted (§2). */
export function setGroupStatus(
  groupId: string,
  status: UserGroup['status'],
): UserGroup | undefined {
  const index = state.groups.findIndex((group) => group.id === groupId);
  if (index === -1) return undefined;
  state.groups[index] = {
    ...(state.groups[index] as UserGroup),
    status,
    updatedBy: ACTOR,
    updatedAt: now(),
  };
  return findGroup(groupId);
}

// ── Writes: membership ───────────────────────────────────────────────────

export function addMember(groupId: string, userId: string): GroupMember | undefined {
  const group = state.groups.find((candidate) => candidate.id === groupId);
  const user = state.users.find((candidate) => candidate.id === userId);
  if (group === undefined || user === undefined) return undefined;
  // Cross-organization membership is refused here as well as in the UI —
  // a fixture that accepts what the server must reject teaches the screen
  // the wrong lesson.
  if (user.organization !== group.organization) return undefined;
  const existing = state.memberships.find(
    (member) => member.groupId === groupId && member.userId === userId,
  );
  if (existing !== undefined) return { ...existing };
  const member: GroupMember = { groupId, userId, addedBy: ACTOR, addedAt: now() };
  state.memberships = [...state.memberships, member];
  return { ...member };
}

export function removeMember(groupId: string, userId: string): void {
  state.memberships = state.memberships.filter(
    (member) => !(member.groupId === groupId && member.userId === userId),
  );
}

function setMembers(groupId: string, userIds: readonly string[]): void {
  const wanted = new Set(userIds);
  for (const member of listMembers(groupId)) {
    if (!wanted.has(member.userId)) removeMember(groupId, member.userId);
  }
  for (const userId of userIds) addMember(groupId, userId);
}

// ── Writes: users ────────────────────────────────────────────────────────

export function createUser(draft: {
  name: string;
  email: string;
  organization: string;
}): LocalUser {
  const user: LocalUser = {
    id: nextId('u'),
    name: draft.name.trim(),
    email: draft.email.trim(),
    status: 'active',
    lastSignIn: 'never',
    createdBy: ACTOR,
    organization: draft.organization,
  };
  state.users = [...state.users, user];
  return { ...user };
}

/**
 * How many Bootstrap Super Admins are currently active (`D19` 🔒). One is
 * the invariant an install starts with; zero is unrecoverable from this
 * console, which is why the write below refuses to produce it.
 */
function activeBootstrapAdminCount(): number {
  return state.users.filter((user) => user.isBootstrapAdmin === true && user.status === 'active')
    .length;
}

/**
 * Returns `false` when the write was REFUSED — the only refusal is
 * deactivating the last active Bootstrap Super Admin (`D19` 🔒, `FE-D50`).
 * The handler answers `409` on a refusal, which is what §13.11 of the REST
 * contract already documents for this case.
 *
 * Reactivation is never refused; the rule only protects against the count
 * reaching zero.
 */
export function setUserActive(userId: string, active: boolean): boolean {
  const user = state.users.find((candidate) => candidate.id === userId);
  if (user === undefined) return false;
  if (!active && user.isBootstrapAdmin === true && activeBootstrapAdminCount() <= 1) {
    return false;
  }
  state.users = state.users.map((candidate) =>
    candidate.id === userId
      ? { ...candidate, status: active ? 'active' : 'deactivated' }
      : candidate,
  );
  return true;
}

// ── Writes: assignments ──────────────────────────────────────────────────

export interface AssignmentWrite {
  principalId: string;
  roleId: string;
  scope: readonly string[];
}

function samePath(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((segment, index) => segment === b[index]);
}

/** Duplicate = same principal, same role, same EXACT scope, still active (§7). */
export function assignmentExists(draft: AssignmentWrite): boolean {
  return state.assignments.some(
    (assignment) =>
      assignment.status === 'active' &&
      assignment.principalId === draft.principalId &&
      assignment.roleId === draft.roleId &&
      samePath(assignment.scope, draft.scope),
  );
}

export function createAssignment(draft: AssignmentWrite): RbacAssignment {
  const assignment: RbacAssignment = {
    id: nextId('a'),
    principalId: draft.principalId,
    roleId: draft.roleId,
    scope: [...draft.scope],
    status: 'active',
    assignedBy: ACTOR,
    assignedAt: now(),
  };
  state.assignments = [...state.assignments, assignment];
  return { ...assignment, scope: [...assignment.scope] };
}

/** Revoke, never delete — see `RbacAssignment.status`. */
export function revokeAssignment(assignmentId: string): void {
  state.assignments = state.assignments.map((assignment) =>
    assignment.id === assignmentId ? { ...assignment, status: 'revoked' } : assignment,
  );
}

// ── Effective permissions ────────────────────────────────────────────────

export interface StoredGrantVia {
  kind: 'direct' | 'group';
  groupId?: string;
  groupName?: string;
}

export interface StoredGrant {
  permissionId: string;
  roleId: string;
  grantType: 'direct' | 'inherited';
  grantingScope: readonly string[];
  via: StoredGrantVia;
}

const ALL_PERMISSION_IDS = PERMISSION_CATALOG.map((entry) => entry.id);

function reaches(grantScope: readonly string[], scope: readonly string[]): boolean {
  return (
    grantScope.length <= scope.length &&
    grantScope.every((segment, index) => segment === scope[index])
  );
}

/**
 * Additive allow, deduplicated union (§8). Three sources, one algebra:
 *
 * 1. the principal's own active assignments,
 * 2. the active assignments of every ACTIVE group they are a member of,
 * 3. inheritance — an assignment at an ancestor scope reaches this one.
 *
 * There is no deny rule, because the approved RBAC model (`D10` 🔒) has
 * none, and inventing a group-level one here would have made the frontend
 * fixture disagree with the server it stands in for.
 *
 * A deactivated user or group confers nothing; its assignment rows stay
 * readable for audit.
 */
export function effectiveGrantsFor(principalId: string, scope: readonly string[]): StoredGrant[] {
  const user = state.users.find((candidate) => candidate.id === principalId);
  const group = state.groups.find((candidate) => candidate.id === principalId);

  if (user !== undefined && user.status !== 'active') return [];
  if (group !== undefined && group.status !== 'active') return [];
  if (user === undefined && group === undefined) return [];

  const sources: { principalId: string; via: StoredGrantVia }[] = [
    { principalId, via: { kind: 'direct' } },
  ];

  if (user !== undefined) {
    for (const member of state.memberships.filter((row) => row.userId === principalId)) {
      const memberGroup = state.groups.find((candidate) => candidate.id === member.groupId);
      if (memberGroup === undefined || memberGroup.status !== 'active') continue;
      sources.push({
        principalId: memberGroup.id,
        via: { kind: 'group', groupId: memberGroup.id, groupName: memberGroup.name },
      });
    }
  }

  const grants: StoredGrant[] = [];
  const seen = new Set<string>();

  for (const source of sources) {
    const applicable = state.assignments.filter(
      (assignment) =>
        assignment.status === 'active' &&
        assignment.principalId === source.principalId &&
        reaches(assignment.scope, scope),
    );
    for (const assignment of applicable) {
      const role = RBAC_ROLES.find((candidate) => candidate.id === assignment.roleId);
      if (role === undefined) continue;
      const permissionIds =
        role.permissionIds === '*' ? ALL_PERMISSION_IDS : [...role.permissionIds];
      for (const permissionId of permissionIds) {
        const key = [
          permissionId,
          assignment.roleId,
          assignment.scope.join('/'),
          source.via.kind,
          source.via.groupId ?? '',
        ].join('|');
        if (seen.has(key)) continue;
        seen.add(key);
        grants.push({
          permissionId,
          roleId: assignment.roleId,
          grantType: samePath(assignment.scope, scope) ? 'direct' : 'inherited',
          grantingScope: [...assignment.scope],
          via: source.via,
        });
      }
    }
  }

  return grants;
}
