import '@shared/theme/tokens.css';

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { AppProviders } from '@app/providers';

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
if (import.meta.env.DEV && new URLSearchParams(window.location.search).get('mock') === '1') {
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
  );
} else {
  mount();
}
