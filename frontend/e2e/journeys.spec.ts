/**
 * `FE-099` — the end-to-end journeys, over the fixture backend.
 *
 * Journey 1 (the walking skeleton) lives in `designer.spec.ts`, where it
 * was built by `FE-125`; this file carries journeys 2–7. They run on the
 * `fixture` Playwright project — the dev server, MSW and the dev-mock auth
 * adapter — for the reason `playwright.config.ts`'s header gives.
 *
 * **Every journey asserts a must-not.** The acceptance line is explicit
 * that a happy path alone is not enough, and each of these is a case where
 * the plausible wrong behaviour is worse than an outright failure: a save
 * that silently overwrites, a gap drawn as a zero, a control that vanishes
 * with no explanation. A test that only proves the happy path would pass
 * against all three.
 *
 * Two journeys steer the session through DEV-only URL overrides
 * (`shared/testdoubles/e2eOverrides.ts`) because they need a state no
 * fixture *record* can express. Journey 2's conflict is deliberately not
 * one of those: it is produced by a real second actor racing a real
 * generation, which is what a conflict actually is.
 */
import { expect, test, type Page } from '@playwright/test';

const SOLUTION = 'cache';
/** A v6 instance — the same one journey 1 drives. */
const INSTANCE = 'cache-prod-03';
/** The fixture's `not_responding` instance (`data/instances.ts`) — journey 3's incident. */
const RED_INSTANCE = 'cache-stage-01';

/**
 * Collects console errors, the way `designer.spec.ts` does, minus any the
 * journey has declared it *expects*.
 *
 * `expected` is a narrow allowance, not an off switch: a journey that
 * deliberately provokes an HTTP error still gets a browser-level "failed
 * to load resource" line, and swallowing every console error to cope with
 * that would also swallow the React warning or the unhandled rejection
 * this check exists to catch. Each entry has to name the specific message
 * the journey is responsible for.
 */
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

/** Explore's step 1 + step 2: the metric radio, then the instance checkbox. */
async function selectMetricAndInstance(page: Page, instanceId: string): Promise<void> {
  // The metric rows are radios whose accessible name is empty (the label
  // is a sibling), so the first radio IS `cache.hit_rate` — the catalog's
  // first plottable entry.
  await page.getByRole('radio').first().click();
  await page.locator('label', { hasText: instanceId }).first().click();
  // The chart and its data table only exist once a series has resolved.
  await expect(page.getByRole('button', { name: /Open data table/i })).toBeEnabled();
}

interface DataTableRow {
  timestamp: string;
  value: string;
}

/** Reads the per-series data table as `{timestamp, value}` rows. */
async function readDataTableRows(table: ReturnType<Page['getByRole']>): Promise<DataTableRow[]> {
  const text = await table.innerText();
  return text
    .split('\n')
    .map((line) => line.split('\t'))
    .filter((cells) => cells.length >= 2 && /\d{1,2}:\d{2}/.test(cells[0] ?? ''))
    .map((cells) => ({ timestamp: (cells[0] ?? '').trim(), value: (cells[1] ?? '').trim() }));
}

// ───────────────────────────── Journey 2 ─────────────────────────────────

test('journey 2 · a concurrent save renders a diff and never overwrites', async ({ page }) => {
  // The 409 IS this journey's subject; the browser logs every non-2xx as a
  // resource error regardless of the app handling it correctly.
  const errors = watchConsole(page, [/status of 409/]);

  await page.goto(`/solutions/${SOLUTION}/instances/${INSTANCE}/designer`);
  await expect(page.getByRole('heading', { name: INSTANCE })).toBeVisible();

  // A real second actor, not a flag: this POST advances the server's
  // generation while the page still holds the one it loaded. From here the
  // page's own save is genuinely stale — the same race two operators hit.
  const theirGeneration = await page.evaluate(async (instanceId) => {
    const response = await fetch(`/api/dp/instances/${instanceId}/config`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        fields: { 'cache-engine.namespace': 'theirs' },
        generation: 'gen-41',
      }),
    });
    return ((await response.json()) as { generation: string }).generation;
  }, INSTANCE);
  expect(theirGeneration).not.toBe('gen-41');

  // The fixture draft leaves several required fields empty, so Save stays
  // disabled until they are filled — the same sequence journey 1 walks.
  // `mine-not-theirs` is the value this journey must still own at the end.
  const section = (name: string) =>
    page.getByRole('button', { name: new RegExp(`^${name}`), expanded: false });

  await section('General').click();
  const namespace = page.getByLabel('Namespace', { exact: false });
  await namespace.fill('mine-not-theirs');

  await section('Keying').click();
  await page.getByLabel('Key source', { exact: false }).click();
  await page.getByRole('option', { name: 'TOPIC' }).click();

  await section('Sizing').click();
  await page.getByLabel('Max entries', { exact: false }).fill('100000');

  await section('Broker').click();
  const host = page.getByLabel('Host', { exact: false });
  await host.scrollIntoViewIfNeeded();
  await host.click();
  await host.pressSequentially('broker-conflict.example.com', { delay: 10 });

  const save = page.getByRole('button', { name: 'Save draft' });
  await expect(save).toBeEnabled();
  await save.click();

  // The conflict is explained, and the explanation leads with the thing an
  // operator most needs to know.
  await expect(page.getByText('Someone else saved first')).toBeVisible();
  await expect(page.getByText('Nothing was overwritten')).toBeVisible();

  // MUST-NOT: no working path overwrites the other actor's record. The
  // three resolutions the contract does not specify are rendered so an
  // operator can see they do not exist — and every one of them is
  // disabled (`FE-D18` clause A, ConflictDialog's own header).
  for (const label of ['Overwrite anyway', 'Take theirs', 'Merge per field']) {
    await expect(page.getByRole('button', { name: label })).toBeDisabled();
  }

  // MUST-NOT: the edit is still the operator's own. A dialog that had
  // quietly taken "theirs" would show the other actor's value here.
  await expect(namespace).toHaveValue('mine-not-theirs');

  expect(errors).toEqual([]);
});

// ───────────────────────────── Journey 5 ─────────────────────────────────

test('journey 5 · a ?sel= / ?focus= link reproduces the sender’s view', async ({ page }) => {
  const errors = watchConsole(page);

  // Baseline first: without the parameters, the bulk actions the link is
  // supposed to produce are absent. Asserting the link's effect without
  // this would pass against a screen that always shows them.
  await page.goto(`/solutions/${SOLUTION}/instances`);
  await expect(page.getByRole('link', { name: 'cache-prod-01' })).toBeVisible();
  await expect(page.getByRole('button', { name: /^Remove \d/ })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Restart \d/ })).toHaveCount(0);

  await page.goto(
    `/solutions/${SOLUTION}/instances?sel=cache-prod-01,cache-prod-02&focus=cache-stage-01`,
  );

  // The selection arrives: two rows selected, and the bulk actions say two.
  await expect(page.getByRole('button', { name: 'Remove 2…' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Restart 2…' })).toBeVisible();

  // `?focus=` lands on the row rather than merely appending it — the row
  // the sender was pointing at is the one the receiver's focus is on.
  const focused = page.locator(':focus');
  await expect(focused).toContainText('cache-stage-01');

  // MUST-NOT: `?focus=` is not a selection. The receiver must not act on a
  // third instance the sender never selected — that is how a bulk restart
  // hits the wrong host.
  await expect(page.getByRole('button', { name: 'Remove 3…' })).toHaveCount(0);

  expect(errors).toEqual([]);
});

// ───────────────────────────── Journey 7 ─────────────────────────────────

test('journey 7 · the idle timer drops the session to /login with the idle arrival', async ({
  page,
}) => {
  const errors = watchConsole(page);

  // Warm the route FIRST, without the override. The `fixture` project runs
  // against the dev server, so a cold visit waits on Vite compiling this
  // route's chunk — and under a loaded worker pool that compile can outlast
  // a short idle timer, logging the session out before the page has even
  // rendered. Warming first makes the timed visit below a race against the
  // timer only, which is the thing the journey is actually testing.
  await page.goto(`/solutions/${SOLUTION}/instances`);
  await expect(page.getByRole('link', { name: 'cache-prod-01' })).toBeVisible();

  // `?e2e_idle_seconds` is DEV-only (`e2eOverrides.ts`); `config.json`
  // ships 30 minutes, which no journey can wait for.
  await page.goto(`/solutions/${SOLUTION}/instances?e2e_idle_seconds=5`);
  await expect(page.getByRole('link', { name: 'cache-prod-01' })).toBeVisible();

  // Sit still. Any of `useIdleTimer`'s activity events would reset it, so
  // the journey deliberately does nothing at all here.
  await expect(page).toHaveURL(/\/login/, { timeout: 30_000 });

  // The arrival is *idle*, and reads as an explanation rather than a
  // failure — it is not the session-expired state.
  await expect(page.getByText(/Signed out after .* idle/)).toBeVisible();

  // MUST-NOT: an idle lock is not a server rejection, and telling an
  // operator their session "ended" sends them to the wrong support path.
  await expect(page.getByText('Your session ended')).toHaveCount(0);

  expect(errors).toEqual([]);
});

// ───────────────────────────── Journey 3 ─────────────────────────────────

test('journey 3 · the incident path — a red badge leads to a gap, never a zero line', async ({
  page,
}) => {
  const errors = watchConsole(page);

  // Governance says the endpoint stopped answering.
  await page.goto(`/solutions/${SOLUTION}/instances/${RED_INSTANCE}/governance`);
  await expect(page.getByRole('heading', { name: RED_INSTANCE })).toBeVisible();
  await expect(page.getByText('Not responding').first()).toBeVisible();

  // The screen's own copy names the workflow this journey walks: "red
  // badge → open the graphs → no data confirms it is genuinely down".
  await page.getByRole('button', { name: 'Open in Monitoring' }).click();
  await expect(page).toHaveURL(new RegExp(`/${RED_INSTANCE}/monitoring$`));

  // Explore, because that is where an operator picks *this* instance —
  // the default dashboard's widgets are pinned to their own selectors.
  await page.goto(`/solutions/${SOLUTION}/instances/${RED_INSTANCE}/monitoring/explore`);
  await selectMetricAndInstance(page, RED_INSTANCE);

  // The tabular alternative is where a gap is legible without reading a
  // canvas (design §18 — the data table is the accessible channel, not a
  // convenience).
  await page.getByRole('button', { name: /Open data table/i }).click();
  const table = page.getByRole('dialog');
  await expect(table).toBeVisible();

  const rows = await readDataTableRows(table);
  expect(rows.length).toBeGreaterThan(3);

  // MUST-NOT: no rendered point may be a zero. A down endpoint that
  // reported 0 would look like a live instance serving nothing — the
  // exact misreading §A.13.4 says the gap exists to prevent.
  const zeroRows = rows.filter((row) => /^0(\.0+)?%?$/.test(row.value));
  expect(zeroRows, `zero-valued rows: ${JSON.stringify(zeroRows)}`).toEqual([]);

  // And the gap is a real absence, not a cosmetic flag: the hourly series
  // is missing whole hours rather than carrying them at some filler value.
  const hourly = 24;
  expect(rows.length).toBeLessThan(hourly);

  expect(errors).toEqual([]);
});

// ───────────────────────────── Journey 4 ─────────────────────────────────

/**
 * ⚠️ **Stops short of `FE-099`'s line for it, because the console stops
 * short too.** The task asks that "the widget appears in the operator
 * layer with its layer badge". It does not, and that is not a defect this
 * journey should paper over: **widget persistence is not built**. The save
 * dialog says so itself — "the route this saves to, and which dashboard it
 * targets, are invented — the widget-persistence contract does not exist
 * yet (§16 Q15)". Verified during this task: after a successful save the
 * dashboard still shows only its `package`-layer widgets, whether reached
 * by reload or by in-app navigation.
 *
 * So the journey asserts the whole path that DOES exist — Explore → a
 * save that states its layer → a confirmation — and pins the layer
 * vocabulary on the dashboard that renders it today. The dashboard-arrival
 * assertion belongs to whatever task closes §16 Q15.
 */
test('journey 4 · Explore → save widget, stating the layer it lands in', async ({ page }) => {
  const errors = watchConsole(page);

  await page.goto(`/solutions/${SOLUTION}/instances/${INSTANCE}/monitoring/explore`);
  await selectMetricAndInstance(page, 'cache-prod-01');

  await page.getByRole('button', { name: /Save as widget/i }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();

  // The layer is stated BEFORE the save, not discovered afterwards.
  await expect(dialog.getByText(/operator/i).first()).toBeVisible();

  // MUST-NOT: the dialog must not imply scopes v1 cannot save to. It says
  // which ones are unbuilt rather than offering them and failing later.
  await expect(dialog.getByText(/entity, tenant and personal scopes are not built/i)).toBeVisible();

  const title = `E2E hit rate ${String(Date.now())}`;
  await dialog.getByLabel(/Widget title/i).fill(title);
  await dialog.getByRole('button', { name: /^Save widget$/i }).click();

  // The save completes, closes, and names what it saved.
  //
  // Scoped to the TOAST, not the page. The saved widget can also render
  // its own title, and an unscoped text query then matches two elements
  // and dies on strict mode — a race this assertion lost roughly one run
  // in three, independent of any change to the hierarchy.
  await expect(dialog).toHaveCount(0);
  const toasts = page.getByRole('region', { name: 'Notifications' });
  await expect(toasts).toContainText('Widget saved');
  await expect(toasts).toContainText(title);

  // The layer vocabulary is real on the dashboard that renders it — the
  // built-in widgets carry their own layer badge, so the badge this
  // widget will get once §16 Q15 lands is not an invention either.
  await page.goto(`/solutions/${SOLUTION}/instances/${INSTANCE}/monitoring`);
  await expect(page.getByText('package').first()).toBeVisible();

  expect(errors).toEqual([]);
});

// ───────────────────────────── Journey 6 ─────────────────────────────────

/**
 * ⚠️ **This journey is narrower than `FE-099`'s line for it, and
 * deliberately so.**
 *
 * The task asks for "with the verb absent, the control is omitted; with a
 * capability limit, it is disabled with a reason". The second half is
 * real and is asserted below. **The first half describes behaviour this
 * console does not have, on purpose**: `overlayRbac` (task 059, `FE-D22`)
 * is a documented no-op, because the only `grantable` verbs today are
 * `target.use` and `instance.bindTarget`, and `permissions.ts`'s C12 rule
 * forbids gating a v1 feature on a verb the catalog cannot grant — doing
 * so would deny every principal, since no identity can hold it.
 *
 * So rather than invent a gate to have something to assert, this journey
 * pins C12 itself: a session holding **no** verbs must still reach every
 * v1 control. That is the regression this codebase can actually suffer,
 * and it fails loudly the day someone gates a v1 screen on a
 * non-grantable verb.
 */
test('journey 6 · no grantable verb may gate a v1 control; limits disable with a reason', async ({
  page,
}) => {
  const errors = watchConsole(page);

  // A session with no permissions and no super-admin (`?e2e_grants=none`,
  // DEV only). Under C12 this must change nothing.
  await page.goto(`/solutions/${SOLUTION}/instances?e2e_grants=none`);
  await expect(page.getByRole('link', { name: 'cache-prod-01' })).toBeVisible();

  // MUST-NOT: a v1 control must not disappear for a verbless principal.
  // If one does, it was gated on a verb no identity can hold, and every
  // real operator is locked out of it too.
  await expect(page.getByRole('button', { name: 'Declare instance' })).toBeVisible();

  await page.goto(`/admin/roles?e2e_grants=none`);
  await expect(page.getByRole('button', { name: /New custom role/i })).toBeVisible();

  // The other half: a control that is unavailable is DISABLED WITH A
  // REASON, never silently missing. Explore's steps are gated on the step
  // before them and say so in words.
  await page.goto(`/solutions/${SOLUTION}/instances/${INSTANCE}/monitoring/explore`);
  const instanceStep = page.getByRole('button', { name: 'Instance', exact: true });
  await expect(instanceStep).toBeDisabled();
  // The same explanation is repeated on each gated step below the first;
  // one visible instance is what this asserts.
  await expect(page.getByText('Pick a metric first.').first()).toBeVisible();

  // MUST-NOT: the unavailable control is present and explained, not
  // omitted. An operator who cannot see the control cannot learn what
  // would unlock it.
  await expect(instanceStep).toHaveCount(1);

  expect(errors).toEqual([]);
});
