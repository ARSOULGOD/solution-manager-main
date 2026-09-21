/**
 * The persistent chrome (task 036, task 106, `D20` organization
 * display-only, `D9` 🔒, `FE-D8`, `FE-D13`, `FE-D29`, design §9/§14/§18,
 * UI §A.2): navy drawer, white app bar, skip link, and the routed
 * `<Outlet>` wrapped in `RouteErrorBoundary` so a failing route leaves the
 * shell mounted (design §16).
 *
 * Mounted into `router.tsx` as a pathless layout route wrapped in
 * `RequireAuth` (`AuthenticatedLayout.tsx`) — every screen except `/login`
 * and the DEV-only gallery renders inside this shell. This component is
 * also independently tested against its own small route tree in
 * `AppShell.test.tsx`.
 *
 * **Responsive drawer (task 106, `FE-D29`; collapse task 111).** `permanent`
 * at `lg` and up — a plain docked `<div>` (verified against
 * `@mui/material/Drawer` source: `variant="permanent"` never mounts a
 * `Modal`, so it never traps focus). Below `lg` it is `temporary`, which
 * always mounts a `Modal` regardless of variant — the same mechanism
 * `Dialog.tsx` (task 016) already relies on for focus trap, restore-to-
 * trigger, Escape-to-close and backdrop-click-to-close, so none of that is
 * reimplemented here. Closing on navigation is handled by the
 * route-change effect below, since that already fires on every route
 * change regardless of which link or crumb triggered it.
 *
 * **Desktop collapse (task 111).** `variant="permanent"` ignores its
 * `open` prop and always renders docked (verified against the MUI source
 * above) — task 106 shipped it with no way to hide it and no toggle to
 * reach one, the reported "fixed, cannot be collapsed" defect.
 *
 * The collapsed shape is an **icon rail**, the other of the two options
 * task 111 allowed. Its first implementation was "full close" — the
 * whole `Drawer` unmounted at `lg` and up — which cost the operator all
 * navigation, not just the labels, and left the app bar's hamburger as
 * the only way back. A rail keeps every destination one click away at
 * `--drawer-w-rail` instead of `--drawer-w`, and `main` reflows into the
 * width the rail gives back because the docked wrapper is a flex sibling.
 *
 * The rail therefore carries its **own** collapse/expand control
 * (`SidebarNav`'s `onToggleCollapsed`), and `AppBar`'s hamburger goes back
 * to being the overlay drawer's opener below `lg` only. Task 111's
 * invariant — the collapsed state never hides its own toggle — holds more
 * directly than before: the toggle is *in* the thing it collapses, and
 * that thing is always on screen. Both write the same persisted
 * `useUiStore.navCollapsed`, so the choice survives navigation and reload.
 *
 * **Sidebar solution list.** Task 036 shipped this hardcoded empty — the
 * client-distribution mechanism it needed did not exist yet — so the
 * drawer read "Solutions (0)" however many packages were loaded. It now
 * reads the same `queryKeys.solutions.list()` entry the catalog screen
 * (039) populates, so the two share one cache entry and one in-flight
 * request, and `SolutionsPage`'s post-load `invalidateQueries` refreshes
 * the drawer for free. The repository is injected (`AuthenticatedLayout`
 * builds it from `RuntimeConfig`) because a shell in `app/shell` may not
 * read app-layer boot config the way a route can.
 *
 * While the catalog is loading or failed, the count is passed as
 * `undefined` and rendered as absent — never as `(0)`, the fabricated
 * empty `UI-D8` forbids. The catalog screen owns the error surface; the
 * drawer must not become an error page.
 */
import { Box, Drawer, useMediaQuery, useTheme } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Outlet, useLocation } from 'react-router';

import {
  countDistinctSolutions,
  toSidebarSolutions,
  type SolutionsRepository,
} from '@features/inventory/solutions';
import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import { useUiStore } from '@shared/state/uiStore';
import { SidebarNav, Toaster, type SidebarNavSolution } from '@shared/ui';

import { RouteErrorBoundary } from '../errors/RouteErrorBoundary';

import { AppBar } from './AppBar';
import { Brand } from './Brand';

const NO_SOLUTIONS: readonly SidebarNavSolution[] = [];

export interface AppShellProps {
  /**
   * Optional so the shell can be mounted on its own in a test or a
   * chromeless context. Without it the drawer lists no solutions and
   * shows no count — the honest rendering of "not asked", identical to
   * how a still-loading catalog renders.
   */
  solutionsRepository?: SolutionsRepository;
}

export function AppShell({ solutionsRepository }: AppShellProps = {}) {
  const location = useLocation();
  const mainRef = useRef<HTMLElement | null>(null);
  const theme = useTheme();
  const permanentDrawer = useMediaQuery(theme.breakpoints.up('lg'));
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const navCollapsed = useUiStore((state) => state.navCollapsed);
  const solutionsQuery = useQuery({
    queryKey: queryKeys.solutions.list(),
    queryFn: ({ signal }) => {
      if (solutionsRepository === undefined) {
        // `enabled` below makes this unreachable; TanStack still demands a
        // function. Throwing beats resolving `[]`, which would seed the
        // shared cache entry with a fabricated empty catalog.
        throw new Error('AppShell: solutions query ran without a repository.');
      }
      return solutionsRepository.list(signal);
    },
    enabled: solutionsRepository !== undefined,
  });
  const packages = solutionsQuery.data;
  const navSolutions = useMemo(
    () => (packages === undefined ? NO_SOLUTIONS : toSidebarSolutions(packages)),
    [packages],
  );
  const navSolutionCount = packages === undefined ? undefined : countDistinctSolutions(packages);
  const toggleNavCollapsed = useUiStore((state) => state.toggleNavCollapsed);
  // `railed` is the *rendered* collapse, not the stored preference: below
  // `lg` the drawer is an overlay that is either open or closed, and a
  // 76px rail inside a modal would be nonsense. The preference is kept
  // untouched across that boundary so widening the window restores it.
  const railed = permanentDrawer && navCollapsed;
  const drawerWidth = railed ? 'var(--drawer-w-rail)' : 'var(--drawer-w)';
  const handleToggleNav = () => {
    setMobileNavOpen((open) => !open);
  };

  // Task 106: the overlay drawer closes on navigation — stranding the
  // operator behind it after a route change would hide the very screen
  // the navigation was for. Adjusted during render (React's own pattern
  // for "reset state when a prop/derived value changes"), not inside the
  // effect below — an unconditional setState in an effect body is a
  // cascading-render lint error (`react-hooks/set-state-in-effect`).
  const [lastPathname, setLastPathname] = useState(location.pathname);
  if (location.pathname !== lastPathname) {
    setLastPathname(location.pathname);
    setMobileNavOpen(false);
  }

  useEffect(() => {
    const main = mainRef.current;
    main?.focus({ preventScroll: true });
    // jsdom (Vitest) does not implement scrollTo — real browsers always do.
    if (typeof main?.scrollTo === 'function') {
      main.scrollTo(0, 0);
    }
  }, [location.pathname]);

  return (
    <Box data-app-shell sx={{ minHeight: '100vh' }}>
      <Box
        component="a"
        // Task 110 found this pointing at `#main` while every non-`AppShell`
        // screen (`NotFound`, the `RuntimeConfig` boot error,
        // dev `Gallery`) — and `index.html`'s own static skip link, which
        // wins the "first Tab stop" race since it sits before `#root` in
        // the DOM — use `main-content`. A real browser's axe pass (never
        // reachable from jsdom, which never renders `index.html`) caught
        // it as a `skip-link` violation: the target didn't exist on any
        // authenticated screen, so the very first Tab stop on every real
        // page went nowhere.
        href="#main-content"
        sx={{
          position: 'absolute',
          left: 8,
          top: -56,
          zIndex: (t2) => t2.zIndex.tooltip + 1,
          px: 2,
          py: 1,
          backgroundColor: 'background.paper',
          color: 'text.primary',
          borderRadius: 1,
          boxShadow: 3,
          transition: 'top 0.15s ease-in',
          '&:focus': { top: 8 },
        }}
      >
        {t('skipToMain')}
      </Box>

      <Box sx={{ display: 'flex', minHeight: '100vh' }}>
        <Drawer
          variant={permanentDrawer ? 'permanent' : 'temporary'}
          open={permanentDrawer || mobileNavOpen}
          onClose={() => setMobileNavOpen(false)}
          // MUI's Drawer Paper is `position: fixed` for every variant,
          // including `permanent` — only `.MuiDrawer-docked`, the plain
          // wrapper div around it, participates in this flex layout. Width
          // set only on the `paper` slot (below) doesn't reach that
          // wrapper, so it collapses to 0, `main` slides left to fill the
          // gap, and the fixed-position Paper then floats over it. Both
          // therefore animate to the same width, and `main` reflows into
          // whatever the docked wrapper gives back.
          sx={
            permanentDrawer
              ? (t2) => ({
                  width: drawerWidth,
                  flexShrink: 0,
                  transition: t2.transitions.create('width', {
                    duration: t2.transitions.duration.shorter,
                  }),
                })
              : undefined
          }
          slotProps={{
            paper: {
              sx: (t2) => ({
                width: permanentDrawer ? drawerWidth : 'var(--drawer-w)',
                maxWidth: '85vw',
                boxSizing: 'border-box',
                border: 'none',
                overflowX: 'hidden',
                backgroundColor: t2.palette.nav.bg,
                transition: t2.transitions.create('width', {
                  duration: t2.transitions.duration.shorter,
                }),
              }),
            },
          }}
        >
          <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
            <Box sx={{ px: railed ? 1 : 2, pt: 2.5, pb: 1 }}>
              <Brand compact={railed} />
            </Box>
            <Box sx={{ flex: '1 1 auto', minHeight: 0, overflowY: 'auto', overflowX: 'hidden' }}>
              <SidebarNav
                solutions={navSolutions}
                solutionCount={navSolutionCount}
                collapsed={railed}
                onToggleCollapsed={permanentDrawer ? toggleNavCollapsed : undefined}
              />
            </Box>
          </Box>
        </Drawer>

        <Box
          sx={{
            display: 'flex',
            flexDirection: 'column',
            flex: '1 1 auto',
            minWidth: 0,
            minHeight: '100vh',
          }}
        >
          <AppBar
            navOpen={mobileNavOpen}
            onToggleNav={handleToggleNav}
            showNavToggle={!permanentDrawer}
          />
          <Box
            component="main"
            id="main-content"
            tabIndex={-1}
            ref={mainRef}
            sx={{ flex: '1 1 auto', overflow: 'auto', p: 3 }}
          >
            <RouteErrorBoundary>
              <Outlet />
            </RouteErrorBoundary>
          </Box>
        </Box>
      </Box>

      <Toaster />
    </Box>
  );
}
