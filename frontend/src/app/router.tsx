import { lazy, Suspense, type ReactElement } from 'react';
import { createBrowserRouter, Navigate, type RouteObject } from 'react-router';

import { buildGalleryRoutes } from './dev/gallery.routes';
import { NotFound } from './NotFound';
import { ROUTE_PATHS, routes } from './routes';

const LoginRoute = lazy(() => import('./routes/LoginRoute'));
const AuthenticatedLayout = lazy(() => import('./routes/AuthenticatedLayout'));
const HomeRoute = lazy(() => import('./routes/HomeRoute'));
const SolutionsRoute = lazy(() => import('./routes/SolutionsRoute'));
const InstancesRoute = lazy(() => import('./routes/InstancesRoute'));
const InstanceCategoryRoute = lazy(() => import('./routes/InstanceCategoryRoute'));
const ExploreRoute = lazy(() => import('./routes/ExploreRoute'));
const DashboardRoute = lazy(() => import('./routes/DashboardRoute'));
const ConnectionsRoute = lazy(() => import('./routes/ConnectionsRoute'));
const AdminRoute = lazy(() => import('./routes/AdminRoute'));

// Reserves the viewport so swapping in a lazy chunk causes no layout shift.
// AppShell's own skeleton (task 036) supersedes this once it exists.
function RouteFallback() {
  return <div style={{ minHeight: '100vh' }} aria-hidden="true" />;
}

function withSuspense(element: ReactElement): ReactElement {
  return <Suspense fallback={<RouteFallback />}>{element}</Suspense>;
}

// Exported separately from the browser router so tests can build a
// createMemoryRouter from the same definitions instead of duplicating them.
export const appRoutes: RouteObject[] = [
  // Chromeless — AppShell is for the authenticated app only.
  { path: ROUTE_PATHS.login, element: withSuspense(<LoginRoute />) },
  // DEV only — must stay before the wildcard below, and never reach a
  // production bundle (FE-D24). A standalone dev tool, not part of the
  // authenticated app shell.
  //
  // The literal `import.meta.env.DEV` belongs HERE as well as inside
  // `buildGalleryRoutes`, and the two guards do different jobs. Its own
  // guard eliminates the `import('./Gallery')` chunk. This one eliminates
  // the MODULE: without it the bundler cannot prove the call returns `[]`,
  // so it keeps a folded husk of `dev/gallery.routes.ts` — 99 bytes of
  // `function buildGalleryRoutes() { return []; }` — in the entry chunk.
  // With it, the branch is dead, the call goes with it, the import binding
  // is unused and the module leaves the graph. FE-101's `no-dev-gallery`
  // assertion measures exactly that.
  ...(import.meta.env.DEV ? buildGalleryRoutes() : []),
  {
    // Pathless layout route: gates every screen below behind RequireAuth
    // and mounts AppShell (drawer, app bar, breadcrumbs, organization display —
    // the server-derived isolation chip of `I5`/`D20`)
    // around their <Outlet> (task 036 wiring).
    element: withSuspense(<AuthenticatedLayout />),
    children: [
      { path: ROUTE_PATHS.home, element: withSuspense(<HomeRoute />) },
      { path: ROUTE_PATHS.solutions, element: withSuspense(<SolutionsRoute />) },
      { path: ROUTE_PATHS.instances, element: withSuspense(<InstancesRoute />) },
      // Static siblings of instanceCategory below — react-router ranks
      // static segments above dynamic ones, so these win over `:category`
      // on an exact match rather than needing to be declared first.
      { path: ROUTE_PATHS.explore, element: withSuspense(<ExploreRoute />) },
      { path: ROUTE_PATHS.dashboard, element: withSuspense(<DashboardRoute />) },
      { path: ROUTE_PATHS.instanceCategory, element: withSuspense(<InstanceCategoryRoute />) },
      { path: ROUTE_PATHS.connections, element: withSuspense(<ConnectionsRoute />) },
      // Bare `/admin` redirects to its default tab — `AdminRoute` itself
      // only ever sees a resolved `:tab` (routes.ts's own default lives
      // here so there is exactly one place that picks `roles`).
      { path: ROUTE_PATHS.adminIndex, element: <Navigate to={routes.admin()} replace /> },
      { path: ROUTE_PATHS.admin, element: withSuspense(<AdminRoute />) },
      // Unknown route: render NotFound in place, never redirect silently.
      { path: '*', element: <NotFound /> },
    ],
  },
];

export const router = createBrowserRouter(appRoutes);
