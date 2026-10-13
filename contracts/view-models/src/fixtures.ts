/** Fixture I/O shared by the generator and the test, so they cannot disagree. */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import type { SolutionPackage, ViewModel } from './types.js';
import type { Case } from './cases.js';

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
export const VM_DIR = join(ROOT, 'fixtures', 'viewmodel');

/** Package fixtures are shared with the Python gate suite and live under validation/. */
export const PKG_DIR = join(ROOT, '..', 'validation', 'fixtures');

export function loadPackage(name: Case['pkg']): SolutionPackage {
  return JSON.parse(readFileSync(join(PKG_DIR, `${name}.package.json`), 'utf8')) as SolutionPackage;
}

export interface FixtureFile extends Omit<Case, 'pkg'> {
  readonly package: Case['pkg'];
  readonly expected: ViewModel;
}

export function fixturePath(name: string): string {
  return join(VM_DIR, `${name}.json`);
}

/** Canonical JSON: 2-space indent, key order as emitted. Pinned so a byte compare is meaningful. */
export function canonical(value: unknown): string {
  return `${JSON.stringify(value, null, 2)}\n`;
}
