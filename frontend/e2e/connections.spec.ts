/**
 * Plan 013 Task 7 — the Connections screen journey, over the `fixture`
 * project (MSW-backed dev server): `playwright.config.ts`'s header is why
 * — there is no live-backend e2e project in this repo at all, so every
 * journey in this file goes through the same MSW double `journeys.spec.ts`
 * uses, including its 422/503 failure paths
 * (`shared/testdoubles/handlers/cp.ts`'s real `connections` tag handlers).
 *
 * Steers through: create a Solace broker (plain Save), create one with
 * Test & Save (a forced failure via an "unreachable" host, then success),
 * create a Remote server with both auth methods, edit a connection and
 * rotate its secret, deactivate, and delete blocked-then-unblocked by
 * `usedByCount`.
 *
 * **Must-not, per journey:** a failed test must never write a row (the
 * create stays off the list); a rotated secret must never show a prior
 * value; a blocked delete must never remove the row.
 */
import { expect, test, type Page } from '@playwright/test';

function watchConsole(page: Page, expected: readonly RegExp[] = []): string[] {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() !== 'error') {
      return;
    }
    const text = message.text();
    if (expected.some((pattern) => pattern.test(text))) {
      return;
    }
    errors.push(text);
  });
  return errors;
}

test('Connections · create a Solace broker with a plain Save', async ({ page }) => {
  const errors = watchConsole(page);
  await page.goto('/connections');
  await expect(page.getByRole('heading', { name: 'Connections' })).toBeVisible();

  await page.getByRole('tab', { name: /Solace brokers/ }).click();
  await page.getByRole('button', { name: '+ New connection' }).click();

  await page.getByLabel('Host').fill('tcps://broker-plain.example.test');
  await page.getByLabel('Message VPN').fill('journey-vpn');
  await page.getByRole('button', { name: 'Save changes' }).click();

  // Must-not: Save (testBeforeSave: false) never asks for a test; the
  // drawer closes and the new row is on the list straight away.
  await expect(page.getByText('tcps://broker-plain.example.test')).toBeVisible();

  expect(errors).toEqual([]);
});

test('Connections · Test & Save fails against an unreachable host, then succeeds', async ({ page }) => {
  // The 503/422 fixture responses ARE this journey's subject; the browser
  // still logs the non-2xx as a resource error.
  const errors = watchConsole(page, [/status of (503|422)/]);
  await page.goto('/connections');
  await page.getByRole('tab', { name: /Solace brokers/ }).click();
  await page.getByRole('button', { name: '+ New connection' }).click();

  await page.getByLabel('Host').fill('tcps://unreachable.example.test');
  await page.getByLabel('Message VPN').fill('journey-vpn');
  await page.getByRole('button', { name: 'Test & save' }).click();

  // MUST-NOT: a failed test never writes a row — the drawer stays open,
  // the rejection renders inline, and the host the operator typed is
  // still there (not cleared, not closed).
  await expect(page.getByText('Service unavailable', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Host')).toHaveValue('tcps://unreachable.example.test');

  // The same drawer, corrected, now succeeds.
  await page.getByLabel('Host').fill('tcps://broker-tested.example.test');
  await page.getByRole('button', { name: 'Test & save' }).click();
  await expect(page.getByText('tcps://broker-tested.example.test')).toBeVisible();

  expect(errors).toEqual([]);
});

test('Connections · create a Remote server with basic auth and with private-key auth', async ({ page }) => {
  const errors = watchConsole(page);
  await page.goto('/connections');
  await page.getByRole('tab', { name: /Remote servers/ }).click();

  await page.getByRole('button', { name: '+ New connection' }).click();
  await page.getByLabel('Host').fill('sftp-basic.example.test');
  await page.getByLabel('Username').fill('ingest-basic');
  // The discriminator's visual default ("basic") is not in state until
  // chosen explicitly — selecting it is what reveals the Password field.
  await page.getByLabel('Authentication').selectOption('basic');
  await page.getByLabel('Password', { exact: true }).fill('a-basic-secret');
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(page.getByText('sftp-basic.example.test')).toBeVisible();

  await page.getByRole('button', { name: '+ New connection' }).click();
  await page.getByLabel('Host').fill('sftp-pk.example.test');
  await page.getByLabel('Username').fill('ingest-pk');
  await page.getByLabel('Authentication').selectOption('private-key');
  await expect(page.getByLabel('Private key')).toBeVisible();
  await page.getByLabel('Private key').fill('-----BEGIN KEY-----fixture-----END KEY-----');
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(page.getByText('sftp-pk.example.test')).toBeVisible();

  expect(errors).toEqual([]);
});

test('Connections · edit and rotate a secret, deactivate, then delete blocked then unblocked', async ({
  page,
}) => {
  const errors = watchConsole(page, [/status of 409/]);
  await page.goto('/connections');
  await page.getByRole('tab', { name: /Solace brokers/ }).click();

  // The seeded, in-use row (usedByCount: 3, `cp.ts`'s `SEED_REAL_CONNECTIONS`).
  await page.getByText('prod-broker-01').click();
  await expect(page.getByLabel('Host')).toHaveValue('tcps://broker.acme.internal');
  // MUST-NOT: a secret already set never shows its value.
  const password = page.getByLabel('Password', { exact: true });
  await expect(password).toHaveValue('');

  await page.getByLabel('Host').fill('tcps://broker.acme.internal.renamed');
  await password.fill('a-rotated-secret');
  await page.getByRole('button', { name: 'Save changes' }).click();
  // In use (usedByCount: 3): the impact warning gates the save.
  await expect(page.getByText(/3 instances reference this connection/)).toBeVisible();
  await page.getByRole('button', { name: 'Save changes' }).last().click();
  await expect(page.getByText('tcps://broker.acme.internal.renamed')).toBeVisible();

  // Deactivate: a one-directional action, no toggle back.
  await page.getByText('prod-broker-01').click();
  await page.getByRole('button', { name: 'Deactivate' }).click();
  await expect(page.getByText('prod-broker-01')).toHaveCount(0); // active-only view hides it
  await page.getByLabel('Show inactive').click();
  await expect(page.getByText('prod-broker-01')).toBeVisible();

  // Delete, blocked while still in use, then unblocked once nothing refers to it.
  await page.getByText('prod-broker-01').click();
  const deleteButton = page.getByRole('button', { name: 'Delete' });
  await expect(deleteButton).toBeDisabled();
  await expect(deleteButton).toHaveAccessibleDescription(/Deactivate it instead/);
  await page.getByRole('button', { name: 'Cancel' }).click();

  // The seeded unused row (usedByCount: 0) deletes cleanly.
  await page.getByText('retired-broker').click();
  await page.getByRole('button', { name: 'Delete' }).click();
  await expect(page.getByText('Delete this connection?')).toBeVisible();
  const confirmButtons = page.getByRole('button', { name: 'Delete' });
  await confirmButtons.last().click();
  await expect(page.getByText('retired-broker')).toHaveCount(0);

  expect(errors).toEqual([]);
});
