# Stage 2: UI building (load the package into the console and prove it renders)

The console must render **any** valid package with **no per-solution code**. Stage 2 proves that for the package
Stage 1 produced. It never modifies UI code: a rendering problem is reported, with a screenshot, as a finding.

## Contents
- Prerequisites
- 2.1 Runner and baseline
- 2.2 Generated contract test
- 2.3 Headless browser check
- 2.4 Round-trip
- Verdicts and the gate
- Known console issues (as of 2026-11-08)

## Prerequisites

Ask the user for these if you don't know them:
- the UI repo path (default: the Solution Manager monorepo's `frontend/`);
- the run command (default: `scripts/run_frontend.sh dev <port>`).

The frontend needs Node ≥ 22.12. `scripts/run_frontend.sh` runs everything in Docker, so the host's Node version
doesn't matter. Its own `node_modules` and the Playwright browsers live in named volumes, so the host's
`frontend/node_modules` is never touched.

```bash
scripts/run_frontend.sh setup          # once: builds sm-skill-node22-playwright, npm ci, Chromium
```

## 2.1 Runner and baseline

Run the hand-written contract test that ships beside the package, if there is one. A red baseline belongs in the
report as found, because it is not caused by this run:

```bash
scripts/run_frontend.sh test contracts/packages/<solution>/package.test.ts
```

## 2.2 Generated contract test

The test must sit inside `frontend/contracts/packages/` for vitest's `contracts` project to see it. Use a temporary
folder, `_skill_<solution>/`, holding a copy of the package. Delete the folder afterwards, unless the user asks to
keep the test, in which case it goes in a reviewed change.

```bash
T=frontend/contracts/packages/_skill_<solution>
mkdir -p $T && cp <out-dir>/package.json $T/
python3 scripts/gen_contract_test.py --package $T/package.json --profile references/profiles/<solution>.yaml \
  --out $T/package.generated.test.ts
scripts/run_frontend.sh exec env SM_SKILL_ARTIFACT_DIR=/repo/$T/artifacts npx vitest run contracts/packages/_skill_<solution>
```

The test covers:
- validity, using `parseAndValidateSolutionPackage` in dev mode;
- the components, roles and cardinalities the profile names;
- unique keys, case-insensitively;
- role-only fields kept in their role component;
- every password a secret with no default, projected as a masked control;
- enum defaults among their options;
- `when` show/hide, both ways;
- `required` with no default projecting a `REQUIRED` validation;
- `project()` at **every** declared package version, giving exactly the version-filtered fields.

Known defects are pinned with `it.fails` and reported, never worked around. It also writes
`artifacts/<solution>-<role>.rendered.json`: the console's own rendering of each role's defaults, using
`yamlFromFields`. Step 2.4 consumes it.

## 2.3 Headless browser check

```bash
scripts/run_frontend.sh dev 5199                                     # background; wait for HTTP 200
SM_WORK=<work dir> scripts/run_frontend.sh ui-check --base http://127.0.0.1:5199 \
  --package /work/package.json --out /work/ui --roles source,sink --secrets /work/secret-values.json
```

`secret-values.json` is a JSON array of the **values** of the YAML leaves the engine withheld. Build it in memory
from the YAML and `report.json`, keep it in the work dir, and never print it. The check searches every visited
page for those values.

It records PASS / FAIL / BLOCKED / N/A for each of these, with one screenshot per step:

| check | how |
|---|---|
| load dialog validates the package | `/solutions` → "Load a package" → file input → "Package format is valid" |
| every package version shown | dialog text `Package versions …` |
| inventory | each component card shows its cardinality and field count |
| catalog card | card for the solution, latest version marked |
| instances page | `/solutions/<solution>/instances` renders |
| Designer per role | an instance of the role from `GET /api/dp/solutions/<solution>/instances`; Designer route renders |
| sections in `ui.order` | "All fields" headings and per-section counts equal the package's, for that instance's components and version |
| labels, helper text | every non-predicated field's label and helperText present |
| secrets masked | count of `input[type=password]` equals the secret fields |
| `when` show/hide | N/A when the rendered components have none; otherwise covered by the contract test |
| defaults | "YAML view" → "Copy YAML" → parsed; every non-secret default equals the package's |
| version selector | in the Declare-instance dialog |
| no secret in the DOM | page HTML searched for each withheld value (≥ 6 chars) |

Exit codes: 0 PASS, 1 FAIL, 3 BLOCKED (nothing failed, but something could not be checked). Read BLOCKED rows to
the user as **not verified**, never as passed.

## 2.4 Round-trip

```bash
python3 scripts/render_config.py roundtrip --package <out-dir>/package.json --profile <profile> --role <role> \
  --version <v> --yaml <role yaml> --report <out-dir>/report.json \
  --console-artifact $T/artifacts/<solution>-<role>.rendered.json --out <work>/roundtrip-<role>.md
```

This renders the role's defaults with the console's rules and compares them with the input YAML, ignoring comments
and key order. Allowed differences come only from `report.json`:
- excluded, flagged, coerced and withheld leaves;
- shared defaults kept.

It also allows keys from the profile's preserved components. Anything else is a bug in the package or the profile.
With `--console-artifact`, it also fails if the console's renderer and `render_config.py` disagree on any key.

## Verdicts and the gate

End Stage 2 with this table, then **STOP**, and don't start Stage 3 without the user's go-ahead:

```markdown
## Stage 2: UI building: <PASS|FAIL|BLOCKED>
| step | verdict | evidence |
|---|---|---|
| 2.1 baseline contract test | … | … |
| 2.2 generated contract test | … | n passed, n known defects |
| 2.3 browser check | … | ui-check.json, screenshots |
| 2.4 round-trip (per role) | … | roundtrip-<role>.md |
Known defects / blocked checks: …
```

## Known console issues (as of 2026-11-08)

Each was found by this skill and is reported, not fixed:

1. **Leading-dot `renderedPath`.** `frontend/contracts/view-model/project.ts` builds `renderedPath` as
   `${configPath}.${key}`, so `configPath: ""` gives `.key`. The canonical engine
   (`contracts/view-models/src/project.ts`, `renderedPathFor`) does not. The YAML renderer hides it, because
   `setAtPath` drops empty segments. The generated test pins it with `it.fails`.
2. **The instances grid crashes for a loaded package.** "MUI X: … rows to have a unique `id`". The dev fixture
   handlers still serve the pre-plan-008 row shape. The repo's own `src/app/dev/devPackageFlow.test.ts` and
   `devPackageScreens.test.tsx` fail on the same path (5 tests). This blocks the instances page and the
   Declare-instance dialog with its version selector.
3. **The File Mesh hand-written `package.test.ts` is red** against its own package (4 failures). It assumes
   `configPath: file.source`, while the package now uses `configPath: ""`.
4. **The baked File Mesh dev instances only partly cover a role.** `fm-source-1` and `fm-sink-1` select only the
   role component plus `spring-cloud-stream`, at package version 1. The other shared components and the later
   versions are verified by the contract test (`project()`), not in the browser.
5. **Cold compile.** The first visit to a Designer route on the dev server can take more than 4 s, so the check
   waits up to `--designer-timeout` (20 s) before calling it BLOCKED.
