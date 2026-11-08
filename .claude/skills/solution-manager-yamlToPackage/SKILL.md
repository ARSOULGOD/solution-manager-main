---
name: solution-manager-yamlToPackage
description: >-
  Turns a Solution Manager solution's role YAML (application.yml or similar) into the Solution
  Package fields (`<solution>/package.json`) that the dynamic Designer UI renders. It merges them
  into an existing package without disturbing other components, then validates and reports
  coverage. Use this whenever the user picks a solution and role (file-mesh source/sink, cache,
  db-designer, or a new solution) and supplies its YAML. Also use it when they ask to create,
  regenerate, update or sync a solution package, package.json fields or Designer fields from
  config, or to onboard a new solution's configuration into the console. A solution with no mapping
  profile yet goes through a propose-and-confirm flow before anything is generated. Also use it to
  build or test the UI for a package: generate its contract test, load it into the console in a
  headless browser, and check that its config round-trips back to the YAML. And use it to deploy and
  test the MI over SSH: render the config, dry-run the deploy, start it, check health and logs, run
  a smoke test, and offer teardown.
---

# Solution YAML → Solution Package

A solution's `package.json` (a **Solution Package**) tells the dynamic UI which config fields to
render. This skill derives those fields from the YAML a solution runs with, one **role** at a time.

Solutions differ in key style, structure and roles, so the work is split in two:
- a **generic engine** (`scripts/`), identical for every solution;
- a **mapping profile** per solution (`references/profiles/<solution>.yaml`), holding the
  solution-specific decisions as data.

Never put solution knowledge into the engine. If a decision can't be inferred from the YAML, it
belongs in the profile, or it is a question for the user.

Read as needed:
- `references/package-schema.md`: package shape, what varies between solutions, validation gates.
- `references/mapping-guide.md`: engine rules, merge rules, profile format, how to write a profile.
- `references/profiles/file-mesh.yaml`: the worked example, with its discrepancy list.

## The pipeline, and its gates

| stage | does | reference |
|---|---|---|
| 1. Package creation | YAML → `package.json` (this file, below) | `references/mapping-guide.md` |
| 2. UI building | contract test, console in a headless browser, YAML round-trip | `references/ui-testing.md` |
| 3. Solution via SSH | render config, dry run, deploy, verify, smoke test, offer teardown | `references/ssh-deploy.md` |

Each stage ends with a PASS/FAIL (or BLOCKED) report, and then **STOP and wait for the user's go-ahead**
before the next stage. A user who asked only for the package gets Stage 1 and an offer of Stage 2, not Stage 2.

## Inputs to establish first

1. **Solution and role.** Roles come from the profile; a solution with no roles uses `--role none`.
2. **The YAML** for that role.
3. **The existing package**, if any. In the Solution Manager repo the canonical packages are
   `frontend/contracts/packages/<solution>/package.json`; the profile's `package:` names its own.
4. **Target package version(s).** Ask every time, even when the answer seems obvious. A wrong guess
   silently changes which instances see the fields. Without an existing package, also ask for
   `appliesTo` (the semver range of MI versions).

## Workflow

### A. The solution already has a profile

Check that `references/profiles/<solution>.yaml` exists and says `status: confirmed`. Report which
profile you used, then run the engine:

```bash
python3 scripts/yaml_to_package.py \
  --profile references/profiles/<solution>.yaml --role <role> --yaml <role yaml> \
  --target-versions <v,...> --package <existing package.json> --out-dir <scratch dir>
```

Exit codes:

| code | meaning | what to do |
|---|---|---|
| 0 | done | read `report.md`; FLAGGED leaves still need decisions |
| 1 | validation failed | fix the cause; never hand-edit the output to pass |
| 2 | bad input | check the arguments and files |
| 3 | draft profile | follow flow B |
| 4 | version decision needed | ask the user; never widen versions on one component |

Then present the four outputs (see "Output of every run").

The engine never overwrites `--package`. Copy `<out-dir>/package.json` over the real file only once
the user has seen the report and agrees.

**Running several roles in a row** (for example, "now do the sink" after a source run): pass the previous
run's `<out-dir>/package.json` as `--package`, not the original file. Otherwise the second run starts from a
package without the first run's changes. Say which base you used. If the earlier output was never applied
and the user may want it discarded, ask. In the repo, also run
`python3 contracts/validation/validate-package.py --dev-mode <package.json>`.

### B. The solution has NO profile (the common case)

1. **Inspect.** Run the proposer. Pass one `--yaml` per role file if there are several; comparing
   them is how shared groups are told apart from role groups.
   ```bash
   python3 scripts/profile_proposer.py --yaml <file> [--yaml <file>] [--solution <name>] \
     --out <scratch dir>/<solution>.draft.yaml
   ```
2. **Show the structure:** top-level groups, leaf count, key style, lists, quoted booleans, duplicates.
3. **Say what it resembles and why**, using the proposer's ranking. Resemblance is evidence, not a
   template. A YAML that looks like File Mesh still does not inherit File Mesh's roles or component
   boundaries.
4. **Show the draft profile** and the numbered open questions. Add your own questions where the draft
   is unsure, for example per-role values hiding in a "shared" group.
5. **STOP and wait for the user's answers.** Do not guess roles, component boundaries, key style,
   cardinality, enum options or list handling, however obvious they look. A wrong guess here becomes
   a package that renders and applies the wrong config. The question costs one message.
6. After confirmation:
   - write the profile to `references/profiles/<solution>.yaml`;
   - record each resolved mismatch under `discrepancies` with an id like `<PREFIX>-D1`;
   - set `status: confirmed`;
   - run flow A.

## Rules that are easy to get wrong

- **Every leaf is accounted for.** Each leaf is MAPPED, FLAGGED (with a reason) or EXCLUDED (by the
  profile, with a reason). The report states whether the buckets add up. Nothing disappears silently.
- **Never invent** `when`, `required`, `discriminator`, `pattern`, `min`/`max`, enum options or defaults.
  If the YAML or its comments suggest one, list it as a suggestion in the open questions.
- **Secrets never carry a default** (project invariant I6). The engine withholds every one and lists
  them. Don't put them back.
- **Merge, don't overwrite.** Only the chosen role's component(s) are rebuilt. Everything else is
  carried over byte-for-byte, including other components, metrics, actions and
  `virtual`/`valueFrom`/`optionsSource` fields. Shared components are verified and reported, not
  rewritten; new shared leaves need the user's agreement and `--update-shared`.
- **Versions nest.** Field `version` ⊆ component `version` ⊆ `packageVersion`. A version is never
  introduced on only some components.
- **Provenance.** Never fabricate a signature. If a signed package changed, ask whether to re-run with
  `--provenance dev-mode`.
- **The reviewed `ui` wins** for fields already in the package (`preserve_reviewed_ui`). Derived
  labels that differ are listed for information, not applied.

## Output of every run

Always give the user all four, in this order:

**(a) The package**: the path to `<out-dir>/package.json`, plus a one-line validation verdict.

**(b) The coverage report**: counts, then the FLAGGED, EXCLUDED, coerced and withheld-secret lists
with reasons. Summarise from `report.md` and link it; don't paste 100 MAPPED rows.

**(c) The diff against the existing package**:
- per touched component: added, changed (attribute: old → new) and package-only fields;
- shared-component default differences;
- the path to `package.diff`.

**(d) Open questions and assumptions**: numbered, so the user can answer by number. Group repeated
reasons into one question. Include:
- every FLAGGED decision;
- package-only fields;
- the provenance question;
- anything you assumed.

Use this layout:

```markdown
## <solution> / <role>: <PASS|FAIL>
Profile: references/profiles/<solution>.yaml (confirmed) · targets [1, 2, 3]
Package: <out-dir>/package.json · report: <out-dir>/report.md · diff: <out-dir>/package.diff

**Coverage:** <n> leaves → <m> mapped, <f> flagged, <e> excluded (balanced: yes) · <c> coerced · <s> secrets withheld

**Changes:** <component>: +<added> / ~<changed> / <package-only> kept …

**Open questions**
1. …
```

## Stage 2: UI building

Follow `references/ui-testing.md`. In short:

1. `scripts/run_frontend.sh setup`, once. The frontend runs in Docker (Node ≥ 22.12), with its own volumes.
2. Run the existing hand-written contract test as a baseline. Then generate and run
   `package.generated.test.ts` (`scripts/gen_contract_test.py`) in a temporary `_skill_<solution>/` folder.
3. Start the dev console and run `run_frontend.sh ui-check` with each role. It loads the package through the
   `/solutions` dialog and checks:
   - the catalog card and inventory;
   - the Designer per role, with sections in `ui.order`;
   - labels and helper text;
   - masked secrets, with no secret value in the DOM;
   - defaults, via the console's YAML view.

   Screenshots are saved per step.
4. `scripts/render_config.py roundtrip` per role, against the console's own rendering.
5. Report the Stage 2 table, delete the temporary folder, stop the dev server, and **STOP**.

Never change UI code to make a check pass. Known console issues found so far are listed at the end of
`references/ui-testing.md`; report them as found.

## Stage 3: Solution via SSH

Read `references/ssh-deploy.md` first. It is a **test harness**: SM itself does not deploy artifacts, so never
present this as an SM feature.

1. Ask for host/user/port, key or agent auth (**never a password**), a known_hosts file, delivery and artifact,
   install dir, start mode and broker details. Secret values stay in the user's shell as `SM_SECRET_*` variables.
2. `render_config.py render --values <designer draft>`: secrets become `${SM_SECRET_*}` placeholders.
3. `deploy_ssh.sh plan` (no network). Show it and **wait for explicit approval**.
4. `deploy_ssh.sh apply --yes-i-approved`, then `verify`: health UP, log scan, Solace connection (MOCKED/real).
5. `smoke_test.py` with the profile's `smoke_test:`. Unconfirmed specs need the user's acceptance.
6. Offer `deploy_ssh.sh teardown`; never run it unasked.

If SSH is refused (exit 4), don't try workarounds. Stay in `plan` and hand over the commands and the script.

## Final output: per-stage summary

End the whole run with one table, after each stage's own report:

| stage | verdict | evidence | open questions |
|---|---|---|---|
| 1. Package creation | PASS/FAIL | `<out-dir>/report.md`, `package.diff` | n |
| 2. UI building | PASS/FAIL/BLOCKED | contract test, `ui-check.json`, screenshots, round-trip | n |
| 3. Solution via SSH | PASS/FAIL (real host / stand-in, mocked broker) | plan, verify, smoke report | n |

A stage that was not run says "not run (awaiting go-ahead)".

## Scripts

| script | does |
|---|---|
| `scripts/yaml_to_package.py` | engine: flatten, type, derive ui, merge, validate, write package + report + diff |
| `scripts/profile_proposer.py` | no-profile flow: structure, resemblance, draft profile, questions |
| `scripts/validate_package.py` | schema + structural gates; usable on any package |
| `scripts/gen_contract_test.py` | Stage 2: generates the vitest contract test for a package + profile |
| `scripts/run_frontend.sh` | Stage 2: runs vitest, the dev server, Playwright and `ui-check` in Docker |
| `scripts/ui_check.mjs` | Stage 2: headless browser check of the loaded package |
| `scripts/render_config.py` | renders a role's config (secrets as `${SM_SECRET_*}`); `roundtrip` compares it with the YAML |
| `scripts/deploy_ssh.sh` | Stage 3: `plan` (default, no network) / `apply` / `verify` / `teardown` over SSH |
| `scripts/smoke_test.py` | Stage 3: runs the profile's `smoke_test:` over SSH |
| `scripts/testing/` | the skill's own Stage 3 target: local sshd, stand-in MI image, mocked broker |
| `scripts/common.py` | shared: strict YAML 1.2 loader, flattening, matching, humanising |

They need Python 3 with PyYAML. `jsonschema` is optional; without it, the schema gate is skipped and
the output says so. The schema is found by walking up to `contracts/solution-package.schema.json`;
outside the repo, pass `--schema`, or ask the user for the file.

`examples/` holds the File Mesh source and sink runs (inputs, output package, report, diff) to
compare against.
