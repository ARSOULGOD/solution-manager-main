#!/usr/bin/env node
/**
 * Generates the browser's Solution Package schema validator ahead of time.
 *
 * AJV normally compiles a schema with `new Function()`. That is correct in a
 * Node build step and forbidden by the console's production Content Security
 * Policy. The emitted ESM module contains only ordinary validation code and
 * works both through Vite's development server and in its production bundle.
 */
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import Ajv2020 from 'ajv/dist/2020.js';
import standaloneCode from 'ajv/dist/standalone/index.js';

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const schemaPath = join(frontendRoot, '..', 'contracts', 'solution-package.schema.json');
const outputPath = join(
  frontendRoot,
  'contracts',
  'solution-package',
  'generated',
  'solution-package-validator.mjs',
);

function makeBrowserEsm(source) {
  const esm = source.replace(
    /^const (\w+) = require\("([^"]+)"\)\.default;$/gm,
    (_, binding, specifier) =>
      `import ${binding}Module from "${specifier}";\n` +
      `const ${binding} = typeof ${binding}Module === 'function' ` +
      `? ${binding}Module : ${binding}Module.default;`,
  );
  if (/\brequire\(/u.test(esm)) {
    throw new Error('AJV emitted an unsupported CommonJS runtime dependency.');
  }
  return esm;
}

function generate() {
  const schemaText = readFileSync(schemaPath, 'utf8');
  const schema = JSON.parse(schemaText);
  const schemaHash = createHash('sha256').update(schemaText).digest('hex');
  const ajv = new Ajv2020({
    allErrors: true,
    strict: true,
    strictRequired: false,
    allowUnionTypes: true,
    code: { source: true, lines: true, esm: true },
  });
  const validate = ajv.compile(schema);

  return [
    '/* This file is generated. Do not edit it directly. */',
    `/* source: contracts/solution-package.schema.json · sha256:${schemaHash} */`,
    makeBrowserEsm(standaloneCode(ajv, validate)),
    '',
  ].join('\n');
}

const expected = generate();
if (process.argv.includes('--check')) {
  let actual;
  try {
    actual = readFileSync(outputPath, 'utf8');
  } catch {
    actual = undefined;
  }
  if (actual !== expected) {
    console.error(
      'Solution Package validator is missing or stale. Run `npm run generate:package-validator`.',
    );
    process.exit(1);
  }
  console.log('Solution Package standalone validator matches the canonical schema.');
} else {
  mkdirSync(dirname(outputPath), { recursive: true });
  writeFileSync(outputPath, expected);
  console.log(`Generated ${outputPath}`);
}
