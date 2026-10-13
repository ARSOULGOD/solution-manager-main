/**
 * Compact, REAL view-model samples small enough to inline directly into a design prompt.
 *
 * The excerpts in `fixtures/viewmodel/excerpts/` are 9–25 KB — still a file to find and paste.
 * These are ~100 lines: every distinct control, state and validation appears exactly once,
 * generated from the engine so they are accurate rather than hand-written.
 *
 *   npm run inline:gen
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { VM_DIR } from './fixtures.js';
import type { FieldView, ViewModel } from './types.js';

const OUT = join(VM_DIR, 'inline');
mkdirSync(OUT, { recursive: true });

const load = (n: string): ViewModel =>
  (JSON.parse(readFileSync(join(VM_DIR, `${n}.json`), 'utf8')) as { expected: ViewModel }).expected;

const allFields = (vm: ViewModel): FieldView[] =>
  vm.components.flatMap((c) => c.sections.flatMap((s) => s.fields));

/** Trim a field to what a designer actually renders. */
function slim(f: FieldView): Record<string, unknown> {
  const o: Record<string, unknown> = {
    key: f.key, renderedPath: f.renderedPath, controlKind: f.controlKind, value: f.value,
  };
  if (f.options) o['options'] = f.options;
  o['visible'] = f.visible;
  o['enabled'] = f.enabled;
  if (f.virtual) o['virtual'] = true;
  if (f.advanced) o['advanced'] = true;
  if (f.masked) o['masked'] = true;
  if (f.discriminator) o['discriminator'] = true;
  if (f.label) o['label'] = f.label;
  if (f.helperText) o['helperText'] = f.helperText.length > 64 ? `${f.helperText.slice(0, 61)}…` : f.helperText;
  if (f.validations.length) o['validations'] = f.validations;
  if (f.hiddenBy.length) o['hiddenBy'] = f.hiddenBy;
  return o;
}

/**
 * Pretty above, compact below: any value whose JSON fits in `LINE` characters goes on one
 * line, everything larger recurses. Pretty-printing a field's nested `value` union costs
 * ~16 lines each and buries the shape a designer is scanning; collapsing every object
 * instead put a whole component on one unreadable line. A width threshold gives both.
 */
const LINE = 380;

function compact(value: unknown, indent = 0): string {
  const flat = JSON.stringify(value);
  if (flat === undefined) return 'null';
  if (flat.length <= LINE || value === null || typeof value !== 'object') return flat;
  const pad = '  '.repeat(indent);
  if (Array.isArray(value)) {
    // an array of scalars never expands, however long its parent is
    if (value.every((v) => v === null || typeof v !== 'object')) return flat;
    return `[\n${value.map((v) => `${pad}  ${compact(v, indent + 1)}`).join(',\n')}\n${pad}]`;
  }
  const body = Object.entries(value as Record<string, unknown>)
    .map(([k, v]) => `${pad}  ${JSON.stringify(k)}: ${compact(v, indent + 1)}`)
    .join(',\n');
  return `{\n${body}\n${pad}}`;
}

function fence(label: string, value: unknown): string {
  return `${label}\n\`\`\`jsonc\n${compact(value)}\n\`\`\`\n`;
}

// ---------------------------------------------------------------- P5: Cache Designer
{
  const vm = load('predicate-and-of-two');
  const f = allFields(vm);
  const pick = (k: string): FieldView | undefined => f.find((x) => x.key === k);
  const keys = [
    'mode', 'provider', 'namespace', 'key-source', 'topic-key-levels', 'max-size', 'ttl',
    'expiry-header-key-pointer', 'cache-hit-response.template-mappings',
    'solace.enabled', 'solace.host', 'solace.password',
    'solace.management.enabled', 'solace.management.management-queue',
  ];
  const chosen = keys.map(pick).filter((x): x is FieldView => x !== undefined);
  const sections = vm.components[0]!.sections;
  const sample = {
    solution: vm.solution,
    packageVersion: vm.packageVersion,
    components: [{
      instanceKey: 'i1',
      type: 'cache-engine',
      role: 'none',
      _sectionsInRenderOrder: sections.map((s) => ({ title: s.title, order: s.order, fieldCount: s.fields.length, allVisibleFieldsAdvanced: s.allVisibleFieldsAdvanced })),
      _note: '14 of 26 fields shown — one per control kind and per state. Real forms reach 206.',
      fields: chosen.map(slim),
      actions: sections.flatMap((s) => s.actions),
    }],
    rendered: vm.rendered,
    excluded: vm.excluded,
    metrics: vm.metrics,
    status: vm.status,
    diagnostics: vm.diagnostics,
  };
  writeFileSync(join(OUT, 'p5-designer-cache.md'), fence('', sample));
}

// ---------------------------------------------------------------- P5b: blocked
{
  const vm = load('version-drift-blocks-editing');
  const f = allFields(vm);
  writeFileSync(join(OUT, 'p5b-blocked.md'), fence('', {
    _note: 'Same view model as P5, with these differences only.',
    status: vm.status,
    _everyFieldNowCarries: { enabled: false },
    _example: slim(f.find((x) => x.key === 'provider')!),
  }));
}

// ---------------------------------------------------------------- P5c: validation
{
  const vm = load('bind-blocks-semantic-warns');
  const warn = load('semantic-tier-warns-never-blocks');
  const blocked = allFields(vm).find((x) => x.validations.some((v) => v.severity === 'block'))!;
  const warned = allFields(warn).find((x) => x.validations.some((v) => v.severity === 'warn'))!;
  writeFileSync(join(OUT, 'p5c-validation.md'), fence('', {
    _note: 'Same view model as P5, with these two fields carrying validations.',
    blockedField: slim(blocked),
    warnedField: slim(warned),
    _renderedExcludesTheBlockedField: vm.rendered.map((r) => r.renderedPath),
  }));
}

// ---------------------------------------------------------------- P6: File Mesh / virtual
{
  const vm = load('fm-virtual-visible-never-rendered');
  const f = allFields(vm);
  const virt = f.filter((x) => x.virtual).slice(0, 3);
  const real = f.filter((x) => !x.virtual && x.visible).slice(0, 3);
  writeFileSync(join(OUT, 'p6-filemesh.md'), fence('', {
    _note: 'Two composed components. 103 fields, 27 virtual, 4 hidden by predicates.',
    components: vm.components.map((c) => ({
      instanceKey: c.instanceKey, type: c.type, role: c.role,
      sectionCount: c.sections.length,
      fieldCount: c.sections.reduce((n, s) => n + s.fields.length, 0),
      sectionTitles: c.sections.map((s) => s.title),
    })),
    _virtualFieldsLookIdenticalToRealOnes: virt.map(slim),
    _ordinaryFieldsForComparison: real.map(slim),
    _renderedCount: vm.rendered.length,
    _excludedByReason: vm.excluded.reduce<Record<string, number>>((a, e) => { a[e.reason] = (a[e.reason] ?? 0) + 1; return a; }, {}),
    status: vm.status,
  }));
}

// ---------------------------------------------------------------- P7: Governance
{
  const standby = load('health-declared-inactive-replica-is-not-red');
  const notDeclared = load('fm-health-not-declared-is-not-applicable');
  const green = load('predicate-and-of-two');
  writeFileSync(join(OUT, 'p7-governance.md'), fence('', {
    _note: 'The status block is the whole contract for this screen. Three real cases.',
    healthy: green.status,
    twoStandbyReplicas: standby.status,
    noHealthBlockDeclared: notDeclared.status,
    _metricCollectionStates: green.metrics,
  }));
}

console.log('  wrote 5 inline samples:');
for (const n of ['p5-designer-cache', 'p5b-blocked', 'p5c-validation', 'p6-filemesh', 'p7-governance']) {
  const lines = readFileSync(join(OUT, `${n}.md`), 'utf8').split('\n').length;
  console.log(`    ${n.padEnd(22)} ${String(lines).padStart(4)} lines`);
}
