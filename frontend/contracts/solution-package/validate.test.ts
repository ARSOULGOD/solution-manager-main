/// <reference types="node" />
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import DB_DESIGNER from '../packages/db-designer/package.json';

import { toFrontendPackage } from './normalize';
import { parseAndValidateSolutionPackage, validateSolutionPackage } from './validate';

const here = dirname(fileURLToPath(import.meta.url));
const rootNegatives = resolve(here, '../../../contracts/validation/negative');

describe('canonical Solution Package upload validation', () => {
  it('accepts the authoring example and produces a useful confirmation summary', () => {
    const result = validateSolutionPackage(DB_DESIGNER, { devMode: false });

    expect(result.valid).toBe(true);
    if (!result.valid) return;
    expect(result.summary).toMatchObject({
      solution: 'db-designer',
      packageVersion: [1],
      componentCount: 1,
      fieldCount: 9,
      actionCount: 1,
      metricCount: 2,
    });
    expect(result.summary.components[0]).toMatchObject({ type: 'db-connector', fieldCount: 9 });
  });

  it('reports every structural problem with a JSON path instead of stopping at the first', () => {
    const result = validateSolutionPackage(
      { solution: 'Bad id', packageVersion: [], appliesTo: 7, components: [] },
      { devMode: false },
    );

    expect(result.valid).toBe(false);
    if (result.valid) return;
    expect(result.issues.length).toBeGreaterThanOrEqual(4);
    expect(result.issues.map((entry) => entry.path)).toEqual(
      expect.arrayContaining(['/solution', '/packageVersion', '/appliesTo', '/components']),
    );
  });

  it('rejects invalid field type and misplaced UI metadata at their exact field paths', () => {
    const value = structuredClone(DB_DESIGNER) as Record<string, unknown>;
    const components = value['components'] as Record<string, unknown>[];
    const fields = components[0]?.['fields'] as Record<string, unknown>[];
    fields[0]!['type'] = 'dropdown';
    fields[0]!['label'] = 'Misplaced';

    const result = validateSolutionPackage(value, { devMode: false });

    expect(result.valid).toBe(false);
    if (result.valid) return;
    expect(result.issues.map((entry) => entry.path)).toEqual(
      expect.arrayContaining(['/components/0/fields/0/type', '/components/0/fields/0/label']),
    );
  });

  it('rejects a package with no component', () => {
    const result = validateSolutionPackage({ ...DB_DESIGNER, components: [] }, { devMode: false });

    expect(result.valid).toBe(false);
    if (result.valid) return;
    expect(result.issues).toEqual(
      expect.arrayContaining([expect.objectContaining({ gate: 1, path: '/components' })]),
    );
  });

  it('rejects every committed semantic negative fixture', () => {
    const files = readdirSync(rootNegatives).filter((name) => name.endsWith('.json'));
    expect(files.length).toBeGreaterThan(0);

    for (const file of files) {
      const value: unknown = JSON.parse(readFileSync(join(rootNegatives, file), 'utf8'));
      expect(validateSolutionPackage(value, { devMode: false }), file).toMatchObject({
        valid: false,
      });
    }
  });

  it('rejects an overlapping runtime range for the same solution only', () => {
    const result = validateSolutionPackage(DB_DESIGNER, {
      devMode: false,
      existingPackages: [
        { solution: 'db-designer', appliesTo: '>=1.5 <3.0' },
        { solution: 'another-solution', appliesTo: '>=1.0 <2.0' },
      ],
    });

    expect(result).toMatchObject({
      valid: false,
      issues: [
        expect.objectContaining({ gate: 3, code: 'APPLIES_TO_OVERLAP', path: '/appliesTo' }),
      ],
    });
  });

  it('reports malformed JSON as a root issue', () => {
    expect(parseAndValidateSolutionPackage('{', { devMode: false })).toMatchObject({
      valid: false,
      issues: [expect.objectContaining({ code: 'MALFORMED_JSON', path: '/' })],
    });
  });

  it('normalizes canonical UI metadata at the single projection boundary', () => {
    const result = validateSolutionPackage(DB_DESIGNER, { devMode: false });
    if (!result.valid) throw new Error('authoring example must validate');

    const normalized = toFrontendPackage(result.package);

    expect(normalized.packageVersion).toEqual([1]);
    expect(normalized.components[0]?.version).toEqual([1]);
    expect(normalized.components[0]?.fields[0]).toMatchObject({
      version: [1],
      label: 'Database vendor',
      helperText: 'Selects which vendor-specific fields below apply.',
      ui: { section: 'Connection', order: 0 },
    });
  });

  it('rejects component and field versions that the package does not declare', () => {
    const value = structuredClone(DB_DESIGNER);
    value.components[0]!.version = [2];
    value.components[0]!.fields[0]!.version = [3];

    const result = validateSolutionPackage(value, { devMode: false });

    expect(result.valid).toBe(false);
    if (result.valid) return;
    expect(result.issues).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ code: 'COMPONENT_VERSION_NOT_DECLARED' }),
        expect.objectContaining({ code: 'FIELD_VERSION_NOT_DECLARED' }),
      ]),
    );
  });

  it('rejects a conditional reference that disappears in an owner version', () => {
    const value = structuredClone(DB_DESIGNER);
    value.packageVersion = [1, 2];
    value.components[0]!.version = [1, 2];
    value.components[0]!.fields[0]!.version = [1];
    value.components[0]!.fields[4]!.version = [1, 2];

    const result = validateSolutionPackage(value, { devMode: false });

    expect(result.valid).toBe(false);
    if (result.valid) return;
    expect(result.issues).toEqual(
      expect.arrayContaining([expect.objectContaining({ code: 'WHEN_FIELD_MISSING_IN_VERSION' })]),
    );
  });
});
