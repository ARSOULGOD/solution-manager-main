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
5. **The samples folder** the suggestions are learned from: every `package.json` under
   `frontend/contracts/packages/`, found by walking up from the working folder, or `--samples <folder>`.
   Say which folder you will use; the preview's `Samples:` line confirms it. No samples is not a failure:
   the preview says so and suggests nothing.

## Workflow

### A. The solution already has a profile

Check that `references/profiles/<solution>.yaml` exists and says `status: confirmed`, and say which
profile you used. Then loop **preview → answers → preview** until nothing is open:

1. **Preview.** It writes `preview.md` and `preview.json` to `--out-dir` and never a package or a profile.
   ```bash
   python3 scripts/yaml_to_package.py preview \
     --profile references/profiles/<solution>.yaml --role <role> --yaml <role yaml> \
     --target-versions <v,...> --package <existing package.json> [--samples <folder>] \
     [--answers <out-dir>/answers.yaml] --out-dir <scratch dir>
   ```
   With no existing package, drop `--package` and pass `--applies-to "<range>"`.
2. **Show it.** The first time, show the whole `preview.md` (see "Output of every run"). A re-run starts
   with `## Changes since the last preview`: show those changes plus the still-open `### Questions`, not
   the whole table again.
3. **Answers.** The user answers the numbered questions by number, and may amend anything (label, section,
   position, advanced, required, type, options, `when`, `pattern`, `min`/`max`, helper text) or remove a key
   with a reason. **You write their replies into `<out-dir>/answers.yaml`** (format below; full rules in
   `references/mapping-guide.md`, "The answers file") and re-run step 1 with `--answers`. Repeat until the
   preview exits 0.
4. **Ask "Create package.json? (yes/no)".** Until now only scratch files exist. On "no", stop there.
5. **Apply**, with exactly the preview's arguments (`--answers` included):
   `python3 scripts/yaml_to_package.py apply ...same arguments...`. It refuses while anything is open, then
   writes `package.json`, `package.diff` (with `--package`), `report.md`, `report.json` and `profile.yaml`
   (the profile with your answers merged into its decisions block), reads the package back and validates it.
6. **Only on exit 0**, copy `<out-dir>/profile.yaml` over `references/profiles/<solution>.yaml`, so the
   answers are remembered and the next run asks only what is new. **After a non-zero exit, copy nothing into
   place:** `profile.yaml` is written even when the read-back fails.
7. **Report** the `apply` result (see "Output of every run").
8. **The canonical-copy gate (unchanged).** Copying `<out-dir>/package.json` over
   `frontend/contracts/packages/<solution>/package.json` needs the user's separate OK, after they have seen
   the report. In the repo, also run `python3 contracts/validation/validate-package.py --dev-mode <package.json>`.
   Then offer Stage 2.

Exit codes (both commands):

| code | meaning | what to do |
|---|---|---|
| 0 | `apply`: done · `preview`: nothing open | `preview`: ask "Create package.json?" · `apply`: copy the profile, report |
| 1 | validation or read-back failed | report it; fix the cause, never hand-edit the output; copy nothing |
| 2 | bad input, including a bad answers file | the message names the entry; fix it and re-run |
| 3 | draft profile | follow flow B |
| 4 | version decision needed | ask the user; never widen versions on one component |
| 5 | questions still open | show them and wait for the answers (`apply` lists their ids) |

Neither command writes over an input: an `--out-dir` that would overwrite the YAML, package, profile or
answers file is refused (exit 2). Answers are validated in stages, since some questions appear only once an
earlier answer is applied (placing a key can raise its type conflict). An answer nothing asks for, a choice
not offered, or a secret default exits 2 naming the entry.

**The answers file** (`<out-dir>/answers.yaml`; every section optional):

```yaml
remove:      # a question's `remove` choice, the dropped spelling of a pair, or a key the user removes
  - {key: <yaml key>, reason: "<the user's reason>"}               # reason required
  - {component: <type>, field: <field key>, reason: "..."}         # a field only the package has
place:       # place · role-component · all-roles · this-role; `component` is always named
  - {key: <k>, component: <component type>}
  - {key: <k>, component: <shared type>, scope: all-roles | this-role}   # shared only, an offered scope only
  - {key: <k>, component: <new type>, new: {role: <role | none>, configPath: "", cardinality: one-of-many}}
store:       # every other key-level choice: list, join-comma, index, map, string, add-option,
  - {key: <k>, as: <choice>}  # first, second, package-type, yaml-type. One per question; a key may have several
suggestions:
  - {key: <k>, attribute: required | advanced, decision: accept | reject}
amend:       # key, or component + field; `set` takes the attributes listed in mapping-guide
  - {key: <k>, set: {required: true, ui.label: "Adapter ID"}, reason: "..."}
restyle: accept | refuse
provenance: keep | dev-mode
forget:      # drop a remembered decision so it is asked again
  - {key: <k>}
  - {component: <type>, field: <field key>, attribute: required}
```

**Running several roles in a row** (for example, "now do the sink" after a source run): pass the previous
run's `<out-dir>/package.json` as `--package` **and** its `<out-dir>/profile.yaml` as `--profile` (once copied,
the same file as `references/profiles/<solution>.yaml`), with a new `--out-dir`. Otherwise the second run starts
without the first run's changes or decisions. Say which base you used. If the earlier output was never applied and the user may want it discarded, ask.

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

- **Never drop a key; only the user removes one.** Every YAML key is placed, open (a question) or removed by
  you (the user's decision, with a reason). Never answer a question for the user, and never write a `remove`
  they did not give. `apply` refuses while anything is open and fails if a key went missing on read-back.
- **Spelling pairs.** Two keys that differ only in case, `-` or `_` (File Mesh's FM-D1) stay open until the
  user says which spelling to keep. The other becomes a `remove` with their reason. Don't pick for them.
- **Every suggestion needs acceptance, and shows its evidence.** A suggested `required` or `ui.advanced`
  comes from the samples (strict word match, more than half of the matched fields). Show every evidence line
  (`sample/component/field: flag, only when …`). Accepted, the field gets the attribute; rejected, it is
  remembered and never asked again. **Never invent** `when`, `discriminator`, `pattern`, `min`/`max`, enum
  options or defaults; the user can add any of them as an amendment.
- **Secrets, including comments** (project invariant I6). A secret's default is always null, and its value
  never appears in the package, the preview, `answers.yaml`, the decisions block, the reports or chat. An
  amendment that sets a secret's default is refused (exit 2). A YAML comment that repeats a secret value never
  becomes helper text; don't quote it either. `type: secret` always comes with `secret: true`.
- **The restyle block** is one question for the whole package: accept or refuse it as a whole. It lists, per
  component (`preserve` ones too), how many positions change and how many stock helper sentences go; custom
  helper text is kept. Refused, every component keeps its detected style and new fields follow it. Adding
  `secret: true` is listed separately and is not part of it.
- **Never hand-edit the decisions block** between the `# >>> decisions` markers at the end of a profile. Only
  `apply` writes it, and the hand-written part above it stays byte-for-byte. To revisit a remembered decision,
  put a `forget` entry in `answers.yaml`.
- **Example answers never go into the real profile.** Answers from `examples/`, evals or tests are not the
  user's decisions: run them against a scratch copy of the profile, and never copy that run's `profile.yaml`
  into `references/profiles/`.
- **Merge, don't overwrite.** Only the chosen role's component(s) are rebuilt. Everything else is carried
  over, including other components, metrics, actions and `virtual`/`valueFrom`/`optionsSource` fields. A new
  key in a shared component is a question (every role, this role only, or the role component).
- **Versions nest.** Field `version` ⊆ component `version` ⊆ `packageVersion`. A version is never
  introduced on only some components.
- **Provenance.** Never fabricate a signature. A signed package that would change raises the signature
  question (keep, or switch to dev-mode).
- **The reviewed `ui` wins** for fields already in the package (`preserve_reviewed_ui`). Derived
  labels that differ are listed for information, not applied.

## Output of every run

**(a) The preview**, every round. The first time, the whole `preview.md`:
- the header (YAML, package, roles, versions, samples, and `Keys: n → placed · removed by you · open`);
- one table per component with every field: key and line, type, default (secrets `withheld (I6)`), section
  and position, shown, required, notes;
- `### Questions (answer by number)`, each with its choices, and every suggestion with its evidence;
- `### For your information` (shared defaults that differ, coerced values, `secret: true` added, package
  fields not in this YAML) and `### Profile changes saved on "create"`.

After that, show `## Changes since the last preview` and the still-open questions. Link `preview.md`.

**(b) The `apply` report**, once:

```markdown
## <solution> / <role>: <PASS|FAIL>
Profile: references/profiles/<solution>.yaml (confirmed; decisions updated) · targets [1, 2, 3]
Package: <out-dir>/package.json · report: <out-dir>/report.md · diff: <out-dir>/package.diff

**Coverage:** <n> YAML keys → <p> fields, <r> removed by you (balanced: yes) · read-back: PASS · <c> coerced · <s> secrets withheld

**Changes:** <component>: +<added> / ~<changed> / <package-only> kept … · restyle <accepted|refused>

**Your decisions:** <removed keys with reasons> · suggestions <accepted>/<rejected> · provenance <keep|dev-mode>
```

Summarise from `report.md` and link it; don't paste every placed row. Then ask for the canonical-copy OK.

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
| 1. Package creation | PASS/FAIL | `<out-dir>/preview.md`, `report.md`, `package.diff` | n |
| 2. UI building | PASS/FAIL/BLOCKED | contract test, `ui-check.json`, screenshots, round-trip | n |
| 3. Solution via SSH | PASS/FAIL (real host / stand-in, mocked broker) | plan, verify, smoke report | n |

A stage that was not run says "not run (awaiting go-ahead)".

## Scripts

| script | does |
|---|---|
| `scripts/yaml_to_package.py` | engine: `preview` (every field, the questions) and `apply` (package, report, diff, merged profile, read-back) |
| `scripts/profile_proposer.py` | no-profile flow: structure, resemblance, draft profile, questions |
| `scripts/validate_package.py` | schema + structural gates; usable on any package |
| `scripts/gen_contract_test.py` | Stage 2: generates the vitest contract test for a package + profile |
| `scripts/run_frontend.sh` | Stage 2: runs vitest, the dev server, Playwright and `ui-check` in Docker |
| `scripts/ui_check.mjs` | Stage 2: headless browser check of the loaded package |
| `scripts/render_config.py` | renders a role's config (secrets as `${SM_SECRET_*}`); `roundtrip` compares it with the YAML |
| `scripts/deploy_ssh.sh` | Stage 3: `plan` (default, no network) / `apply` / `verify` / `teardown` over SSH |
| `scripts/smoke_test.py` | Stage 3: runs the profile's `smoke_test:` over SSH |
| `scripts/testing/` | the skill's own Stage 3 target: local sshd, stand-in MI image, mocked broker |
| `scripts/common.py` + `style.py`, `learn_samples.py`, `decisions.py`, `preview_md.py` | the engine's modules: YAML 1.2 loader and matching; style and restyle; learned suggestions; answers and the decisions block; the preview's markdown |

They need Python 3 with PyYAML. `jsonschema` is optional; without it, the schema gate is skipped and
the output says so. The schema is found by walking up to `contracts/solution-package.schema.json`;
outside the repo, pass `--schema`, or ask the user for the file.

`examples/` holds the File Mesh source and sink runs (inputs, output package, report, diff) to
compare against.
