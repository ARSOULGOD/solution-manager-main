import '@shared/theme/tokens.css';

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { AppProviders } from '@app/providers';
import { logger } from '@shared/observability';

import { RuntimeConfigBoot } from './boot/RuntimeConfig';

const rootElement = document.getElementById('root');
if (rootElement === null) {
  throw new Error('Root element "#root" was not found in index.html.');
}

const root = createRoot(rootElement);

function mount() {
  root.render(
    <StrictMode>
      <RuntimeConfigBoot>
        <AppProviders />
      </RuntimeConfigBoot>
    </StrictMode>,
  );
}

// `npm run dev` has no backend to talk to, so the same MSW handlers the
// unit suite uses under `msw/node` (task 033) are started here under
// `msw/browser` — otherwise every fixture-backed screen (038+) has no way
// to be viewed in a real browser at all (found while verifying task 038;
// see its report, Ruling 8). `import.meta.env.DEV` is statically false in
// a production build, so Vite dead-code-eliminates this whole branch —
// `msw/browser` and the fixture handlers never reach the shipped bundle.
//
// `VITE_USE_REAL_AUTH=true` (plan 132, Task 2/9/10) is the one exception:
// it means "run this DEV session against the real backend through the
// Vite proxy" (`AuthProvider.tsx`'s `selectAdapter` already reads the same
// flag to skip the mock auth adapter). MSW intercepts every fetch it has a
// handler for regardless of which adapter issued it, so leaving the worker
// running would silently swallow the real `/cp/auth/*` and `/cp/hierarchy/*`
// calls behind stale fixture data even with the flag set. Matching
// `selectAdapter`'s own condition here — instead of only removing the
// auth/hierarchy handler groups — lets every fixture handler get out of the
// way together, which is what "against the real backend" actually means.
const useRealAuth = import.meta.env.DEV && import.meta.env['VITE_USE_REAL_AUTH'] === 'true';

/**
 * A browser can keep the mock MSW worker registered after switching a running
 * dev session to real-backend mode. Unregister only the worker shipped by this
 * app; otherwise it can continue answering API requests before Vite's proxy
 * gets a chance to forward them to the backend.
 */
async function unregisterMockServiceWorker(): Promise<void> {
  if (!('serviceWorker' in navigator)) {
    return;
  }
  const registrations = await navigator.serviceWorker.getRegistrations();
  await Promise.all(
      registrations
      .filter((registration) => {
        const scriptUrl =
          registration.active?.scriptURL ??
          registration.waiting?.scriptURL ??
          registration.installing?.scriptURL;
        return scriptUrl?.endsWith('/mockServiceWorker.js') === true;
      })
      .map((registration) => registration.unregister()),
  );
}

if (import.meta.env.DEV && !useRealAuth) {
  void Promise.all([
    import('@shared/testdoubles/handlers'),
    // Solution Packages loaded into this session through the Solutions
    // catalog (`REQ-001`'s acceptance gate, rendered by the real
    // `project()` engine). Prepended, so its routes win over the defaults;
    // the `msw/node` unit suite never loads it, keeping the default
    // fixture set exactly as the reference HTML shows it.
    import('./dev/devPackageHandlers'),
    import('./dev/devPackageStore'),
    import('./dev/devDeclaredInstances'),
    import('msw/browser'),
  ]).then(
    ([
      { handlers },
      { devPackageHandlers },
      { devPackageStore },
      { devDeclaredInstances },
      { setupWorker },
    ]) => {
      // Forces the session's stored packages and declarations to be read and
      // revalidated here, before anything renders, rather than inside the
      // first request that happens to touch either store. `list()` cannot
      // throw — a corrupt or stale entry is dropped, never propagated (see
      // each store's header) — so an unreadable `sessionStorage` cannot stop
      // the console starting.
      devPackageStore.list();
      devDeclaredInstances.list();
      return setupWorker(...devPackageHandlers, ...handlers)
        .start()
        .then(mount);
    },
  ).catch((error: unknown) => {
    // A local MSW/service-worker problem must not leave the application blank.
    // Mount the UI so its own error/loading states remain visible and keep the
    // startup failure available in the browser console for diagnosis.
    logger.error('development.mock-worker-failed', {
      message: error instanceof Error ? error.message : String(error),
    });
    mount();
  });
} else {
  if (useRealAuth) {
    void unregisterMockServiceWorker().finally(mount);
  } else {
    mount();
  }
}
