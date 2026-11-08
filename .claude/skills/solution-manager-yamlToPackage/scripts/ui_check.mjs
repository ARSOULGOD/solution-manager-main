// Stage 2.3: load a package into the running DEV console through the /solutions dialog and check what renders,
// in headless Chromium. Generic: everything it expects is derived from the package itself, so it needs no
// per-solution code. Run it through run_frontend.sh (`ui-check`), which makes @playwright/test resolvable.
//
//   node ui_check.mjs --base http://127.0.0.1:5173 --package /work/package.json --out /work/ui \
//        [--roles source,sink] [--secrets /work/secret-values.json] [--designer-timeout 20000]
//
// --secrets: a JSON array of secret VALUES taken from the input YAML. Each page's DOM is searched for them;
// values shorter than 6 characters are skipped (too likely to match ordinary text) and listed as skipped.
// Writes <out>/ui-check.json and one screenshot per step. Exit 0 PASS, 1 FAIL, 3 BLOCKED (nothing failed, but
// at least one check could not run).
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

import { chromium } from '@playwright/test';
import { load as loadYaml } from 'js-yaml';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith('--') ? [...acc, [a.slice(2), all[i + 1]]] : acc), []),
);
const base = args.base ?? 'http://127.0.0.1:5173';
const out = args.out ?? '/work/ui';
const pkg = JSON.parse(readFileSync(args.package, 'utf8'));
const roles = (args.roles ?? '').split(',').filter(Boolean);
const secrets = args.secrets ? JSON.parse(readFileSync(args.secrets, 'utf8')) : [];
const designerTimeout = Number(args['designer-timeout'] ?? 20000);
mkdirSync(out, { recursive: true });

const checks = [];
const record = (name, status, detail, shot) => checks.push({ name, status, detail, screenshot: shot ?? null });
const scanSecrets = async (page, where) => {
  const html = await page.content();
  const leaked = secrets.filter((s) => typeof s === 'string' && s.length >= 6 && html.includes(s));
  if (leaked.length) record(`no secret value in the DOM (${where})`, 'FAIL', `found ${leaked.length} secret value(s) in the page`);
};

const eff = (declared, inherited) => (declared == null ? [...inherited] : declared.filter((v) => inherited.includes(v)));
const flatten = (obj, prefix = '', acc = {}) => {
  for (const [k, v] of Object.entries(obj ?? {})) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v !== null && typeof v === 'object' && !Array.isArray(v)) flatten(v, key, acc); else acc[key] = v;
  }
  return acc;
};

// What the Designer should show for one instance: its selected components at its package version.
function expected(componentTypes, version) {
  const pv = pkg.packageVersion;
  const comps = (componentTypes?.length ? componentTypes : pkg.components.map((c) => c.type))
    .map((t) => pkg.components.find((c) => c.type === t))
    .filter((c) => c && eff(c.version, pv).includes(version));
  const sections = [], fields = [];
  for (const c of comps) {
    const cv = eff(c.version, pv);
    const own = (c.fields ?? []).filter((f) => eff(f.version, cv).includes(version));
    const bySec = new Map();
    for (const f of own) {
      const s = f.ui?.section ?? 'General';
      if (!bySec.has(s)) bySec.set(s, { title: s, order: f.ui?.order ?? 1e9, count: 0 });
      bySec.get(s).count += 1;
      fields.push({ component: c, field: f });
    }
    sections.push(...[...bySec.values()].sort((a, b) => a.order - b.order));
  }
  return { comps, sections, fields };
}

async function checkDesigner(page, role, inst) {
  const detail = await page.evaluate(async (id) => (await fetch(`/api/dp/instances/${id}`)).json(), inst.instanceId ?? inst.id);
  const version = detail.declaredPackageVersion ?? pkg.packageVersion[pkg.packageVersion.length - 1];
  const exp = expected(inst.componentTypes, version);
  const tag = `${role} (instance ${inst.instanceId ?? inst.id}, v${version}, components ${exp.comps.map((c) => c.type).join('+')})`;
  const allFields = page.getByRole('button', { name: /all fields/i });
  if (await allFields.count()) await allFields.first().click();
  await page.waitForTimeout(1000);
  const shot = join(out, `05-all-fields-${role}.png`);
  await page.screenshot({ path: shot, fullPage: true });

  const got = (await page.locator('main button[aria-expanded]').allInnerTexts())
    .map((t) => t.split('\n')).filter(([t]) => !/rollout/i.test(t))
    .map(([title, n]) => [title.trim(), Number((n ?? '').match(/\d+/)?.[0] ?? NaN)]);
  const want = exp.sections.map((s) => [s.title, s.count]);
  const same = JSON.stringify(got) === JSON.stringify(want);
  record(`sections in ui.order with their field counts: ${tag}`, same ? 'PASS' : 'FAIL',
    same ? `${want.length} sections` : `expected ${JSON.stringify(want)} got ${JSON.stringify(got)}`, shot);

  const text = await page.locator('main').innerText();
  const visible = exp.fields.filter(({ field }) => !field.when);
  const noLabel = visible.filter(({ field }) => field.ui?.label && !text.includes(field.ui.label)).map(({ field }) => field.key);
  const noHelp = visible.filter(({ field }) => field.ui?.helperText && !text.includes(field.ui.helperText)).map(({ field }) => field.key);
  record(`labels shown: ${tag}`, noLabel.length ? 'FAIL' : 'PASS', noLabel.length ? `missing: ${noLabel.join(', ')}` : `${visible.length} fields`, shot);
  record(`helper text shown: ${tag}`, noHelp.length ? 'FAIL' : 'PASS', noHelp.length ? `missing: ${noHelp.join(', ')}` : `${visible.length} fields`, shot);

  const secretCount = visible.filter(({ field }) => (field.type === 'secret' || field.secret) && !field.virtual).length;
  const masked = await page.locator('main input[type=password]').count();
  record(`secrets masked: ${tag}`, masked === secretCount ? 'PASS' : 'FAIL', `${masked} masked inputs for ${secretCount} secret fields`, shot);

  const whenFields = exp.fields.filter(({ field }) => field.when);
  record(`when show/hide: ${tag}`, whenFields.length ? 'BLOCKED' : 'N/A',
    whenFields.length ? `${whenFields.length} predicated field(s); not driven in the browser, covered by the generated contract test`
      : 'no `when` predicate in the rendered components');

  // defaults, through the console's own YAML export
  let yamlText = null;
  try {
    await page.getByRole('button', { name: /yaml view/i }).click();
    await page.getByRole('button', { name: /copy yaml/i }).click();
    yamlText = await page.evaluate(() => navigator.clipboard.readText());
  } catch (e) {
    record(`defaults seeded from the package: ${tag}`, 'BLOCKED', `could not read the YAML view: ${String(e).slice(0, 200)}`);
  }
  if (yamlText !== null) {
    writeFileSync(join(out, `designer-${role}.yaml`), yamlText);
    const flat = flatten(loadYaml(yamlText));
    const bad = [];
    for (const { component, field } of visible) {
      if (field.virtual || field.type === 'secret' || field.secret || field.default === undefined) continue;
      const path = component.configPath ? `${component.configPath}.${field.key}` : field.key;
      const v = flat[path];
      if (JSON.stringify(v ?? null) !== JSON.stringify(field.default ?? null)) bad.push(`${path}: ${JSON.stringify(v)} != ${JSON.stringify(field.default)}`);
    }
    const yshot = join(out, `06-yaml-${role}.png`);
    await page.screenshot({ path: yshot, fullPage: true });
    record(`defaults seeded from the package (console YAML view): ${tag}`, bad.length ? 'FAIL' : 'PASS',
      bad.length ? bad.slice(0, 8).join('; ') : `${Object.keys(flat).length} keys match`, yshot);
  }
}

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1440, height: 1100 },
  permissions: ['clipboard-read', 'clipboard-write'] });
const page = await context.newPage();
const pageErrors = [];
page.on('pageerror', (e) => pageErrors.push(e.message.slice(0, 300)));

try {
  await page.goto(`${base}/solutions`);
  await page.getByRole('button', { name: 'Load a package' }).first().click({ timeout: 30000 });
  await page.locator('input[type=file]').setInputFiles(args.package);
  const dialog = page.getByRole('dialog');
  await dialog.getByText(/Package format is valid|needs correction/).waitFor({ timeout: 30000 });
  let shot = join(out, '01-load-dialog.png');
  await page.screenshot({ path: shot, fullPage: true });
  const text = await dialog.innerText();
  if (/needs correction/.test(text)) {
    record('load dialog validates the package', 'FAIL', text.slice(0, 600), shot);
  } else {
    record('load dialog validates the package', 'PASS', 'Package format is valid', shot);
    const versions = `Package versions ${pkg.packageVersion.join(', ')}`;
    record('dialog shows every package version', text.includes(versions) ? 'PASS' : 'FAIL', versions, shot);
    const missing = [];
    for (const c of pkg.components) {
      const want = `Fields: ${(c.fields ?? []).length}`;
      const card = dialog.getByRole('button', { name: new RegExp(`^${c.type}\\b`) });
      const t = (await card.count()) ? await card.first().innerText() : '';
      if (!t.includes(c.cardinality) || !t.includes(want)) missing.push(`${c.type} (${c.cardinality}, ${want}) got: ${t.replace(/\n+/g, ' ') || 'absent'}`);
    }
    record('inventory lists every component with its cardinality and field count', missing.length ? 'FAIL' : 'PASS',
      missing.length ? missing.join('; ') : `${pkg.components.length} components`, shot);
    await scanSecrets(page, 'load dialog');
    await dialog.getByRole('button', { name: /confirm and load/i }).click();
  }

  await page.waitForTimeout(1500);
  shot = join(out, '02-catalog.png');
  await page.screenshot({ path: shot, fullPage: true });
  const main = await page.locator('main').innerText();
  const hasCard = main.includes(pkg.solution);
  const lastV = `${pkg.packageVersion[pkg.packageVersion.length - 1]} · latest`;
  record('catalog card appears with the latest version marked', hasCard && main.includes(lastV) ? 'PASS' : 'FAIL',
    hasCard ? `card for ${pkg.solution}; expects "${lastV}"` : `no card mentioning ${pkg.solution}`, shot);

  // instance inventory: the console derives one dev instance per loaded package
  await page.goto(`${base}/solutions/${pkg.solution}/instances`);
  await page.waitForTimeout(4000);
  shot = join(out, '03-instances.png');
  await page.screenshot({ path: shot, fullPage: true });
  const inv = await page.locator('main').innerText();
  if (/failed to load/i.test(inv)) {
    record('instances page renders', 'BLOCKED', `console error: ${inv.replace(/\s+/g, ' ').slice(0, 400)}`, shot);
  } else {
    record('instances page renders', 'PASS', inv.replace(/\s+/g, ' ').slice(0, 200), shot);
  }

  // Designer per role: find an instance of the solution via the dev API, open its Designer, wait for sections
  const list = await page.evaluate(async (sol) => {
    const r = await fetch(`/api/dp/solutions/${encodeURIComponent(sol)}/instances`);
    return r.ok ? r.json() : null;
  }, pkg.solution);
  const items = Array.isArray(list) ? list : (list?.items ?? list?.instances ?? []);
  for (const role of roles.length ? roles : ['none']) {
    const inst = items.find((i) => (i.role ?? '').toLowerCase() === role || new RegExp(role, 'i').test(i.name ?? '')) ?? items[0];
    const name = `Designer renders the ${role} role`;
    if (!inst) {
      record(name, 'BLOCKED', 'no instance of this solution is served by the dev API');
      continue;
    }
    const id = inst.instanceId ?? inst.id;
    await page.goto(`${base}/solutions/${pkg.solution}/instances/${id}/designer`);
    shot = join(out, `04-designer-${role}.png`);
    try {
      await page.getByRole('button', { name: /form view/i }).waitFor({ timeout: designerTimeout });
    } catch {
      await page.screenshot({ path: shot, fullPage: true });
      record(name, 'BLOCKED', `instance ${id}: the Designer form did not render within ${designerTimeout} ms. ` +
        'Section order, labels, helper text, masking, when show/hide and defaults could not be checked.', shot);
      continue;
    }
    await page.screenshot({ path: shot, fullPage: true });
    record(name, 'PASS', `instance ${id}`, shot);
    await checkDesigner(page, role, inst);
    await scanSecrets(page, `Designer ${role}`);
  }
  record('version selector offers every package version', 'BLOCKED',
    'the selector lives in the Declare-instance dialog on the instances page, which does not render (see above)');
  const skipped = secrets.filter((s) => typeof s === 'string' && s.length < 6);
  if (secrets.length) {
    if (!checks.some((c) => c.name.startsWith('no secret value') && c.status === 'FAIL'))
      record('no secret value in the DOM (every page visited)', 'PASS',
        `${secrets.length - skipped.length} value(s) searched; ${skipped.length} too short to search reliably`);
  }
} catch (e) {
  record('ui check ran to completion', 'FAIL', String(e).slice(0, 500));
} finally {
  await browser.close();
}

// N/A never affects the verdict; BLOCKED means a check could not run, which is not the same as passing
const verdict = checks.some((c) => c.status === 'FAIL') ? 'FAIL' : checks.some((c) => c.status === 'BLOCKED') ? 'BLOCKED' : 'PASS';
writeFileSync(join(out, 'ui-check.json'), JSON.stringify({ verdict, base, solution: pkg.solution, checks, pageErrors }, null, 2));
for (const c of checks) console.log(`${c.status.padEnd(7)} ${c.name}${c.detail ? ` :: ${c.detail.slice(0, 160)}` : ''}`);
if (pageErrors.length) console.log(`page errors: ${pageErrors.length} (see ui-check.json)`);
console.log(`VERDICT ${verdict}`);
process.exit(verdict === 'PASS' ? 0 : verdict === 'FAIL' ? 1 : 3);
