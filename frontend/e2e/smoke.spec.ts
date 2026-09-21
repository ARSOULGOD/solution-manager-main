/**
 * The harness testing itself (task 008's own acceptance): a trivial e2e
 * proving the Playwright → preview-server → real `public/config.json`
 * boot path (`RuntimeConfigBoot`, task 005) actually renders a page,
 * without any mocking — no component makes a plane API call yet.
 *
 * The route it lands on changed under it. When this was written `/`
 * rendered Home; since task 031 every authenticated screen sits behind
 * `RequireAuth` (mounted by `AuthenticatedLayout`, task 036), so an
 * unauthenticated visitor to `/` is redirected to `/login` — and no
 * backend exists to authenticate against, the same absence
 * `e2e/login.spec.ts` documents. Asserting Home here would mean seeding
 * a session the app never issued, which is the one thing this test must
 * not do. What it still proves is everything task 008 asked of it: the
 * preview server serves the build, the runtime config resolves (a
 * missing or malformed `config.json` renders the boot error state, not
 * a route), the router runs, and the first paint is clean.
 */
import { expect, test } from '@playwright/test';

test('boots and renders, redirecting an unauthenticated visitor to sign-in', async ({ page }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') {
      errors.push(message.text());
    }
  });

  await page.goto('/');

  await expect(page).toHaveTitle('Solution Manager');
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  expect(errors).toEqual([]);
});
