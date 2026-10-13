import { CssBaseline, ThemeProvider } from '@mui/material';
import { QueryClientProvider } from '@tanstack/react-query';
import { useMemo } from 'react';
import { RouterProvider } from 'react-router';

import { createCpClient } from '@shared/api/cpClient';
import { createQueryClient } from '@shared/api/queryClient';
import { AuthProvider } from '@shared/auth/AuthProvider';
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

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <QueryClientProvider client={queryClient}>
        <AuthProvider cpClient={cpClient}>
          <RouterProvider router={router} />
        </AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
