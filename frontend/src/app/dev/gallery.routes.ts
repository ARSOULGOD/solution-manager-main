/**
 * `/dev/gallery`, registered only when `import.meta.env.DEV` — the same
 * rule as the auth mock (`FE-D24`), so it cannot reach a production
 * bundle. Kept independent of whatever FE-030's dev-mock guard does
 * (execution.md finding F11): a shared "is this DEV" helper would make
 * two unrelated production-safety guarantees depend on one function.
 */
import { lazy, Suspense, createElement } from 'react';
import type { RouteObject } from 'react-router';

// The `import('./Gallery')` call must appear textually inside a literal
// `if (import.meta.env.DEV)` check — not behind a function parameter, not
// hoisted to module scope. Vite replaces `import.meta.env.DEV` with a
// literal `false` per-module in a production build, and esbuild/Rollup
// then dead-code-eliminate the branch (and the dynamic import inside it)
// before the module graph is built, so no Gallery-*.js chunk is emitted.
// Routing the same check through a runtime parameter defeats that
// constant-folding (confirmed empirically: `npm run build` still shipped
// the chunk when this lived behind an `isDev` argument) since the
// bundler can't prove a parameter's value across the call site.
export function buildGalleryRoutes(): RouteObject[] {
  if (!import.meta.env.DEV) {
    return [];
  }

  const Gallery = lazy(() => import('./Gallery'));
  const element = createElement(Suspense, { fallback: null }, createElement(Gallery));

  return [{ path: '/dev/gallery', element }];
}
