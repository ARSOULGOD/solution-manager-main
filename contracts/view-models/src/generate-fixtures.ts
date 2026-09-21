/**
 * Generate `fixtures/viewmodel/*.json` from `cases.ts` by running the engine.
 *
 * The generated `expected` block is the Java implementation's target. Review the diff when
 * regenerating: an unreviewed change here silently moves the goalposts for the other
 * implementation, which is the divergence the suite exists to prevent.
 *
 *   npm run fixtures:gen
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { CASES } from './cases.js';
import { project } from './project.js';
import { canonical, fixturePath, loadPackage, VM_DIR } from './fixtures.js';

mkdirSync(VM_DIR, { recursive: true });
for (const c of CASES) {
  const pkg = loadPackage(c.pkg);
  const expected = project(pkg, c.selection, c.draft, c.observed, c.catalog ?? {});
  const { pkg: _pkg, ...rest } = c;
  writeFileSync(fixturePath(c.name), canonical({ ...rest, package: c.pkg, expected }));
  const vis = expected.components.flatMap((k) => k.sections.flatMap((s) => s.fields.filter((f) => f.visible)));
  console.log(
    `  ${c.name.padEnd(48)} visible=${String(vis.length).padStart(3)} ` +
    `rendered=${String(expected.rendered.length).padStart(3)} ` +
    `excluded=${String(expected.excluded.length).padStart(3)} ` +
    `health=${expected.status.healthRollup}`,
  );
}
console.log(`\n  ${CASES.length} fixtures written to fixtures/viewmodel/`);
