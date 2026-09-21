/**
 * The one hook the Groups screens gate on (Groups management §11,
 * `FE-D9`). Wraps `usePermissions().gate()` so every call site reads the
 * same way — `can(GROUP_PERMISSIONS.create)` — and so the omit-vs-render
 * decision is made in exactly one place.
 *
 * ⚠️ Cosmetic, never a security boundary: the server denies by default on
 * every request regardless (`permissions.ts`'s header, design §15). What
 * this changes is whether an operator sees a control they could not use.
 */
import { useCallback } from 'react';

import type { GrantableVerb } from '@shared/authz/permissions';
import { usePermissions } from '@shared/authz/usePermissions';

export type GroupPermissionCheck = (verb: GrantableVerb) => boolean;

export function useGroupPermissions(): GroupPermissionCheck {
  const permissions = usePermissions();
  return useCallback((verb: GrantableVerb) => permissions.gate(verb) === 'render', [permissions]);
}
