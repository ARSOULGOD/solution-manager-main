import { ThemeProvider } from '@mui/material';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { createMemoryRouter, matchRoutes, RouterProvider } from 'react-router';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

import { createCpClient } from '@shared/api/cpClient';
import { AuthProvider } from '@shared/auth/AuthProvider';
import { createSmTheme } from '@shared/theme';

import { RuntimeConfigBoot } from './boot/RuntimeConfig';
import { appRoutes } from './router';
import { ROUTE_PATHS } from './routes';

// React 18+ requires this flag for `act` to work outside a test-library
// wrapper (this file predates `renderWithProviders`, task 008, and stays
// on plain react-dom to keep testing the real route table/lazy-loading
// rather than a wrapped one).
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

// LoginRoute (task 037) reads `useAuth()`. Vitest always runs with
// `import.meta.env.DEV === true`, so `AuthProvider` selects `devMockAdapter`
// (never touches the network) — this client is never actually called, same
// reasoning `renderWithProviders.tsx` (task 030) documents for itself.
const testCpClient = createCpClient('/api');

describe('appRoutes — pattern resolution', () => {
  it.each([
    '/login',
    '/',
    '/solutions',
    '/solutions/sol-1/instances',
    '/solutions/sol-1/instances/inst-1/designer',
    '/solutions/sol-1/instances/inst-1/governance',
    '/solutions/sol-1/instances/inst-1/monitoring',
    '/solutions/sol-1/instances/inst-1/monitoring/explore',
    '/solutions/sol-1/instances/inst-1/monitoring/dashboards/dash-1',
    '/admin',
    '/admin/roles',
    '/admin/groups',
  ])('resolves %s', (path) => {
    expect(matchRoutes(appRoutes, path)).not.toBeNull();
  });

  it('matches /monitoring/explore to the explore route, not the generic :category route', () => {
    const matches = matchRoutes(appRoutes, '/solutions/sol-1/instances/inst-1/monitoring/explore');
    expect(matches?.at(-1)?.route.path).toBe(ROUTE_PATHS.explore);
  });

  it('matches a dashboard id to the dashboard route, not the generic :category route', () => {
    const matches = matchRoutes(
      appRoutes,
      '/solutions/sol-1/instances/inst-1/monitoring/dashboards/dash-1',
    );
    expect(matches?.at(-1)?.route.path).toBe(ROUTE_PATHS.dashboard);
  });

  it('an unknown top-level path matches the wildcard NotFound route', () => {
    const matches = matchRoutes(appRoutes, '/this/does/not/exist');
    expect(matches?.at(-1)?.route.path).toBe('*');
  });

  it('no route pattern carries a client-supplied organization-authority segment', () => {
    for (const route of appRoutes) {
      expect(route.path?.toLowerCase() ?? '').not.toContain('organization');
    }
  });

  it('resolves the DEV-only gallery route (Vitest runs with import.meta.env.DEV true)', () => {
    expect(matchRoutes(appRoutes, '/dev/gallery')).not.toBeNull();
  });
});

describe('appRoutes — deep-link rendering', () => {
  let container: HTMLDivElement | null = null;
  let root: Root | null = null;

  // LoginRoute (task 037) reads RuntimeConfig for the idle-timeout minutes
  // its footer states — every deep-link render below now needs a resolved
  // config, not just the /login one, since they share one RouterProvider
  // tree wrapped in RuntimeConfigBoot.
  beforeAll(() => {
    // `loadConfig()` (task 005) always calls `fetch(url, ...)` with a plain
    // string path — this mock only needs to handle that real shape.
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.includes('config.json')) {
          return Promise.resolve(
            new Response(
              JSON.stringify({ apiBasePath: '/api', idleTimeoutMinutes: 30, channel: 'test' }),
              { status: 200 },
            ),
          );
        }
        // `InstanceCategoryRoute` (task 044) fetches the instance itself —
        // a minimal fixture row, just enough for `InstanceHeader` to render.
        if (url.includes('/dp/instances/')) {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                id: 'inst-1',
                name: 'inst-1',
                solutionId: 'sol-1',
                env: 'PROD',
                org: 'Org',
                subOrg: 'Sub',
                driver: 'docker',
                locus: 'dp_remote',
                replicas: [{ name: 'inst-1-0', health: 'healthy' }],
                healthRollup: 'healthy',
                version: { state: 'ok', observed: '1.0.0', declared: '1.0.0' },
                configState: 'in_sync',
                lastSeen: '1s ago',
              }),
              { status: 200 },
            ),
          );
        }
        // `AppShell` (task 036/106) queries the solutions list for the
        // sidebar count on every authenticated route, not just `/solutions`
        // — every deep-link render below goes through it now.
        if (url.includes('/cp/solutions')) {
          return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }));
        }
        throw new Error(`router.test.tsx: unexpected fetch to ${url}`);
      }),
    );
  });

  afterAll(() => {
    vi.unstubAllGlobals();
  });

  afterEach(() => {
    if (root) {
      act(() => {
        root?.unmount();
      });
    }
    container?.remove();
    container = null;
    root = null;
  });

  async function renderAt(path: string): Promise<HTMLDivElement> {
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    const memoryRouter = createMemoryRouter(appRoutes, { initialEntries: [path] });
    // `InstanceCategoryRoute` (task 044) is the first route deep-rendered
    // here that calls `useQuery` — every real route needs this provider,
    // but it's added at the point a test first exercises one.
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    act(() => {
      root?.render(
        <ThemeProvider theme={createSmTheme('light')}>
          <QueryClientProvider client={queryClient}>
            <RuntimeConfigBoot>
              <AuthProvider cpClient={testCpClient}>
                <RouterProvider router={memoryRouter} />
              </AuthProvider>
            </RuntimeConfigBoot>
          </QueryClientProvider>
        </ThemeProvider>,
      );
    });
    // The route element itself resolves synchronously, but its lazy chunk
    // is a real dynamic import() — flush until Suspense's fallback yields
    // to the loaded content instead of asserting on a specific tick count.
    // Every authenticated path now nests under AppShell (task 036 wiring),
    // which paints its own chrome (breadcrumbs, organization) via its own lazy
    // chunk — resolving that tells us nothing about whether the routed
    // page underneath has settled. Watch AppShell's own `#main-content`
    // (the Outlet's mount point, `NotFound.tsx`/`Gallery.tsx`
    // all share the id) when it exists; chromeless routes (login, the dev
    // gallery) have no `#main-content` at all, so fall back to the whole
    // container for those — otherwise the chrome alone (breadcrumbs render
    // before the routed Outlet content does) reads as "settled" while the
    // route's own lazy chunk is still on its fallback. 400 attempts (2s
    // budget): DataGrid + the rest of the Phase 1 kit make the lazy
    // gallery chunk substantially heavier than Autocomplete alone.
    for (let attempt = 0; attempt < 400; attempt += 1) {
      const target = container.querySelector('#main-content') ?? container;
      if (target.textContent !== '') {
        break;
      }
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 5));
      });
    }
    return container;
  }

  it(
    'a deep link to /login resolves the lazy chunk and renders its content',
    // LoginRoute (task 037) adds RuntimeConfigBoot's fetch and AuthProvider's
    // identity resolution on top of the lazy chunk itself — same reasoning
    // as the gallery test's override below, needed under full-suite load.
    { timeout: 10_000 },
    async () => {
      const el = await renderAt('/login');
      expect(el.textContent).toContain('Sign in');
    },
  );

  it(
    'a deep link to /dev/gallery resolves the lazy chunk and renders the gallery',
    { timeout: 20_000 },
    async () => {
      const el = await renderAt('/dev/gallery');
      expect(el.textContent).toContain('Component gallery');
    },
  );

  it('a deep link to a valid :category renders that category, not NotFound', async () => {
    const el = await renderAt('/solutions/sol-1/instances/inst-1/designer');
    expect(el.textContent).toContain('Designer');
    expect(el.textContent).not.toMatch(/not found/i);
  });

  it('a deep link with an invalid :category renders NotFound rather than falling through', async () => {
    const el = await renderAt('/solutions/sol-1/instances/inst-1/bogus');
    expect(el.textContent).toMatch(/not found/i);
  });

  it('an unmatched path renders NotFound, not a redirect', async () => {
    const el = await renderAt('/nope');
    expect(el.textContent).toMatch(/not found/i);
  });

  it('a deep link to /admin/roles renders the Admin shell', async () => {
    const el = await renderAt('/admin/roles');
    expect(el.textContent).toContain('Admin');
    expect(el.textContent).not.toMatch(/not found/i);
  });

  it('bare /admin redirects to its default tab (routes.ts, FE-090)', async () => {
    const el = await renderAt('/admin');
    expect(el.textContent).toContain('Admin');
    expect(el.textContent).not.toMatch(/not found/i);
  });

  it('a deep link to /admin/groups renders the Groups tab', async () => {
    const el = await renderAt('/admin/groups');
    expect(el.textContent).toContain('Admin');
    expect(el.textContent).not.toMatch(/not found/i);
  });

  it('a deep link with an invalid :tab renders NotFound rather than falling through', async () => {
    const el = await renderAt('/admin/bogus');
    expect(el.textContent).toMatch(/not found/i);
  });
});
