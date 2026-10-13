#!/usr/bin/env node
/**
 * Proves ChartRenderer's `import('echarts')` (task 072, `FE-D16`, design
 * §17: "ECharts async import inside ChartRenderer") actually lands in a
 * separate chunk instead of being inlined wherever ChartRenderer is used.
 *
 * Can't check the real `dist/` for this: `ChartRenderer` has no consumer
 * yet (no route wires it in until a later Monitoring task), so Rollup
 * tree-shakes the whole file out of the real app build — a real `dist/`
 * currently contains zero `echarts` code either way, which would prove
 * nothing. Runs its own isolated build (same `vite.config.ts` — same
 * aliases, same React plugin) from a synthetic entry that imports
 * `ChartRenderer` and anchors it to `window` — a bare re-export entry
 * turned out to be tree-shaken to nothing too (empirically confirmed: an
 * entry that only re-exported `ChartRenderer` emitted zero trace of its
 * body, since nothing downstream consumes a standalone build's exports),
 * so the anchor is what makes Rollup keep it. Asserts from Rollup's own
 * chunk metadata (`moduleIds`, not string-matching emitted code, which
 * even unminified can drop comments/reorder text) that the entry chunk
 * pulls in none of `node_modules/echarts`, and some other chunk pulls in
 * all of it.
 */
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { build } from 'vite';

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const scratchDir = mkdtempSync(join(tmpdir(), 'assert-echarts-chunk-'));
const entryPath = join(scratchDir, 'entry.tsx');

writeFileSync(
  entryPath,
  [
    "import { ChartRenderer } from '@features/monitoring/chart/ChartRenderer';",
    '',
    'if (typeof window !== "undefined") {',
    '  window.__chartRendererAnchor = ChartRenderer;',
    '}',
    '',
  ].join('\n'),
);

function isEchartsModule(moduleId) {
  return moduleId.includes(`${sep}node_modules${sep}echarts${sep}`);
}

try {
  const result = await build({
    root: frontendRoot,
    configFile: join(frontendRoot, 'vite.config.ts'),
    logLevel: 'silent',
    build: {
      outDir: scratchDir,
      emptyOutDir: false,
      write: true,
      minify: false,
      rollupOptions: { input: entryPath },
    },
  });

  const output = Array.isArray(result) ? result[0].output : result.output;
  const chunks = output.filter((item) => item.type === 'chunk');

  const entryChunk = chunks.find((chunk) => chunk.isEntry);
  if (entryChunk === undefined) {
    throw new Error('no entry chunk emitted');
  }

  const entryPullsInEcharts = entryChunk.moduleIds.some(isEchartsModule);
  if (entryPullsInEcharts) {
    console.error(
      'assert-echarts-chunk: the entry chunk itself contains echarts modules — ' +
        "the import('echarts') in ChartRenderer.tsx is not being code-split (FE-D16, design §17).",
    );
    process.exit(1);
  }

  const echartsChunk = chunks.find(
    (chunk) => chunk !== entryChunk && chunk.moduleIds.some(isEchartsModule),
  );
  if (echartsChunk === undefined) {
    console.error(
      'assert-echarts-chunk: no emitted chunk contains echarts at all — ' +
        'expected a separate async chunk from ChartRenderer.tsx\'s dynamic import.',
    );
    process.exit(1);
  }

  console.log(
    `assert-echarts-chunk: echarts is isolated to its own chunk (${echartsChunk.fileName}), ` +
      `absent from the entry chunk (${entryChunk.fileName})`,
  );
} finally {
  rmSync(scratchDir, { recursive: true, force: true });
}
