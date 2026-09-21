/**
 * Task 132's visual proof (plan `126`, "Visual proof — the point of the
 * task, so it is not optional"). Joins the `fixture` project for the same
 * reason `designer.spec.ts` and `reflow.spec.ts` do: the MSW-backed dev
 * server is the only place a populated dashboard exists.
 *
 * These are **assertions about the rendered page**, not screenshot capture.
 * The first one is the exact check that failed before task 126: the host
 * `<div>` carried `_echarts_instance_=""` with no `<canvas>` child at all,
 * because StrictMode's double-invoked effect disposed the instance it had
 * just published. Screenshots are written alongside as artefacts for a human
 * to compare against `reference/Solution Manager v2.html`.
 *
 * **The chart host is located by `[_echarts_instance_]`, not by
 * `role="application"`.** `ChartRenderer` sets `role="application"` and an
 * `aria-label` on that `<div>`, but ECharts' own `aria: { enabled: true }`
 * (design §18's last row) OVERWRITES both at runtime — the shipped element
 * is `role="img"` with ECharts' generated data description as its name.
 * `tabIndex={0}` and `aria-describedby` do survive, so the keyboard focus
 * stop and the hint are intact. Found by this spec, recorded in task 132's
 * report; the divergence between §18's stated contract and what ships is a
 * real finding and is NOT silently changed here.
 */
import { expect, test } from '@playwright/test';

const MONITORING = '/solutions/cache/instances/cache-prod-03/monitoring';
const EXPLORE = `${MONITORING}/explore`;
const SHOTS = process.env['MONITORING_SHOT_DIR'] ?? 'test-results/monitoring';

test.use({ viewport: { width: 1440, height: 1000 } });

test('dashboard: charts draw, the reference widgets are all present, and the plot fills the card', async ({
  page,
}) => {
  await page.goto(MONITORING);
  await expect(page).not.toHaveURL(/\/login/);

  // ---- the six reference widgets (task 132's fixture) ----
  await expect(page.getByText('Hit rate', { exact: true })).toBeVisible();
  await expect(page.getByText('Evictions / min')).toBeVisible();
  await expect(page.getByText('Entries', { exact: true })).toBeVisible();
  await expect(page.getByText('Hit rate (now)')).toBeVisible();
  await expect(page.getByText('Miss count')).toBeVisible();
  // Hidden default (task 129's dashed placeholder) and the too-new widget.
  await expect(page.getByText(/Hidden default · p99 GET latency/)).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Read-only' })).toBeVisible();

  // ---- the sub-tab bar (task 130) ----
  await expect(page.getByRole('tab', { name: 'Dashboards' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await expect(page.getByRole('tab', { name: 'Explore' })).toBeVisible();

  // ---- task 131's explicit action ----
  await expect(page.getByRole('button', { name: 'Add widget' })).toBeVisible();

  // ---- THE check that failed before task 126 ----
  const host = page.locator('[_echarts_instance_]').first();
  await expect(host).toBeVisible();
  // The instance id is non-empty: before task 126 the attribute was present
  // and EMPTY, the signature of an instance created and then disposed.
  await expect(host).not.toHaveAttribute('_echarts_instance_', '');
  // The keyboard focus stop survives ECharts' own aria pass (see header).
  await expect(host).toHaveAttribute('tabindex', '0');

  const canvas = host.locator('canvas').first();
  await expect(canvas).toBeAttached();
  const canvasBox = await canvas.boundingBox();
  expect(canvasBox, 'the ECharts canvas must exist and have a real size').not.toBeNull();
  expect(canvasBox?.width ?? 0).toBeGreaterThan(0);
  expect(canvasBox?.height ?? 0).toBeGreaterThan(0);

  // ---- task 128/129: the plot fills its card, no dead space ----
  // An `h: 2` widget's cell is 314px; the plot must occupy most of the card
  // rather than the ~100px a flat 220px body used to leave.
  const card = page.locator('[data-fill]').filter({ has: host }).first();
  const cardBox = await card.boundingBox();
  expect(cardBox).not.toBeNull();
  const ratio = (canvasBox?.height ?? 0) / (cardBox?.height ?? 1);
  expect(
    ratio,
    `plot height ${String(canvasBox?.height)} of card ${String(cardBox?.height)}`,
  ).toBeGreaterThan(0.6);

  await page.screenshot({ path: `${SHOTS}/dashboard-1440.png`, fullPage: true });
});

test('explore: the chart draws and the tab bar marks Explore — and only Explore — active', async ({
  page,
}) => {
  await page.goto(EXPLORE);
  await expect(page).not.toHaveURL(/\/login/);

  // The `end` prop's whole reason (task 130): `/monitoring` is a prefix of
  // this route, so without it both tabs read as active.
  await expect(page.getByRole('tab', { name: 'Explore' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await expect(page.getByRole('tab', { name: 'Dashboards' })).toHaveAttribute(
    'aria-selected',
    'false',
  );

  await page.screenshot({ path: `${SHOTS}/explore-1440.png`, fullPage: true });
});

test('the save flow lands: Explore → Save as widget → the widget is on the dashboard', async ({
  page,
}) => {
  await page.goto(MONITORING);
  await page.getByRole('button', { name: 'Add widget' }).click();
  await expect(page.getByRole('tab', { name: 'Explore' })).toHaveAttribute(
    'aria-selected',
    'true',
  );

  await page.getByRole('radio', { name: 'cache.hit_rate' }).click();
  const instances = page.getByRole('checkbox');
  await instances.nth(0).click();
  await instances.nth(1).click();
  await instances.nth(2).click();

  await page.getByRole('button', { name: 'Save as widget' }).click();
  const title = `Journey widget ${String(Date.now())}`;
  await page.getByLabel(/^Widget title/).fill(title);
  await page.getByRole('button', { name: 'Save widget' }).click();

  // Task 132 — the operator is taken to the board, and the widget is on it.
  // Before this, the fixture echoed the POST and discarded it, so a saved
  // widget never appeared however long you waited.
  await expect(page.getByRole('tab', { name: 'Dashboards' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  // Scoped to a widget CARD — the success toast carries the same title, so
  // an unscoped text match would pass without the widget ever landing,
  // which is exactly the failure this test exists to catch.
  await expect(page.locator('[data-fill]').filter({ hasText: title })).toBeVisible();

  await page.screenshot({ path: `${SHOTS}/dashboard-after-save-1440.png`, fullPage: true });

  // ---- Reset defaults restores the package layer, never an empty board ----
  await page.getByRole('button', { name: 'Reset defaults' }).click();
  await expect(page.locator('[data-fill]').filter({ hasText: title })).toHaveCount(0);
  await expect(page.getByText('Hit rate', { exact: true })).toBeVisible();
  await expect(page.getByText(/Hidden default · p99 GET latency/)).toBeVisible();
});

/**
 * Plan `126`'s step 4, and a second job: switching mode changes the MUI
 * theme, which changes `ChartRenderer`'s memoized factory identity, which
 * makes `useEcharts` dispose the old instance and create a new one. That is
 * exactly the path task 126 rewrote, so a chart that survives a theme toggle
 * is direct evidence the serialisation did not break recreation.
 */
test('dark mode: the chart is recreated, not lost, and redraws in the dark theme', async ({
  page,
}) => {
  await page.goto(MONITORING);
  const host = page.locator('[_echarts_instance_]').first();
  await expect(host.locator('canvas')).toBeAttached();
  const lightInstance = await host.getAttribute('_echarts_instance_');

  await page.getByRole('button', { name: /dark/i }).first().click();

  // A NEW instance (ECharts themes are init-time-only, so the old one must
  // be disposed and replaced) — and it still has a canvas of real size.
  await expect
    .poll(async () => page.locator('[_echarts_instance_]').first().getAttribute('_echarts_instance_'))
    .not.toBe(lightInstance);

  const darkHost = page.locator('[_echarts_instance_]').first();
  const darkCanvas = darkHost.locator('canvas').first();
  await expect(darkCanvas).toBeAttached();
  const box = await darkCanvas.boundingBox();
  expect(box?.height ?? 0).toBeGreaterThan(0);

  await page.screenshot({ path: `${SHOTS}/dashboard-dark-1440.png`, fullPage: true });
});
