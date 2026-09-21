import { LoginPage } from '@features/auth';

import { useRuntimeConfig } from '../boot/RuntimeConfig';

// `idleTimeoutMinutes` is an app-layer concern (RuntimeConfig) that a
// feature may not import directly (boundaries) — this is the call site
// RequireAuth.tsx and LoginPage.tsx's headers both point to.
export default function LoginRoute() {
  const config = useRuntimeConfig();
  return (
    <LoginPage
      idleTimeoutMinutes={config.idleTimeoutMinutes}
      localBootstrap={config.channel === 'local-demo'}
    />
  );
}
