import type { AdminRepository } from '../adminRepository';

// Admin's own copy of the tab order (task 090) — kept separate from
// `app/routes.ts`'s `AdminTab`/`ADMIN_TABS` because a feature may not
// import `@app/routes` (module boundaries); `InstanceHeader`'s own
// `InstanceCategory` duplicates `app/routes.ts` the same way.
export const ADMIN_TABS = ['roles', 'assignments', 'access', 'groups', 'users'] as const;
export type AdminTab = (typeof ADMIN_TABS)[number];

export interface AdminShellProps {
  tab: AdminTab;
  /** Built by the route (app layer owns `RuntimeConfig`), consumed by the tab views. */
  repository: AdminRepository;
}
