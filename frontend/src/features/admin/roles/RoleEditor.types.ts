import type { RbacRole } from '@shared/testdoubles/data/rbac';

import type { AdminRepository } from '../adminRepository';

/**
 * `inspect` — a built-in role, read-only (clone to change it). `edit` — a
 * custom role. `clone` — a new custom role seeded from any role. `create`
 * — a blank custom role.
 */
export type RoleEditorMode = 'inspect' | 'edit' | 'clone' | 'create';

export interface RoleEditorProps {
  mode: RoleEditorMode;
  /** The source role for `inspect` / `edit` / `clone`; `null` for `create`. */
  role: RbacRole | null;
  repository: AdminRepository;
  onClose: () => void;
  /** Re-open as a clone of the role currently being inspected. */
  onClone: () => void;
}
