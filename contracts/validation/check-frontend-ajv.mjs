// Compiles contracts/solution-package.schema.json with the FRONTEND's own Ajv and the frontend's own options, and
// proves the three package fixtures pass and every gate-1 negative is rejected.
//
// Why this exists (W3-04): the frontend's validator (frontend/contracts/solution-package/validate.ts) runs
// `ajv.compile(schema)` at module load under `strict: true`. A schema that the Python and Java validators accept can
// still fail to COMPILE there (strict mode refuses, for instance, `required` on a subschema with no `type`), and then
// importing the validator throws and every package-validation test and route fails. Nothing else in this suite loads
// the schema with Ajv, so the gap stayed open until the frontend was run.
//
// The options are READ from validate.ts, not copied: a copy would drift the way the gap itself did.
//
// Ajv is resolved from $SM_FRONTEND_NODE_MODULES, else frontend/node_modules. Exit 0 = ok, 1 = check failed,
// 3 = Ajv could not be found (run-checks.sh turns that into a skip, which --strict makes a failure).
import { createRequire } from 'node:module';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', '..');
const nodeModules = process.env.SM_FRONTEND_NODE_MODULES || path.join(root, 'frontend', 'node_modules');
if (!existsSync(path.join(nodeModules, 'ajv', 'package.json'))) {
  console.error(`  frontend Ajv not found under ${nodeModules}`);
  process.exit(3);
}
const require = createRequire(path.join(path.dirname(nodeModules), 'x.js'));
const Ajv2020 = require('ajv/dist/2020').default ?? require('ajv/dist/2020');
const version = require('ajv/package.json').version;

const source = readFileSync(path.join(root, 'frontend', 'contracts', 'solution-package', 'validate.ts'), 'utf8');
const match = /new Ajv2020\(\s*(\{[\s\S]*?\n?\})\s*\)\s*;/.exec(source);
if (!match) {
  console.error('  could not find the `new Ajv2020({...})` options in frontend/contracts/solution-package/validate.ts');
  process.exit(1);
}
const options = new Function(`return (${match[1]});`)();
console.log(`  Ajv ${version}, options ${JSON.stringify(options)}`);

const schema = JSON.parse(readFileSync(path.join(root, 'contracts', 'solution-package.schema.json'), 'utf8'));
let validate;
try {
  validate = new Ajv2020(options).compile(schema);
} catch (e) {
  console.error(`  FAIL the schema does not compile under the frontend's Ajv options: ${e.message}`);
  process.exit(1);
}

let failures = 0;
const fixtures = path.join(here, 'fixtures');
for (const name of readdirSync(fixtures).filter((n) => n.endsWith('.package.json')).sort()) {
  const ok = validate(JSON.parse(readFileSync(path.join(fixtures, name), 'utf8')));
  if (!ok) {
    failures++;
    console.error(`  FAIL fixture ${name} is rejected by the frontend's Ajv: ${JSON.stringify(validate.errors?.slice(0, 2))}`);
  }
}
const negatives = path.join(here, 'negative');
let rejected = 0;
for (const name of readdirSync(negatives).filter((n) => n.startsWith('gate01-') && n.endsWith('.json')).sort()) {
  if (validate(JSON.parse(readFileSync(path.join(negatives, name), 'utf8')))) {
    failures++;
    console.error(`  FAIL gate-1 negative ${name} is ACCEPTED by the frontend's Ajv`);
  } else {
    rejected++;
  }
}
if (failures > 0) {
  process.exit(1);
}
console.log(`  schema compiles; ${rejected} gate-1 negatives rejected; fixtures accepted`);
