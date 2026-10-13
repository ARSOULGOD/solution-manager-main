/**
 * Gate D journey 1 (task 125, `execution.md` §6.4/§19): login → instance →
 * edit → save (generation moves) → apply → `APPLY_INDETERMINATE` rendered
 * — neither success nor failure. Proves the UX contract; it does NOT prove
 * `dp-remote` apply correctness (`DECISIONS.md` open item 1 / R1).
 *
 * Runs against the `fixture` project (the dev server — `playwright.config.ts`'s
 * own header explains why: this is named a "fixture e2e journey" in the
 * plan, and MSW/the dev-mock auth adapter exist only there).
 *
 * `cache-prod-03` is a real fixture id (`src/shared/testdoubles/data/instances.ts`)
 * declared at `packageVersion: 6` — never an invented one. Its Designer
 * excluding `Max weight` (v7-only) is this journey's own proof that the
 * version threading `FE-121`/`FE-122` built reaches all the way to a real
 * browser, not only the unit suite.
 */
import { expect, test } from '@playwright/test';

test('login, edit, save (generation moves), apply → APPLY_INDETERMINATE — on a real v6 instance', async ({
  page,
}) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') {
      errors.push(message.text());
    }
  });

  // ---- login ----
  // The dev-mock auth adapter (`FE-D24`) resolves an identity immediately
  // — there is no backend to authenticate against in this repo yet, so
  // this proves the same thing `/login`'s redirect proves for an
  // unauthenticated visitor to the `preview` build: the app never lands
  // on a blank or hung screen. Landing on a real page, not `/login`, IS
  // this environment's login step.
  await page.goto('/solutions/cache/instances/cache-prod-03/designer');
  await expect(page).not.toHaveURL(/\/login/);
  await expect(page.getByRole('heading', { name: 'cache-prod-03' })).toBeVisible();

  // `SectionCard`'s own toggle carries `aria-expanded`; `SectionNav`'s
  // (persistent-rail, at this desktop width) jump button, same visible
  // text, does not — disambiguates the two, same trick
  // `DesignerReflow.test.tsx` uses.
  const section = (name: string) =>
    page.getByRole('button', { name: new RegExp(`^${name}`), expanded: false });

  // ---- instance: a real v6 instance renders no v7 field ----
  await expect(page.getByText('pkg v6')).toBeVisible();
  await section('Sizing').click();
  await expect(page.getByLabel('Max entries', { exact: false })).toBeVisible();
  await expect(page.getByLabel('Max weight', { exact: false })).toHaveCount(0);

  // ---- edit: fill every required field the fixture draft leaves empty ----
  await section('General').click();
  await page.getByLabel('Namespace', { exact: false }).fill('ledger-gate-d');

  await section('Keying').click();
  await page.getByLabel('Key source', { exact: false }).click();
  await page.getByRole('option', { name: 'TOPIC' }).click();

  // Sizing is already expanded from the version check above.
  await page.getByLabel('Max entries', { exact: false }).fill('100000');

  await section('Broker').click();
  const host = page.getByLabel('Host', { exact: false });
  await expect(host).toBeVisible();
  await host.scrollIntoViewIfNeeded();
  await host.click();
  await host.pressSequentially('broker-gate-d.example.com', { delay: 10 });
  await expect(host).toHaveValue('broker-gate-d.example.com');

  await expect(page.getByText(/blocking/i)).toHaveCount(0);

  // ---- save: the generation moves ----
  // 'gen-41' is the default fixture handler's own documented baseline
  // (`handlers/dp.ts`'s designer GET) — a known constant, not something
  // this test needs to fetch first. The save POST always returns a fresh
  // `gen-${Date.now()}`, so any successful save necessarily differs from it.
  const saveResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes('/dp/instances/cache-prod-03/config') &&
      response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Save draft' }).click();
  const saveResponse = await saveResponsePromise;
  const saved = (await saveResponse.json()) as { generation: string };

  // FE-099's first must-not: the generation is on the REQUEST, not merely
  // returned by it. Without it on the way out there is no optimistic
  // concurrency at all — the server cannot tell a fresh save from one
  // built on a record another operator has already replaced
  // (`FE-D18` clause A). Asserting only the response would pass against a
  // client that never sent one.
  const sentBody = saveResponse.request().postDataJSON() as {
    generation?: string;
    fields?: Record<string, Record<string, unknown>>;
  };
  expect(sentBody.generation).toBe('gen-41');

  // FE-099's second must-not: an INACTIVE branch is absent from the
  // payload. `Key source` was set to TOPIC above, so the package's
  // `payload-key-json-pointer` field — gated `when: {key-source: PAYLOAD}`
  // — is not rendered, and `renderedFields()` (`FE-D17`) must not ship it.
  // A payload carrying the untaken branch writes configuration the
  // operator never saw, which is how a hidden field silently takes effect.
  // `fields` is keyed by COMPONENT INSTANCE id, with the field keys nested
  // one level inside — checking the top level only would make the
  // exclusion below pass against literally any payload.
  const sentKeys = Object.entries(sentBody.fields ?? {}).flatMap(([component, values]) =>
    Object.keys((values ?? {}) as Record<string, unknown>).map((field) => `${component}.${field}`),
  );
  expect(sentKeys.length).toBeGreaterThan(0);
  expect(
    sentKeys.filter((key) => key.includes('payload-key-json-pointer')),
    `inactive branch leaked into the payload: ${JSON.stringify(sentKeys)}`,
  ).toEqual([]);
  // Anchors the exclusion above so it cannot pass vacuously. The
  // discriminator that SELECTS the branch is in the payload, in the same
  // `component.field` shape the exclusion searches — so a missing
  // `payload-key-json-pointer` means genuinely absent, not "looked in the
  // wrong place". (An earlier version of this test checked only the
  // top-level keys, where `fields` holds component ids, and would have
  // passed against any payload at all.)
  //
  // `topic-key-levels`, the active branch's own field, is deliberately
  // NOT asserted: it is an empty optional the journey never fills, and
  // `renderedFields()` omits it for that reason rather than because of
  // the branch.
  expect(
    sentBody.fields?.['cache-engine']?.['key-source'],
    `payload: ${JSON.stringify(sentKeys)}`,
  ).toBe('TOPIC');

  expect(saved.generation).not.toBe('gen-41');
  await expect(page.getByText('No unsaved changes')).toBeVisible();

  // ---- apply: renders APPLY_INDETERMINATE, never a success verdict ----
  await page.getByRole('button', { name: 'Apply…' }).click();
  await expect(page.getByText('Choose a restart strategy')).toBeVisible();
  await page.getByRole('button', { name: 'Continue' }).click();
  await expect(page.getByText('Apply requires a restart')).toBeVisible();
  await page.getByRole('button', { name: /Apply & restart/ }).click();

  await expect(page.getByText('Outcome not confirmed')).toBeVisible();
  const bodyText = await page.textContent('body');
  expect(bodyText).not.toMatch(/applied successfully|apply succeeded/i);

  expect(errors).toEqual([]);
});
