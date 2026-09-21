#!/usr/bin/env node
/**
 * Task 034's codegen entry point. `generated/` (design §7) holds OpenAPI
 * output and must never be hand-edited; until a real OpenAPI document
 * exists, hand types live in `src/shared/api/contracts/` instead
 * (`FE-D7`). This script exists so the wiring — npm script, expected
 * input path, output location — is proven correct BEFORE the contract
 * lands, by failing loudly rather than silently doing nothing.
 *
 * Expected input: an OpenAPI document at `../contracts/openapi.yaml`
 * (repo root's shared `contracts/` folder, design §4.1) or
 * `../contracts/openapi.json`. Neither exists yet.
 */
import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const candidatePaths = [
  join(frontendRoot, '..', 'contracts', 'openapi.yaml'),
  join(frontendRoot, '..', 'contracts', 'openapi.json'),
];

const openApiDocument = candidatePaths.find((path) => existsSync(path));

if (openApiDocument === undefined) {
  console.error('generate-types: no OpenAPI document found.');
  console.error('  Looked for:');
  for (const path of candidatePaths) {
    console.error(`    - ${path}`);
  }
  console.error('');
  console.error(
    '  Console REST is not written yet (design §13). Hand types in src/shared/api/contracts/',
  );
  console.error(
    '  (task 034) are the source of truth until a real OpenAPI document exists — this failure',
  );
  console.error('  is expected and intentional, not a bug in this script.');
  process.exit(1);
}

// An OpenAPI document exists but no generator is wired yet — still fails,
// with a DIFFERENT message, so "found the file" and "generated from it"
// are never conflated.
console.error(`generate-types: found ${openApiDocument}, but no generator is wired yet.`);
console.error('  Wiring the actual codegen step (e.g. openapi-typescript) is future work —');
console.error('  this script only proves the input/output seam exists.');
process.exit(1);
