import { CssBaseline, ThemeProvider } from '@mui/material';
import { QueryClientProvider } from '@tanstack/react-query';
import { useEffect, useMemo } from 'react';
import { RouterProvider } from 'react-router';

import { createCpClient } from '@shared/api/cpClient';
import { configureCsrf } from '@shared/api/http';
import { createQueryClient } from '@shared/api/queryClient';
import { AuthProvider } from '@shared/auth/AuthProvider';
import { createLocalSessionAdapter } from '@shared/auth/localSessionAdapter';
import { useUiStore } from '@shared/state/uiStore';
import { createSmTheme } from '@shared/theme';

import { useRuntimeConfig } from './boot/RuntimeConfig';
import { router } from './router';

// Composition order is fixed (design §8.1, task 004):
// ThemeProvider → CssBaseline → QueryClientProvider → AuthProvider → RouterProvider.
export function AppProviders() {
  const mode = useUiStore((state) => state.mode);
  const theme = useMemo(() => createSmTheme(mode), [mode]);
  const queryClient = useMemo(() => createQueryClient(), []);
  const config = useRuntimeConfig();
  const cpClient = useMemo(() => createCpClient(config.apiBasePath), [config.apiBasePath]);
  // The demo channel talks to the local launcher. Other DEV channels retain the fixture adapter
  // selected inside AuthProvider, so tests and the frontend's mock gallery do not change.
  const localDemoAdapter = useMemo(
    () => (config.channel === 'local-demo' ? createLocalSessionAdapter(cpClient) : undefined),
    [config.channel, cpClient],
  );
  useEffect(() => {
    if (config.channel !== 'local-demo') {
      configureCsrf({});
      return undefined;
    }
    configureCsrf({
      headerName: 'X-SM-CSRF',
      getToken: () => document.cookie.split('; ').find((value) => value.startsWith('SM_DEMO_CSRF='))?.split('=')[1],
    });
    return () => configureCsrf({});
  }, [config.channel]);

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <QueryClientProvider client={queryClient}>
        <AuthProvider
          cpClient={cpClient}
          {...(localDemoAdapter === undefined ? {} : { adapterOverride: localDemoAdapter })}
        >
          <RouterProvider router={router} />
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
