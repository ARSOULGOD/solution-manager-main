/**
 * Boots the app with deploy-time configuration (design §13, §21). Fetched
 * once, before the real app renders; a fetch or parse failure renders a
 * visible boot error page in place of the app — never a silent default
 * (task 005 acceptance). Later tasks (026 HTTP core, 031 idle lock) read
 * the resolved config via `useRuntimeConfig`.
 */
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';

import { loadConfig, type RuntimeConfig } from './loadConfig';

const RuntimeConfigContext = createContext<RuntimeConfig | null>(null);

type BootState =
  | { status: 'loading' }
  | { status: 'ready'; config: RuntimeConfig }
  | { status: 'error'; error: unknown };

function bootErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Unknown boot error.';
}

function BootErrorPage({ error }: { error: unknown }) {
  return (
    <main id="main-content" role="alert">
      <h1>Solution Manager could not start</h1>
      <p>The deploy-time configuration could not be loaded.</p>
      <p>{bootErrorMessage(error)}</p>
    </main>
  );
}

export function RuntimeConfigBoot({ children }: { children: ReactNode }) {
  const [state, setState] = useState<BootState>({ status: 'loading' });

  useEffect(() => {
    let cancelled = false;

    loadConfig()
      .then((config) => {
        if (!cancelled) {
          setState({ status: 'ready', config });
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setState({ status: 'error', error });
        }
      });

    return () => {
      cancelled = true;
    };
  }, []);

  if (state.status === 'error') {
    return <BootErrorPage error={state.error} />;
  }

  if (state.status === 'loading') {
    return null;
  }

  return (
    <RuntimeConfigContext.Provider value={state.config}>{children}</RuntimeConfigContext.Provider>
  );
}

export function useRuntimeConfig(): RuntimeConfig {
  const config = useContext(RuntimeConfigContext);
  if (config === null) {
    throw new Error('useRuntimeConfig() called outside a ready RuntimeConfigBoot.');
  }
  return config;
}
