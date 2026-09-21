/**
 * `AdminRepository` (task 033, §6.0.5, §9.2, UI §A.12). CONTRACT: TBD —
 * everything (§16 Q4: the verb catalog is unratified; §16 Q5: first-admin
 * bootstrap is unspecified). `bootstrapFirstAdmin()` is intentionally
 * ABSENT — do not invent one.
 *
 * `createUser` (FE-094) carries `credentialPath` — resolved by `FE-D38`
 * to the admin-set temporary credential, forced-change-on-first-sign-in
 * path. The other two values exist so the request shape does not move if
 * the decision is revisited; the backend contract is still TBD (§16 Q5).
 *
 * ⚠️ **AUDIT IS AN UNRESOLVED DEPENDENCY (Groups management §13).** Every
 * auditable event this feature produces — group created / updated /
 * deactivated / reactivated, member added / removed, role assigned to a
 * group, assignment revoked — is a MUTATION on this interface, and this
 * interface is deliberately the only way a Groups screen writes anything.
 * That makes the repository the audit seam: when the backend defines an
 * audit contract, the actor, action, target, timestamp, organization,
 * scope and before/after values are recorded by the server handling these
 * same calls, with no change to any screen.
 *
 * **No client-side audit is written here, and none should be.** An audit
 * record produced by the client is a record the client could omit — the
 * server is the only place it is trustworthy (design §15, `I5`). What is
 * missing is the backend's audit contract itself, not a frontend call:
 * `contracts/` does not exist and `AdminRepository` is `CONTRACT: TBD`.
 * The `correlationId` every mutation already returns (`FE-D13`) is the
 * key that ties a UI outcome to that future server-side record.
 */
import type { PlaneClient } from '@shared/api';
import type { PermissionDef } from '@shared/authz/permissions';
import type { GroupMember, UserGroup } from '@shared/testdoubles/data/groups';
import type {
  LocalUser,
  RbacAssignment,
  RbacPrincipal,
  RbacRole,
  ScopeNode,
} from '@shared/testdoubles/data/rbac';

export interface RoleDraft {
  id?: string;
  name: string;
  description: string;
  permissionIds: readonly string[];
}

export interface AssignmentDraft {
  principalId: string;
  roleId: string;
  scope: readonly string[];
}

/**
 * Which principal's assignment conferred a grant (Groups management, §8).
 * `direct` = the queried principal's own assignment. `group` = an
 * assignment held by a group they are a member of, named so the Access
 * check can answer "why do I have this?" with something an administrator
 * can act on. Nested groups are out of scope for this version, so this is
 * one level deep by construction, not by omission.
 */
export type GrantVia = { kind: 'direct' } | { kind: 'group'; groupId: string; groupName: string };

export interface EffectiveGrant {
  permissionId: string;
  roleId: string;
  grantType: 'direct' | 'inherited';
  grantingScope: readonly string[];
  via: GrantVia;
}

/**
 * The saved role plus the server's correlation id — the role editor
 * reports its own outcome as a toast, and `ToastInput.correlationId` is
 * required (`FE-D13`), never client-fabricated. Same shape as
 * `DeclareInstanceResult` (task 115).
 */
export interface SaveRoleResult {
  role: RbacRole;
  correlationId: string;
}

/**
 * The created assignment plus the server's correlation id — the New
 * assignment dialog reports its own outcome, and `ToastInput.correlationId`
 * is required (`FE-D13`), never client-fabricated. Same shape as
 * `SaveRoleResult` (task 091).
 */
export interface CreateAssignmentResult {
  assignment: RbacAssignment;
  correlationId: string;
}

/** How a new local user's first credential is delivered (`FE-D38`, §16 Q5). */
export type CredentialPath = 'temp' | 'invite' | 'oob';

export interface CreateUserDraft {
  name: string;
  email: string;
  /** The organization the account belongs to — the top of the hierarchy (`FE-D46`). */
  organization: string;
  credentialPath: CredentialPath;
}

/** Create or edit a group. `organization` is set once, at creation (§4). */
export interface GroupDraft {
  name: string;
  description: string;
  organization: string;
  /** Initial members. Only users of `organization` are accepted. */
  memberIds: readonly string[];
}

/** Edit: organization is deliberately absent — it is immutable after creation (§4). */
export interface GroupEditDraft {
  name: string;
  description: string;
  memberIds: readonly string[];
}

/**
 * The saved group plus the server's correlation id — every write on the
 * Groups screens reports its own outcome, and `ToastInput.correlationId`
 * is required (`FE-D13`), never client-fabricated. Same shape as
 * `SaveRoleResult`.
 */
export interface SaveGroupResult {
  group: UserGroup;
  correlationId: string;
}

/** The group's membership after the write, plus the server's correlation id (`FE-D13`). */
export interface AddMembersResult {
  members: GroupMember[];
  correlationId: string;
}

/**
 * The created user plus the server's correlation id — the Add user dialog
 * reports its own outcome, and `ToastInput.correlationId` is required
 * (`FE-D13`), never client-fabricated. Same shape as `SaveRoleResult`.
 */
export interface CreateUserResult {
  user: LocalUser;
  correlationId: string;
}

export interface AdminRepository {
  permissionCatalog(signal?: AbortSignal): Promise<PermissionDef[]>;
  roles(signal?: AbortSignal): Promise<RbacRole[]>;
  saveRole(role: RoleDraft): Promise<SaveRoleResult>;
  principals(signal?: AbortSignal): Promise<RbacPrincipal[]>;
  scopeTree(signal?: AbortSignal): Promise<ScopeNode>;
  assignments(signal?: AbortSignal): Promise<RbacAssignment[]>;
  createAssignment(assignment: AssignmentDraft): Promise<CreateAssignmentResult>;
  effectivePermissions(
    principalId: string,
    scope: readonly string[],
    signal?: AbortSignal,
  ): Promise<EffectiveGrant[]>;
  /**
   * Revoke, never delete (`RbacAssignment.status`) — the row survives for
   * audit attribution, exactly as a deactivated user does.
   */
  revokeAssignment(assignmentId: string): Promise<void>;
  users(signal?: AbortSignal): Promise<LocalUser[]>;
  createUser(draft: CreateUserDraft): Promise<CreateUserResult>;
  setUserActive(id: string, active: boolean): Promise<void>;
  // bootstrapFirstAdmin(): intentionally ABSENT — see header (§16 Q5).

  // ---- Groups ----
  groups(signal?: AbortSignal): Promise<UserGroup[]>;
  group(groupId: string, signal?: AbortSignal): Promise<UserGroup>;
  createGroup(draft: GroupDraft): Promise<SaveGroupResult>;
  updateGroup(groupId: string, draft: GroupEditDraft): Promise<SaveGroupResult>;
  setGroupActive(groupId: string, active: boolean): Promise<SaveGroupResult>;
  groupMembers(groupId: string, signal?: AbortSignal): Promise<GroupMember[]>;
  addGroupMembers(groupId: string, userIds: readonly string[]): Promise<AddMembersResult>;
  removeGroupMember(groupId: string, userId: string): Promise<void>;
  groupAssignments(groupId: string, signal?: AbortSignal): Promise<RbacAssignment[]>;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

export function createAdminRepository(cpClient: PlaneClient): AdminRepository {
  return {
    permissionCatalog(signal) {
      return cpClient.get<PermissionDef[]>('/admin/permission-catalog', opts(signal));
    },
    roles(signal) {
      return cpClient.get<RbacRole[]>('/admin/roles', opts(signal));
    },
    async saveRole(role) {
      const { data, correlationId } = await cpClient.postWithCorrelation<RbacRole>(
        '/admin/roles',
        role,
      );
      return { role: data, correlationId };
    },
    principals(signal) {
      return cpClient.get<RbacPrincipal[]>('/admin/principals', opts(signal));
    },
    scopeTree(signal) {
      return cpClient.get<ScopeNode>('/admin/scope-tree', opts(signal));
    },
    assignments(signal) {
      return cpClient.get<RbacAssignment[]>('/admin/assignments', opts(signal));
    },
    async createAssignment(assignment) {
      const { data, correlationId } = await cpClient.postWithCorrelation<RbacAssignment>(
        '/admin/assignments',
        assignment,
      );
      return { assignment: data, correlationId };
    },
    effectivePermissions(principalId, scope, signal) {
      const params = new URLSearchParams({ principalId, scope: scope.join('/') });
      return cpClient.get<EffectiveGrant[]>(
        `/admin/effective-permissions?${params.toString()}`,
        opts(signal),
      );
    },
    users(signal) {
      return cpClient.get<LocalUser[]>('/admin/users', opts(signal));
    },
    async createUser(draft) {
      const { data, correlationId } = await cpClient.postWithCorrelation<LocalUser>(
        '/admin/users',
        draft,
      );
      return { user: data, correlationId };
    },
    setUserActive(id, active) {
      return cpClient.patch<void>(`/admin/users/${encodeURIComponent(id)}`, { active });
    },
    revokeAssignment(assignmentId) {
      return cpClient.delete<void>(`/admin/assignments/${encodeURIComponent(assignmentId)}`);
    },

    groups(signal) {
      return cpClient.get<UserGroup[]>('/admin/groups', opts(signal));
    },
    group(groupId, signal) {
      return cpClient.get<UserGroup>(`/admin/groups/${encodeURIComponent(groupId)}`, opts(signal));
    },
    async createGroup(draft) {
      const { data, correlationId } = await cpClient.postWithCorrelation<UserGroup>(
        '/admin/groups',
        draft,
      );
      return { group: data, correlationId };
    },
    async updateGroup(groupId, draft) {
      const { data, correlationId } = await cpClient.patchWithCorrelation<UserGroup>(
        `/admin/groups/${encodeURIComponent(groupId)}`,
        draft,
      );
      return { group: data, correlationId };
    },
    async setGroupActive(groupId, active) {
      const { data, correlationId } = await cpClient.postWithCorrelation<UserGroup>(
        `/admin/groups/${encodeURIComponent(groupId)}/${active ? 'reactivate' : 'deactivate'}`,
        {},
      );
      return { group: data, correlationId };
    },
    groupMembers(groupId, signal) {
      return cpClient.get<GroupMember[]>(
        `/admin/groups/${encodeURIComponent(groupId)}/members`,
        opts(signal),
      );
    },
    async addGroupMembers(groupId, userIds) {
      const { data, correlationId } = await cpClient.postWithCorrelation<GroupMember[]>(
        `/admin/groups/${encodeURIComponent(groupId)}/members`,
        { userIds: [...userIds] },
      );
      return { members: data, correlationId };
    },
    removeGroupMember(groupId, userId) {
      return cpClient.delete<void>(
        `/admin/groups/${encodeURIComponent(groupId)}/members/${encodeURIComponent(userId)}`,
      );
    },
    groupAssignments(groupId, signal) {
      return cpClient.get<RbacAssignment[]>(
        `/admin/groups/${encodeURIComponent(groupId)}/assignments`,
        opts(signal),
      );
    },
  };
}
