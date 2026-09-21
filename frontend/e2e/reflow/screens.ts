/**
 * The screen list `reflow.spec.ts` walks (task 110's "every screen in
 * every major state"). Runs on the `fixture` project (the dev server —
 * `playwright.config.ts`'s own header) so every authenticated route
 * renders real fixture data through MSW, same as `designer.spec.ts`.
 * `cache` / `cache-prod-03` are real fixture ids
 * (`src/shared/testdoubles/data/{solutions,instances}.ts`), not invented
 * ones.
 *
 * Not every screen state is enumerated here — `Admin`/`Targets` are bare
 * `RouteStub`s (Phase 7, gated, per `FE-098`'s own report), and a
 * dialog-open state is left for a follow-up rather than scripting a full
 * edit/save flow into a width-sweep fixture list; the screens below still
 * cover every real Phase 0-6 route plus the two states most likely to
 * reflow badly (Designer's blocking-validation banner, a populated data
 * grid, a wizard with locked steps).
 */
import type { Page } from '@playwright/test';

export interface ReflowScreen {
  name: string;
  path: string;
  /** Ready hint: a selector `reflow.spec.ts` waits for before measuring. */
  readySelector: string;
}

export const REFLOW_SCREENS: readonly ReflowScreen[] = [
  { name: 'login', path: '/login', readySelector: 'text=Sign in' },
  {
    name: 'login-session-expired',
    path: '/login?arrival=session',
    readySelector: 'text=Your session ended',
  },
  { name: 'home', path: '/', readySelector: 'main' },
  { name: 'solutions-catalog', path: '/solutions', readySelector: 'main' },
  { name: 'instances-list', path: '/solutions/cache/instances', readySelector: 'main' },
  {
    name: 'designer',
    path: '/solutions/cache/instances/cache-prod-03/designer',
    readySelector: 'main',
  },
  {
    name: 'governance',
    path: '/solutions/cache/instances/cache-prod-03/governance',
    readySelector: 'main',
  },
  {
    name: 'explore',
    path: '/solutions/cache/instances/cache-prod-03/monitoring/explore',
    readySelector: 'main',
  },
  {
    name: 'dashboard',
    path: '/solutions/cache/instances/cache-prod-03/monitoring/dashboards/default',
    readySelector: 'main',
  },
  { name: 'targets-stub', path: '/targets', readySelector: 'main' },
  { name: 'admin-stub', path: '/admin', readySelector: 'main' },
  { name: 'not-found', path: '/this-route-does-not-exist', readySelector: 'main' },
] as const;

export async function gotoScreen(page: Page, screen: ReflowScreen): Promise<void> {
  await page.goto(screen.path);
  await page.locator(screen.readySelector).first().waitFor({ state: 'visible' });
}
