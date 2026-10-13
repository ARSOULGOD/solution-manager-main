/**
 * The route table, exactly as design §14. Routes carry resource ids only;
 * organization authority comes from the authenticated session (I5, D20).
 * Feature code builds links through `routes.*`, never a hand-written
 * template string, so this file is the one place a path can change.
 */

export const INSTANCE_CATEGORIES = ['designer', 'governance', 'monitoring'] as const;
export type InstanceCategory = (typeof INSTANCE_CATEGORIES)[number];

export function isInstanceCategory(value: string): value is InstanceCategory {
  return (INSTANCE_CATEGORIES as readonly string[]).includes(value);
}

// FE-090: Admin's own tab order. `:tab` defaults to `roles` (bare `/admin`
// redirects there in router.tsx) — an unknown value 404s rather than
// falling through, same rule `:category` follows above.
export const ADMIN_TABS = ['roles', 'assignments', 'access', 'groups', 'users'] as const;
export type AdminTab = (typeof ADMIN_TABS)[number];

export function isAdminTab(value: string): value is AdminTab {
  return (ADMIN_TABS as readonly string[]).includes(value);
}

// react-router path patterns. `explore` and `dashboard` are static-segment
// siblings of the dynamic `instanceCategory` pattern — react-router ranks
// static segments above dynamic ones, so `/monitoring/explore` matches its
// own route rather than falling into `:category`.
export const ROUTE_PATHS = {
  login: '/login',
  home: '/',
  solutions: '/solutions',
  instances: '/solutions/:solutionId/instances',
  instanceCategory: '/solutions/:solutionId/instances/:instanceId/:category',
  explore: '/solutions/:solutionId/instances/:instanceId/monitoring/explore',
  dashboard: '/solutions/:solutionId/instances/:instanceId/monitoring/dashboards/:dashboardId',
  connections: '/connections',
  adminIndex: '/admin',
  admin: '/admin/:tab',
} as const;

export const routes = {
  login: () => ROUTE_PATHS.login,
  home: () => ROUTE_PATHS.home,
  solutions: () => ROUTE_PATHS.solutions,
  instances: (solutionId: string) => `/solutions/${solutionId}/instances`,
  instanceCategory: (solutionId: string, instanceId: string, category: InstanceCategory) =>
    `/solutions/${solutionId}/instances/${instanceId}/${category}`,
  explore: (solutionId: string, instanceId: string) =>
    `/solutions/${solutionId}/instances/${instanceId}/monitoring/explore`,
  dashboard: (solutionId: string, instanceId: string, dashboardId: string) =>
    `/solutions/${solutionId}/instances/${instanceId}/monitoring/dashboards/${dashboardId}`,
  connections: () => ROUTE_PATHS.connections,
  admin: (tab: AdminTab = 'roles') => `/admin/${tab}`,
} as const;
