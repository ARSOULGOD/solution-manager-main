/**
 * The Admin feature's public surface (task 090/091) — what an app-layer
 * route may import. Everything else under `src/features/admin/**` is
 * internal.
 */
export { AdminShell, ADMIN_TABS, type AdminShellProps, type AdminTab } from './AdminShell';
export {
  createAdminRepository,
  type AdminRepository,
  type GroupDraft,
  type GroupEditDraft,
  type RoleDraft,
  type SaveGroupResult,
  type SaveRoleResult,
} from './adminRepository';
