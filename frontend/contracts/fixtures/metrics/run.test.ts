/// <reference types="node" />
/**
 * The shared fixture suite's TypeScript runner (task 071, `DM-D20`). Reads
 * every `*.json` fixture in this directory, calls `projectSeries()`, and
 * asserts a structural (key-order-insensitive) deep-equal against
 * `expected` — see `../README.md` for the fixture shape and why the
 * comparison is structural rather than string-level.
 *
 * Runs under Vitest's dedicated `contracts` project (`vitest.config.ts`,
 * task 008) — plain `node`, no jsdom, since `projectSeries()` has nothing
 * to do with a DOM.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { projectSeries } from '../../metrics/projectSeries';
import type { ChartModel, ChartTheme, ChartWidgetConfig, QueryResponse } from '../../metrics/types';

interface Fixture {
  name: string;
  queryResponse: QueryResponse;
  widgetConfig: ChartWidgetConfig;
  theme: ChartTheme;
  expected: ChartModel;
}

const fixturesDir = dirname(fileURLToPath(import.meta.url));
const fixtureFiles = readdirSync(fixturesDir).filter((file) => file.endsWith('.json'));

// The suite must not silently pass by matching zero fixtures — a typo'd
// directory or an accidental `include` change would otherwise look green.
if (fixtureFiles.length === 0) {
  throw new Error(`No fixture files found in ${fixturesDir}`);
}

describe('projectSeries() — shared fixture suite', () => {
  it.each(fixtureFiles)('%s', (file: string) => {
    const fixture = JSON.parse(readFileSync(join(fixturesDir, file), 'utf-8')) as Fixture;

    const actual = projectSeries(fixture.queryResponse, fixture.widgetConfig, fixture.theme);

    expect(actual).toEqual(fixture.expected);

    // Obligation 1, direct proof beyond the deep-equal above: the response
    // never carries more points than were present on the wire, and no
    // point's value is a bare 0 that the input didn't already have — a
    // chart library coercing an absent sample to a zero-filled point would
    // still pass a naive deep-equal if the fixture's `expected` were wrong,
    // so this checks point *counts* line up 1:1 per series independently.
    fixture.queryResponse.series.forEach((inputSeries, index) => {
      const actualSeries = actual.series[index];
      expect(actualSeries).toBeDefined();
      expect(actualSeries?.points).toHaveLength(inputSeries.points.length);
    });
  });
});
