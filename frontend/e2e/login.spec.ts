/**
 * Login journey (task 037). Runs against the production preview build — no
 * backend exists in this repo yet (Console REST is unwritten, `localSessionAdapter`'s
 * own header marks `/auth/login` CONTRACT: TBD), so this proves the real
 * mechanism up to the point a real backend takes over: the page renders,
 * arrivals from the query string render their alert, and a submit against
 * the (absent) endpoint fails into a visible, non-blank state rather than
 * hanging or throwing — never a fabricated "success" (same honesty
 * `e2e/smoke.spec.ts` already established for this harness).
 */
import { expect, test } from '@playwright/test';

test('renders the sign-in form with no console errors', async ({ page }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') {
      errors.push(message.text());
    }
  });

  await page.goto('/login');

  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  await expect(page.getByLabel('Username')).toBeVisible();
  await expect(page.getByLabel(/^password/i)).toBeVisible();
  expect(errors).toEqual([]);
});

test('a session-expired arrival renders its warning before any submission', async ({ page }) => {
  await page.goto('/login?arrival=session');

  await expect(page.getByText('Your session ended')).toBeVisible();
});

test('an idle-timeout arrival renders its info notice, distinct from session-expired', async ({
  page,
}) => {
  await page.goto('/login?arrival=idle');

  await expect(page.getByText(/signed out after \d+ minutes? idle/i)).toBeVisible();
  await expect(page.getByText('Your session ended')).not.toBeVisible();
});

test('submitting against the real (currently backend-less) endpoint ends in a visible error state, never a silent hang', async ({
  page,
}) => {
  await page.goto('/login');

  await page.getByLabel('Username').fill('devon.ramirez');
  await page.getByLabel(/^password/i).fill('whatever');
  await page.getByRole('button', { name: 'Sign in' }).click();

  // No backend exists to authenticate against yet — either the
  // password-field error (a parsed 4xx) or the unreachable banner (a
  // genuine network failure) is the honest outcome; which one depends on
  // how the preview server's static file server answers an unknown POST
  // route, not on anything this test should assert as a business rule.
  await expect(
    page.getByText('Username or password is incorrect.').or(page.getByRole('alert')),
  ).toBeVisible({ timeout: 10_000 });
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
});
