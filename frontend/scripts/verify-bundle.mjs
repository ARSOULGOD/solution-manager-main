#!/usr/bin/env node
/**
 * FE-096 · bundle budget for the *initial* JavaScript payload.
 *
 * "Initial" = the entry chunk plus every chunk reachable from it through a
 * **static** import — everything the browser must download before the first
 * route can render. Lazy route chunks (`router.tsx` `lazy(() => import())`),
 * the ECharts chunk (`ChartRenderer`'s `import('echarts')`, FE-D16) and the
 * Data Grid chunk (`DataTable`, FE-020 / FE-D11) are excluded by
 * construction and must stay excluded.
 *
 * Runs the real production build in-memory (`write: false`, same
 * `vite.config.ts`) and asserts, as hard build failures:
 *   1. no `node_modules/echarts` module in any initial chunk;
 *   2. no `node_modules/@mui/x-data-grid` module in any initial chunk;
 *   3. total gzipped size of the initial chunks <= the recorded ceiling.
 *
 * Sizes are gzipped because that is what crosses the wire. `zlib.gzipSync`
 * is deterministic for a given Node/zlib; CI and local both run Node 22.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * CEILING: 160 KiB gzip. Rationale (keep in sync with the note in
 * `.github/workflows/frontend.yml`):
 *
 *   Baseline 2026-09-02 (FE-096): 134 KiB gzip — React + ReactDOM, MUI core
 *   + Emotion (FE-D4, one MUI design system), TanStack Query (FE-D1), and
 *   the theme / auth / router shell. None of that is route code; none of it
 *   is reducible without reversing a locked decision.
 *
 *   Ceiling = baseline + ~20% headroom. It absorbs incremental growth in
 *   the shared shell. It does NOT absorb a new heavy dependency landing in
 *   the initial graph, or a feature that forgets to lazy-split — those are
 *   the regressions this gate exists to catch. Raise it only with a
 *   one-line note here AND in the workflow saying what grew and why.
 * ─────────────────────────────────────────────────────────────────────────
 */
import { sep } from 'node:path';
import { gzipSync } from 'node:zlib';

import { build } from 'vite';

const INITIAL_JS_GZIP_CEILING_BYTES = 160 * 1024;

const ECHARTS_PREFIX = `${sep}node_modules${sep}echarts${sep}`;
const DATA_GRID_PREFIX = `${sep}node_modules${sep}@mui${sep}x-data-grid`;

const KiB = (bytes) => `${(bytes / 1024).toFixed(1)} KiB`;

const result = await build({
  logLevel: 'silent',
  build: { write: false },
});

const output = (Array.isArray(result) ? result[0] : result).output;
const chunks = output.filter((item) => item.type === 'chunk');
const chunkByFile = new Map(chunks.map((chunk) => [chunk.fileName, chunk]));

const entryChunks = chunks.filter((chunk) => chunk.isEntry);
if (entryChunks.length !== 1) {
  console.error(
    `verify-bundle: expected exactly one entry chunk, found ${entryChunks.length} ` +
      `(${entryChunks.map((c) => c.fileName).join(', ') || 'none'})`,
  );
  process.exit(1);
}

// Transitive closure over *static* imports only (`chunk.imports`), never
// `chunk.dynamicImports`.
const initialFiles = new Set();
const stack = [entryChunks[0].fileName];
while (stack.length > 0) {
  const fileName = stack.pop();
  if (initialFiles.has(fileName)) {
    continue;
  }
  initialFiles.add(fileName);
  for (const imported of chunkByFile.get(fileName)?.imports ?? []) {
    if (chunkByFile.has(imported)) {
      stack.push(imported);
    }
  }
}

const initialChunks = [...initialFiles].map((file) => chunkByFile.get(file));
const hasModule = (chunk, prefix) => chunk.moduleIds.some((id) => id.includes(prefix));

const failures = [];

const echartsInInitial = initialChunks.filter((chunk) => hasModule(chunk, ECHARTS_PREFIX));
if (echartsInInitial.length > 0) {
  failures.push(
    `ECharts is in the initial payload (${echartsInInitial
      .map((c) => c.fileName)
      .join(', ')}). It must stay behind ChartRenderer's dynamic import (FE-D16, design §17).`,
  );
}

const dataGridInInitial = initialChunks.filter((chunk) => hasModule(chunk, DATA_GRID_PREFIX));
if (dataGridInInitial.length > 0) {
  failures.push(
    `MUI X Data Grid is in the initial payload (${dataGridInInitial
      .map((c) => c.fileName)
      .join(
        ', ',
      )}). It must load with the route that uses DataTable, not before first render (design §17).`,
  );
}

let initialGzipBytes = 0;
const rows = initialChunks
  .map((chunk) => {
    const gzip = gzipSync(Buffer.from(chunk.code)).length;
    initialGzipBytes += gzip;
    return { file: chunk.fileName, gzip };
  })
  .sort((a, b) => b.gzip - a.gzip);

if (initialGzipBytes > INITIAL_JS_GZIP_CEILING_BYTES) {
  failures.push(
    `initial JS is ${KiB(initialGzipBytes)} gzip, over the ${KiB(
      INITIAL_JS_GZIP_CEILING_BYTES,
    )} ceiling by ${KiB(initialGzipBytes - INITIAL_JS_GZIP_CEILING_BYTES)}.`,
  );
}

console.log('verify-bundle: initial JS payload (static imports from the entry chunk)');
for (const row of rows) {
  console.log(`  ${row.file.padEnd(44)} ${KiB(row.gzip).padStart(11)}`);
}
console.log(`  ${'—'.repeat(44)} ${'—'.repeat(11)}`);
console.log(`  ${'total'.padEnd(44)} ${KiB(initialGzipBytes).padStart(11)}`);
console.log(
  `  ceiling ${KiB(INITIAL_JS_GZIP_CEILING_BYTES)} · ` +
    `headroom ${KiB(INITIAL_JS_GZIP_CEILING_BYTES - initialGzipBytes)}`,
);

const lazyEcharts = chunks.find(
  (chunk) => !initialFiles.has(chunk.fileName) && hasModule(chunk, ECHARTS_PREFIX),
);
const lazyDataGrid = chunks.find(
  (chunk) => !initialFiles.has(chunk.fileName) && hasModule(chunk, DATA_GRID_PREFIX),
);
console.log(
  `  echarts chunk: ${lazyEcharts ? lazyEcharts.fileName : '(not in this build)'} · ` +
    `data-grid chunk: ${lazyDataGrid ? lazyDataGrid.fileName : '(not in this build)'}`,
);

if (failures.length > 0) {
  console.error('\nverify-bundle: FAIL');
  for (const failure of failures) {
    console.error(`  - ${failure}`);
  }
  process.exit(1);
}

console.log('\nverify-bundle: OK');
