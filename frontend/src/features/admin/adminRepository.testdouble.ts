/**
 * A complete `AdminRepository` whose every method rejects, for tests to
 * override the two or three they actually exercise.
 *
 * Before this, each admin test file carried its own hand-written stub, so
 * adding one method to the interface meant editing five identical
 * twenty-line literals — and a test asserting "this screen never calls
 * `saveRole`" only held by accident, because nothing forced the unused
 * methods to be present at all. Every method here rejects loudly, so a
 * screen reaching for a seam its test did not stub fails with a named
 * error rather than an `undefined is not a function`.
 *
 * Not a `*.test.ts` file: Vitest's `include` matches `*.test.{ts,tsx}`
 * exactly, so this is a helper the suites import, never a suite itself.
 * It is feature-internal — `shared/testdoubles` may not import a feature
 * (boundaries policy), which is why it lives here rather than there.
 */
import type { AdminRepository } from './adminRepository';

function unused(method: string): () => Promise<never> {
  return () =>
    Promise.reject(
      new Error(`AdminRepository.${method}() was called but this test did not stub it.`),
    );
}

export function stubAdminRepository(overrides: Partial<AdminRepository> = {}): AdminRepository {
  return {
    permissionCatalog: unused('permissionCatalog'),
    roles: unused('roles'),
    saveRole: unused('saveRole'),
    principals: unused('principals'),
    scopeTree: unused('scopeTree'),
    assignments: unused('assignments'),
    createAssignment: unused('createAssignment'),
    effectivePermissions: unused('effectivePermissions'),
    revokeAssignment: unused('revokeAssignment'),
    users: unused('users'),
    createUser: unused('createUser'),
    setUserActive: unused('setUserActive'),
    groups: unused('groups'),
    group: unused('group'),
    createGroup: unused('createGroup'),
    updateGroup: unused('updateGroup'),
    setGroupActive: unused('setGroupActive'),
    groupMembers: unused('groupMembers'),
    addGroupMembers: unused('addGroupMembers'),
    removeGroupMember: unused('removeGroupMember'),
    groupAssignments: unused('groupAssignments'),
    ...overrides,
  };
}
