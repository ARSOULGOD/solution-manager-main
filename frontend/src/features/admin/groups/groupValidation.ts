/**
 * Group form validation (Groups management §3/§4). Pure functions, no
 * React and no repository — so the rules are testable on their own and are
 * stated once for both the create and the edit form.
 *
 * ⚠️ The server is the authority. Every rule here is ALSO enforced by the
 * write path (`rbacStore` refuses a duplicate name and a cross-organization
 * membership, and the handler answers `409`) — a client-only rule is a
 * race, not a rule. What this module buys is a field-level message before
 * the round trip, not the guarantee.
 */
import { t } from '@shared/i18n';
import type { UserGroup } from '@shared/testdoubles/data/groups';
import type { LocalUser } from '@shared/testdoubles/data/rbac';

/**
 * The ratified name character set (`D18` 🔒 / `FE-D48`): Unicode letters
 * and numbers, spaces, and `(` `)` `.` `_` `-` `&` `'`. Deliberately
 * narrow — a name carrying `/` would be ambiguous against the scope-path
 * separator, `\\` and `:` are ambiguous in a log line, and a control
 * character is unreadable in one. Unicode letters are allowed (`\p{L}`),
 * so this is not an English-only rule; `&` and `'` were added because real
 * team names ("Payments & Ledger", "O'Brien's Team") carry them.
 */
const NAME_PATTERN = /^[\p{L}\p{N} ()._\-&']+$/u;

/** `FE-D48`. Was 64; the ratified rule is 1–128. */
const MAX_NAME_LENGTH = 128;
const MAX_DESCRIPTION_LENGTH = 256;

export interface GroupFormValues {
  name: string;
  description: string;
  organization: string;
  memberIds: readonly string[];
}

/** One message per field; an absent key means that field is valid. */
export type GroupFormErrors = Partial<Record<'name' | 'description' | 'organization', string>>;

export interface ValidateGroupOptions {
  /** Every existing group, for the case-insensitive per-organization uniqueness rule. */
  existingGroups: readonly UserGroup[];
  /** The group being edited, excluded from the uniqueness check. */
  editingGroupId?: string;
}

export function validateGroupForm(
  values: GroupFormValues,
  { existingGroups, editingGroupId }: ValidateGroupOptions,
): GroupFormErrors {
  const errors: GroupFormErrors = {};
  // ⚠️ Validated RAW, not trimmed. The two name rules look contradictory
  // and are not: uniqueness compares case-insensitively AFTER trim (so
  // `"  Ledger "` cannot slip past `"Ledger"`), while VALIDITY refuses
  // surrounding whitespace outright — so the operator is told the rule
  // rather than silently getting a name in the audit line that is not the
  // one they typed (`FE-D48`).
  const raw = values.name;
  const name = raw.trim();
  const description = values.description.trim();

  if (name === '') {
    errors.name = t('adminGroupNameRequired');
  } else if (raw !== name) {
    errors.name = t('adminGroupNameWhitespace');
  } else if (name.length > MAX_NAME_LENGTH) {
    errors.name = t('adminGroupNameTooLong', { max: MAX_NAME_LENGTH });
  } else if (!NAME_PATTERN.test(name)) {
    errors.name = t('adminGroupNameInvalidChars');
  } else if (
    values.organization !== '' &&
    isNameTaken(name, values, existingGroups, editingGroupId)
  ) {
    errors.name = t('adminGroupNameDuplicate', { organization: values.organization });
  }

  if (description.length > MAX_DESCRIPTION_LENGTH) {
    errors.description = t('adminGroupDescriptionTooLong', { max: MAX_DESCRIPTION_LENGTH });
  }

  if (values.organization === '') {
    errors.organization = t('adminGroupOrganizationRequired');
  }

  return errors;
}

function isNameTaken(
  name: string,
  values: GroupFormValues,
  existingGroups: readonly UserGroup[],
  editingGroupId: string | undefined,
): boolean {
  const wanted = name.toLocaleLowerCase();
  return existingGroups.some(
    (group) =>
      group.id !== editingGroupId &&
      group.organization === values.organization &&
      group.name.trim().toLocaleLowerCase() === wanted,
  );
}

export function hasErrors(errors: GroupFormErrors): boolean {
  return Object.keys(errors).length > 0;
}

/**
 * Users a group of `organization` may contain: same organization, and —
 * for a NEW membership — active. A member who was already in the group and
 * has since been deactivated is a separate case the members list surfaces
 * explicitly; it is never silently dropped.
 */
export function selectableMembers(
  users: readonly LocalUser[],
  organization: string,
  alreadyMemberIds: readonly string[] = [],
): LocalUser[] {
  const already = new Set(alreadyMemberIds);
  return users.filter(
    (user) =>
      user.organization === organization && user.status === 'active' && !already.has(user.id),
  );
}

/**
 * The member ids that survive a change of organization, and the ones
 * dropped by it (§3: "changing the organization must clear incompatible
 * member selections and show an explanatory message").
 */
export function partitionMembersByOrganization(
  memberIds: readonly string[],
  users: readonly LocalUser[],
  organization: string,
): { kept: string[]; dropped: string[] } {
  const byId = new Map(users.map((user) => [user.id, user]));
  const kept: string[] = [];
  const dropped: string[] = [];
  for (const id of memberIds) {
    if (byId.get(id)?.organization === organization) kept.push(id);
    else dropped.push(id);
  }
  return { kept, dropped };
}
