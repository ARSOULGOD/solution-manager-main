/**
 * FE-101 · a deliberately violating entry point.
 *
 * Nothing imports this file. `scripts/production-assertions.selftest.mjs`
 * builds it — with the real `vite.config.ts`, in production mode, through
 * the real bundler — as the fourth DEV-only thing a careless change could
 * ship: the auth mock, the dev gallery, MSW and a fixture dataset, all
 * reached WITHOUT an `import.meta.env.DEV` guard, which is precisely the
 * mistake `FE-D24`'s guard exists to prevent.
 *
 * The self-test then requires `no-auth-mock`, `no-dev-gallery`, `no-msw`
 * and `no-fixture-dataset` to fire on that build, naming these four
 * modules. Described inputs prove the assertion logic; this proves the
 * assertions still recognise the real thing after a bundler upgrade
 * renames a field, splits chunks differently, or inlines a module — the
 * failure mode a hand-written fixture object cannot reproduce.
 *
 * Same idea as `src/test/lint-fixtures/` (task 009) and
 * `e2e/reflow.selftest.spec.ts` (task 110): a guardrail is only worth its
 * green tick if something in the repo is known to make it go red.
 *
 * ⚠️ Do not import this from application code, and do not "fix" it. It is
 * supposed to be wrong.
 */
import { buildGalleryRoutes } from '@app/dev/gallery.routes';
import { createDevMockAdapter } from '@shared/auth/devMockAdapter';
import { CACHE_INSTANCES } from '@shared/testdoubles/data/instances';

// Referenced, not just imported: an unused import is dead code the bundler
// would drop, and a build that dropped it would prove nothing.
export const violation = {
  adapter: createDevMockAdapter,
  routes: buildGalleryRoutes,
  instances: CACHE_INSTANCES.length,
  worker: () => import('msw/browser').then(({ setupWorker }) => setupWorker),
};
