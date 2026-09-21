/**
 * The permission source every feature reads (task 032). Wraps the current
 * identity's granted ids (from `AuthAdapter.getIdentity()`, task 030)
 * against `PERMISSION_CATALOG` — still cosmetic (see `permissions.ts`'s
 * header), never a substitute for the server's own check.
 */
import { useMemo } from 'react';

import { useAuth } from '@shared/auth/AuthProvider';

import { PERMISSION_CATALOG } from './catalog';
import { createPermissionApi, type PermissionApi } from './permissions';

export function usePermissions(): PermissionApi {
  const { identity } = useAuth();

  return useMemo(
    () => createPermissionApi(identity?.permissions ?? [], PERMISSION_CATALOG),
    [identity],
  );
}
