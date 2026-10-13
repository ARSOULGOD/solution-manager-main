/**
 * Paste-friendly excerpts of the view-model fixtures, for feeding a design tool that has no
 * repo access. Full fixtures are 25–92 KB; these keep the exact SHAPE and one field of each
 * interesting kind, so a designer sees every case they must render without 100 near-identical
 * entries. The full fixtures remain the contract — these are for conversation, not for tests.
 *
 *   npm run excerpts:gen
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { VM_DIR, canonical } from './fixtures.js';
import type { FieldView, ViewModel } from './types.js';

const OUT = join(VM_DIR, 'excerpts');
mkdirSync(OUT, { recursive: true });

/** One field per "interesting kind" so every control and state appears exactly once. */
function pick(fields: readonly FieldView[]): FieldView[] {
  const want: ((f: FieldView) => boolean)[] = [
    (f) => f.discriminator && f.controlKind === 'select',
    (f) => f.discriminator && f.controlKind === 'switch',
    (f) => f.masked,
    (f) => f.virtual,
    (f) => !f.visible,
    (f) => f.advanced,
    (f) => f.validations.some((v) => v.severity === 'block'),
    (f) => f.validations.some((v) => v.severity === 'warn'),
    (f) => f.controlKind === 'duration',
    (f) => f.controlKind === 'key-value',
    (f) => f.controlKind === 'number',
    (f) => !f.enabled,
    (f) => f.value.kind === 'unset',
    (f) => f.controlKind === 'text' && f.visible,
  ];
  const out: FieldView[] = [];
  for (const w of want) {
    const hit = fields.find((f) => w(f) && !out.includes(f));
    if (hit) out.push(hit);
  }
  return out;
}

const CASES = [
  ['designer-cache', 'predicate-and-of-two', 'Cache Designer — a working draft with the broker branch open'],
  ['designer-cache-blocked', 'version-drift-blocks-editing', 'Cache Designer under VERSION_DRIFT — every control disabled, reason stated'],
  ['designer-cache-validation', 'bind-blocks-semantic-warns', 'Cache Designer with a bind-tier block and a semantic-tier warning'],
  ['designer-filemesh', 'fm-virtual-visible-never-rendered', 'File Mesh Designer — two composed components, 103 fields, 27 virtual'],
  ['governance-standby', 'health-declared-inactive-replica-is-not-red', 'Governance — two standby replicas: INACTIVE_REPLICA, not red'],
  ['governance-not-declared', 'fm-health-not-declared-is-not-applicable', 'Governance — no health block declared: NOT_APPLICABLE, never red'],
] as const;

for (const [name, source, note] of CASES) {
  const full = JSON.parse(readFileSync(join(VM_DIR, `${source}.json`), 'utf8')) as { expected: ViewModel };
  const vm = full.expected;
  const excerpt = {
    _note: note,
    _source: `contracts/fixtures/viewmodel/${source}.json — this is an EXCERPT; the full fixture is the contract`,
    engineVersion: vm.engineVersion,
    solution: vm.solution,
    packageVersion: vm.packageVersion,
    components: vm.components.map((c) => ({
      instanceKey: c.instanceKey,
      type: c.type,
      role: c.role,
      _sectionCount: c.sections.length,
      _sectionTitlesInOrder: c.sections.map((s) => s.title),
      sections: c.sections.slice(0, 3).map((s) => ({
        title: s.title,
        order: s.order,
        allVisibleFieldsAdvanced: s.allVisibleFieldsAdvanced,
        _fieldCount: s.fields.length,
        fields: pick(s.fields).slice(0, 6),
        actions: s.actions,
      })),
    })),
    _renderedCount: vm.rendered.length,
    rendered: vm.rendered.slice(0, 5),
    _excludedCount: vm.excluded.length,
    excluded: vm.excluded.slice(0, 5),
    metrics: vm.metrics,
    status: vm.status,
    diagnostics: vm.diagnostics.slice(0, 3),
  };
  const path = join(OUT, `${name}.json`);
  writeFileSync(path, canonical(excerpt));
  const kb = (readFileSync(path).length / 1024).toFixed(1);
  console.log(`  ${name.padEnd(28)} ${kb.padStart(5)} KB   ${note}`);
}
console.log(`\n  ${CASES.length} excerpts written to fixtures/viewmodel/excerpts/`);
