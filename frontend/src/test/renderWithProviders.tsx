/**
 * Mounts the same provider stack every real screen sees — theme, query,
 * auth, router — in the order design §8.1 fixes (`app/providers.tsx`):
 * ThemeProvider → CssBaseline → QueryClientProvider → AuthProvider →
 * Router. `MemoryRouter` stands in for the real app's `RouterProvider`
 * so a component test isn't coupled to the full route table (route-table
 * behaviour — guards, deep links — is `router.test.tsx`'s job, against
 * the real routes, per design §19's "Routes" layer).
 *
 * Auth (task 030) is the real `AuthProvider`, given a `cpClient` that
 * points nowhere meaningful — in a Vitest run `import.meta.env.DEV` is
 * true, so `AuthProvider` selects `devMockAdapter`, which never touches
 * the network, so the unresolvable client is never actually called.
 * Permission overrides (task 032) land the same way once that model
 * exists — nothing to wire yet, so nothing is invented here.
 */
import { CssBaseline, ThemeProvider } from '@mui/material';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderOptions } from '@testing-library/react';
import type { ReactElement, ReactNode } from 'react';
import { MemoryRouter } from 'react-router';

import { createCpClient } from '@shared/api/cpClient';
import type { AuthAdapter, Identity } from '@shared/auth/AuthAdapter';
import { AuthProvider } from '@shared/auth/AuthProvider';
import { createSmTheme, type SmThemeMode } from '@shared/theme';

export interface RenderWithProvidersOptions extends Omit<RenderOptions, 'wrapper'> {
  /** Colour mode for this render — design §8.1's toggle, testable per-test. */
  mode?: SmThemeMode;
  /** Initial MemoryRouter location. Defaults to "/". */
  route?: string;
  /** Supply a client to assert on its cache; otherwise a fresh no-retry one is created. */
  queryClient?: QueryClient;
  /** Override the resolved identity's permissions for an authorization test. */
  permissions?: readonly string[];
}

function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
}

const testCpClient = createCpClient('/api');

export function renderWithProviders(
  ui: ReactElement,
  {
    mode = 'light',
    route = '/',
    queryClient,
    permissions,
    ...renderOptions
  }: RenderWithProvidersOptions = {},
) {
  const theme = createSmTheme(mode);
  const client = queryClient ?? createTestQueryClient();
  const adapterOverride: AuthAdapter | undefined =
    permissions === undefined
      ? undefined
      : {
          getIdentity: () =>
            Promise.resolve<Identity>({
              username: 'authz-test-user',
              source: 'local',
              mustRotate: false,
              superAdmin: false,
              organizationNames: ['Test Org'],
              permissions,
            }),
          login: () => Promise.reject(new Error('Not used by renderWithProviders.')),
          logout: () => Promise.resolve(),
          rotateCredential: () => Promise.reject(new Error('Not used by renderWithProviders.')),
          onSessionExpired: () => () => {},
        };

  function Providers({ children }: { children: ReactNode }) {
    return (
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <QueryClientProvider client={client}>
          <AuthProvider
            cpClient={testCpClient}
            {...(adapterOverride === undefined ? {} : { adapterOverride })}
          >
            <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>
          </AuthProvider>
        </QueryClientProvider>
      </ThemeProvider>
    );
  }

  return { ...render(ui, { wrapper: Providers, ...renderOptions }), queryClient: client };
}

export * from '@testing-library/react';
