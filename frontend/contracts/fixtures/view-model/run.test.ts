/// <reference types="node" />
/**
 * The shared fixture suite's TypeScript runner (task 046, `UI-D15`). Reads
 * every `*.json` fixture in this directory, calls `project()`, and asserts
 * a structural (key-order-insensitive) deep-equal against `expected` — see
 * `../README.md` for the fixture shape and why the comparison is structural
 * rather than string-level.
 *
 * Runs under Vitest's dedicated `contracts` project (`vitest.config.ts`,
 * task 008) — plain `node`, no jsdom, since this suite (like `project()`
 * itself) has nothing to do with a DOM.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { project } from '../../view-model/project';
import type {
  ComponentSelection,
  DraftValues,
  ObservedState,
  PackageVersionId,
  SolutionPackage,
  ViewModel,
} from '../../view-model/types';

interface Fixture {
  name: string;
  package: SolutionPackage;
  componentSelection: ComponentSelection;
  draftValues: DraftValues;
  observedState: ObservedState;
  /**
   * The package version to project at (FE-118). OMITTED means "project
   * every version", which is what every fixture written before versions
   * existed means and why none of them had to change beyond the array
   * form of `package.packageVersion`.
   */
  packageVersion?: PackageVersionId;
  expected: ViewModel;
}

const fixturesDir = dirname(fileURLToPath(import.meta.url));
const fixtureFiles = readdirSync(fixturesDir).filter((file) => file.endsWith('.json'));

// The suite must not silently pass by matching zero fixtures — a typo'd
// directory or an accidental `include` change would otherwise look green.
if (fixtureFiles.length === 0) {
  throw new Error(`No fixture files found in ${fixturesDir}`);
}

describe('project() — shared fixture suite', () => {
  it.each(fixtureFiles)('%s', (file: string) => {
    const fixture = JSON.parse(readFileSync(join(fixturesDir, file), 'utf-8')) as Fixture;

    const actual = project(
      fixture.package,
      fixture.componentSelection,
      fixture.draftValues,
      fixture.observedState,
      fixture.packageVersion,
    );

    expect(actual).toEqual(fixture.expected);

    // Obligation 7 / `I6`, direct proof beyond the deep-equal above: no raw
    // draft value belonging to a `secret: true` field may appear anywhere
    // in the projected output, under any key.
    for (const instanceValues of Object.values(fixture.draftValues)) {
      for (const [key, value] of Object.entries(instanceValues)) {
        const isSecretField = fixture.package.components.some((component) =>
          component.fields.some((field) => field.key === key && field.secret === true),
        );
        if (isSecretField && typeof value === 'string' && value.length > 0) {
          expect(JSON.stringify(actual)).not.toContain(value);
        }
      }
    }
  });
});
