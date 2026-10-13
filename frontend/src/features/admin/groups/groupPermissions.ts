/**
 * Which ratified permission each Groups action is gated on — `D18` 🔒,
 * `FE-D47`, Groups management §11.
 *
 * This module used to map every Groups action onto the closest approved
 * `user.*` verb, because `D10`'s catalog had no `group.*` ids and
 * `GrantableVerb` made inventing one a compile error. **`D18` ratified
 * them on 2026-09-04**, so the borrowing is over and each action gates on
 * its own verb:
 *
 * | Groups action                | Verb                  |
 * |------------------------------|-----------------------|
 * | See the Groups tab, list, a group's detail, and its members | `group.view` |
 * | Create a group               | `group.create`        |
 * | Edit name / description      | `group.edit`          |
 * | Deactivate / reactivate      | `group.deactivate`    |
 * | Add / remove members         | `group.member.manage` |
 * | Assign / revoke a role       | `role.assign`         |
 *
 * **Two verbs `D18` declined, and why this file has no key for them.**
 * `group.member.view` — `group.view` covers group *and* member visibility;
 * a separate verb would allow seeing a group whose membership is hidden,
 * a state no screen and no audit question needs. `group.role.assign` —
 * `role.assign` already means "assign or revoke a role to or from a
 * principal", and a group **is** a principal in `D10`'s model, so a
 * group-specific spelling would be a second name for one authority and the
 * two would drift.
 *
 * ⚠️ **Membership management is security-sensitive, and its gate must
 * never be weakened to `user.create` or `user.deactivate`.** Adding a
 * member GRANTS that user every role the group holds, so it is an
 * authorization act, not an identity act — a user-administrator who could
 * add members could escalate privilege through the back door of a
 * membership row.
 *
 * ⚠️ **BACKEND OBLIGATION, stated by `D18` and not dischargeable here.**
 * Holding `group.member.manage` is *necessary, not sufficient*: the server
 * must additionally refuse a membership change through which the actor
 * would confer a role or a scope the actor is not itself permitted to
 * delegate. A client-side check of that is a race, not a rule.
 *
 * ⚠️ Gating here is COSMETIC (`permissions.ts`'s own header, `FE-D9`). The
 * server denies by default regardless; hiding a control changes what an
 * operator sees, not what the server allows.
 */
import { requireGrantable } from '@shared/authz/permissions';
import type { GrantableVerb } from '@shared/authz/permissions';

export interface GroupPermissionMap {
  view: GrantableVerb;
  create: GrantableVerb;
  edit: GrantableVerb;
  deactivate: GrantableVerb;
  /** No `memberView` — `view` covers it (`D18`). Do not add one back. */
  memberManage: GrantableVerb;
  roleAssign: GrantableVerb;
}

/**
 * The typed mapping. `GrantableVerb` is the enforcement: a verb that is
 * not in the ratified catalog will not compile here, which is what stops
 * an id being introduced as a string literal at a call site.
 */
export const GROUP_PERMISSIONS: GroupPermissionMap = {
  view: 'group.view',
  create: 'group.create',
  edit: 'group.edit',
  deactivate: 'group.deactivate',
  memberManage: 'group.member.manage',
  roleAssign: 'role.assign',
};

// Compile-time proof that every mapped verb is in the ratified catalog
// (§6.0.6's mechanism — the parameter's TYPE is the check, the value is
// never read). Listed rather than iterated with `Object.values`, whose
// element type erases to `any` and would silence exactly the check this
// exists to make.
requireGrantable(GROUP_PERMISSIONS.view);
requireGrantable(GROUP_PERMISSIONS.create);
requireGrantable(GROUP_PERMISSIONS.edit);
requireGrantable(GROUP_PERMISSIONS.deactivate);
requireGrantable(GROUP_PERMISSIONS.memberManage);
requireGrantable(GROUP_PERMISSIONS.roleAssign);
