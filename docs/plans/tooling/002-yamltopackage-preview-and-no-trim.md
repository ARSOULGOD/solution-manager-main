# solution-manager-yamlToPackage: preview before create, no trimming, sample-learned style

> **Status: APPROVED 2026-11-10, NOT YET EXECUTED.** Brainstormed and approved in chat on 2026-11-10, section by
> section. The user approved the written design without changes and skipped the independent adversarial review.
>
> **Execution (the user's choices, 2026-11-10):**
> - a fresh session, using `superpowers:subagent-driven-development`, with one subagent per task;
> - a commit after each task, on branch `feat/yamltopackage-preview`, created from `feature/agent-mi-config-view`.

**Goal:** change Stage 1 (package creation) of `.claude/skills/solution-manager-yamlToPackage/` so that:

1. no field that is present in the YAML is ever dropped by the skill;
2. before `package.json` is created, the user sees the whole mapping ("the experience of the YAML"): every field,
   its priority (section, position, up front or Advanced, required or optional), its role and its versions;
3. the package follows the best practices of the sample packages;
4. the user is asked for amendments, and the package is created only after the user says so.

**This is a dev/test tool, not product code.** Nothing here changes `frontend/src`, `backend/` or `contracts/`. No
`REQ-*` traces to it, as with plan 001.

## Decisions (answered by the user, 2026-11-10)

Plan-local IDs: `Q<n>` is the clarifying question the decision answers, `A1` the approach. They are deliberately not
`D<n>`, which is the parent-architecture family (`I3`).

| ID | question | decision | rationale |
|---|---|---|---|
| Q1 | What does "priority" mean? | Display position (`ui.section`, `ui.order`, up front or `ui.advanced`) **and** required or optional (`required`) | user's choice |
| Q2 | How strict is "never drop a field"? | The skill never drops or holds back a YAML key. **Only the user** may remove one, at review, with a reason recorded in the profile. The rule covers **fields only**; values keep today's rules | Secret values cannot be kept (`I6`). A "YAML value always wins" rule would make one shared field flip between roles (`server.port` 8092/8093) and force per-role fields |
| Q3 | Two keys that differ only in case, `-` or `_` | The preview asks which spelling to keep; the other is recorded as removed by the user | The skill's validator rejects case-only duplicates; keeping both would show two fields for what looks like one setting |
| Q4 | Style where the samples disagree | db-designer style: `ui.order` = position within the section; helper text only where it adds something (a YAML comment); `secret: true` beside `type: secret` | The samples README calls db-designer the authoring reference; a stock sentence only repeats the label; `secret: true` changes console behaviour (finding 3) |
| Q5 | An existing package in another style | Restyled on its next run, shown as its own preview block, refusable | One style across every package the skill writes |
| Q6 | Starting values of `required` and `advanced` | Suggested by the skill, learned from the samples; every suggestion needs the user's acceptance | user: "the final decision would be of the user" |
| Q7 | How the skill learns | On every run, from the repo's `frontend/contracts/packages/`; `--samples <folder>` points it elsewhere | Works on every clone; the user's folder is untracked |
| Q8 | Where decisions are remembered | In the mapping profile | One place; it already holds FM-D1 to FM-D11 |
| Q9 | Suggestion rule, given the measured data (finding 6) | Strict learning only | The only option that is purely learned from the samples; its weak suggestions are few and easy to reject |
| A1 | Implementation approach | Separate `preview` and `apply` commands with an answers file; decisions merged into the profile on `apply` | The only approach in which nothing is created or changed until the user says "create" |

**What this supersedes.** Each superseded entry is updated to say so when the change is made.

- **The rule "never invent `required`"** (SKILL.md "Rules that are easy to get wrong"; `mapping-guide.md` engine rule 7)
  is narrowed: `required` and `ui.advanced` may now be *suggested* from sample evidence, but are never applied without
  the user's acceptance. `when`, `discriminator`, `pattern`, `min`/`max`, enum options and defaults stay never-invented.
- **The EXCLUDED bucket and the profile's `exclude` key** (`mapping-guide.md` engine rule 8 and "Profile format") are
  replaced by "removed by you".
- **FM-D1's resolution** ("exclude the lowercase key") is replaced: the next preview asks which spelling to keep (Q3).
- **FM-D5's resolution** (`ui_order: running`) is replaced: db-designer numbering after the restyle (Q5), or the
  package's detected style if the restyle is refused.
- **`--update-shared` and `--provenance`** are replaced by preview answers.

## Findings that shaped the design (verified 2026-11-10)

1. **What is dropped today.** The engine was run with the current profile against
   `frontend/contracts/packages/file-mesh/package.json`, targets 1,2,3:
   - **source:** 110 keys → 109 fields, 1 excluded (FM-D1);
   - **sink:** 102 keys → 102 fields;
   - **values not used:** 6 secret values per role (`I6`), plus shared defaults where the package value wins (7 for
     source, 8 for sink).

   The other hold-back reasons (a list with no rule, a decimal, an empty map, an unmatched key, a new key in a shared
   component, and so on) appear with other YAMLs.
2. **The samples.** The user's folder (`solution-manager-dev-instance-data-flow-visualization/`, untracked) equals the
   repo's `frontend/contracts/packages/` except for two files:
   - `file-mesh/package.json`: 4 lines (two broker labels, and `valueFrom.attribute` `tcpUrl` instead of `host`);
   - `file-mesh/package.test.ts`: a newer version of the test.

   Neither affects learning, because the skill reads `package.json` files only and never learns from the package it
   is generating. The samples README calls `db-designer` "the canonical hypothetical authoring reference" and says
   `cache`'s "legacy presentation metadata is not an upload authoring example".
3. **`secret: true` matters, not just as style.** Every hand-written sample writes it beside `type: secret`.
   - The console's copy of `project()` keeps the value out of the view (`secretUnset`) only when `secret: true` is set
     (`frontend/contracts/view-model/project.ts:131`, `:371`).
   - The canonical engine accepts either marker (`contracts/view-models/src/project.ts:67`).
   - The skill's File Mesh output has 8 secret fields, none with the flag.
4. **The three `ui.order` styles look identical on screen.** `cache` numbers by section index, File Mesh by one running
   counter, and db-designer and the other samples by position within the section.
   - **The console's copy** orders sections by their first field's `ui.order`, with a stable sort, and keeps fields
     in file order (`frontend/contracts/view-model/project.ts:483`, `:608`).
   - **The canonical engine** orders sections by first appearance, and fields by `ui.order` and then file order
     (`contracts/view-models/src/project.ts:347`, `:496`, `:504`).

   All three styles number in file order, so both copies render file order. The skill's
   `references/package-schema.md` (section "ui.order") wrongly says the hand-written samples use the section index.
5. **How big the File Mesh restyle is.** 156 fields in 10 components. `ui.order` changes on 138 of them. 154 carry
   exactly the stock helper sentence (none with a comment appended), and 2 carry custom text. No frontend test asserts
   File Mesh's helper text or order.
6. **Learning is thin for File Mesh.** A throwaway prototype (not kept) indexed 103 distinct non-virtual sample fields
   (reused components counted once, File Mesh excluded) and tried three rules per role:
   - **Strict (same last-segment words, majority):** 4 required, 0 advanced. `solace.java.host` and
     `solace.connector.management.session.host` come from cache's `solace.host`; `spring.application.name` and
     `logging.file.name` come from a notification template's and a job's `NAME`.
   - **Loose (any single "signal word"):** 8 to 10 required, 0 advanced, mostly noise.
   - **A hand-written pattern list:** 18 to 19 required, 3 to 4 advanced, about half of the required ones wrong (SFTP
     and FTP port and password, `strictHostKeyChecking`, `server.ssl.key-store-password`).
7. **Stale artifacts.** Eval `file-mesh-sink-flags-consumer` still expects three held-back
   `spring.cloud.stream.solace.default.consumer.*` keys that FM-D2 resolved on 2026-11-10. The `examples/file-mesh-*`
   reports predate FM-D2 and FM-D11.
8. **Downstream readers.**
   - `render_config.py` takes its allowed round-trip differences from `report.json`'s `excluded` list (`:132`).
   - `ui_check.mjs` orders sections by their first field's `ui.order` with a stable sort (`:59`-`:63`), so db-designer
     numbering passes.

## Global constraints

- **`I6`.** Secret values never appear in the package, the preview, the answers file, the profile's decisions, the
  reports or the logs. An amendment that sets a default on a secret field is refused.
- **`I8`.** No new dependency: Python's standard library and PyYAML (MIT) only. Tests use `unittest`.
- **`I1`.** Nothing here asks anything of an MI.
- **No UI code changes.** The console must render any valid package (plan 001's rule).
- **Contracts untouched.** The skill reads `contracts/solution-package.schema.json` and never edits it.
- **The engine never overwrites its inputs** (YAML, package, profile). It writes only to `--out-dir`, and the skill
  copies files into place at the approved steps.
- **No commits** unless the user asks.

## Design

### 1. Stage 1 flow and what the preview shows (approved)

**Flow** for a solution with a confirmed profile. A solution with no profile still goes through the profile proposal
(flow B) unchanged, then continues here.

1. **Inputs, as today:** solution and role, the YAML, the existing package, the target versions (asked every time), and
   `appliesTo` for a new package. New: the skill says which samples folder it will learn from.
2. **`preview`:** the skill shows the preview in chat. The same content, plus every suggestion's full evidence, is saved
   to `<out-dir>/preview.md` (for people) and `<out-dir>/preview.json` (for the skill).
3. **Answers:** the user answers the numbered questions by number and may amend anything: a label, section, position,
   advanced, required, type, enum options, `when`, `pattern`, `min`/`max`, helper text, or removing a key with a reason.
   The skill records the replies in `<out-dir>/answers.yaml` and re-runs `preview`, showing only what changed and what
   is still open. This repeats until nothing is open.
4. **The skill asks "Create package.json? (yes/no)".** Until this point only scratch files exist.
5. **`apply`:** writes `package.json`, `package.diff`, `report.md`, `report.json` and the updated `profile.yaml` to
   `<out-dir>`. The skill copies the profile into `references/profiles/` and shows the validation verdict.
6. **Unchanged:** copying the package over `frontend/contracts/packages/<solution>/package.json` still needs the user's
   separate OK. Then Stage 2 is offered.

**The preview lists every field on the first run.** The user asked for the fields to be shown explicitly. Re-runs
show only the changes. Layout, illustrated with File Mesh source (line numbers and positions are illustrative):

```
## file-mesh / source: PREVIEW (nothing created yet)
YAML: application-source-file-v2.yml, 110 keys · profile: file-mesh (confirmed)
Package: frontend/contracts/packages/file-mesh/package.json · packageVersion [1,2,3,4] · appliesTo ">=1.0 <2.0"
Roles: source → file-source (one-of-many) · shared (none): server, spring-boot-admin, application, solace, logging, management
Versions: this YAML targets [1,2,3]; every field it feeds carries [1,2,3]
Samples: frontend/contracts/packages: 8 packages, 103 distinct fields (file-mesh itself not used)
Keys: 110 → 108 placed · 0 removed by you · 2 open

### file-source (role source): 49 fields
| YAML key (line)                      | type   | default             | section · pos   | shown    | required                | notes                |
| file.source.general.adapter_id (L35) | string | "fm-file-source"    | General · 0     | up front | no                      | was "SOURCE_TEST_V1" |
| file.source.sftp_settings.password   | secret | withheld (I6)       | SFTP · 3        | up front | no                      | secret: true added   |
### server (shared): 6 fields
| server.port (L4)                     | int    | 8094 (package kept) | Server · 0      | up front | no                      | YAML says 8092       |
### solace (shared): 22 fields
| solace.java.host (L124)              | string | from connection     | Java Client · 0 | up front | no → suggested yes (Q2) | valueFrom            |

### Questions (answer by number)
1. Spelling: useOutputDestinationSolaceCredentials (L96) vs useoutputdestinationsolacecredentials (L97). Keep which? Reason for removing the other?
2. Suggested required: solace.java.host, like cache/cache-engine/solace.host (required). Accept or reject?
5. Restyle to db-designer style: new positions on 138 of 156 fields, stock helper text removed from 154, 2 custom kept. Accept or refuse?
6. Signature: the package is signed and will change. Keep it, or switch to dev-mode?

### For your information
- 7 shared defaults differ (package kept) · 6 values coerced · secret: true added to 8 fields
### Profile changes saved on "create": none yet
```

### 2. Engine rules (approved)

**Three outcomes replace MAPPED / FLAGGED / EXCLUDED.** Every YAML key (every document) ends up as exactly one of:

- **placed:** it becomes a field;
- **open:** it is a question in the preview;
- **removed by you:** the user's recorded decision, with date and reason.

**`apply` refuses while anything is open** (exit 5).

**The no-trim gate is a read-back, not a count.** After writing, `apply` reads the package back and checks two
things:
- every placed key is a field of the component it was placed in;
- every key removed by the user is absent from that component.

Removing a key also removes its field if the package already has one, and the preview shows that as a removal. Any
mismatch fails the run (exit 1).

**Every reason a key is held back today becomes a question with fixed choices.** "Remove it (you), with a reason" is
always one of the choices.

| held back today because… | the preview offers |
|---|---|
| no component claims it (including a key outside a relative `configPath`) | an existing component · a new one (the user gives its type, role, cardinality and `configPath`) |
| a list with no `lists` rule | a `list` field (default left empty: the schema allows only scalar defaults) · one text field joined with `,` · one field per item |
| an empty map | a `map` field |
| a decimal value | a `string` field keeping the text (the schema has no decimal type) |
| an enum value not in the options | add it to the options · make the field `string` |
| two keys differing only in case, `-` or `_` | keep one spelling; the other is removed by you (both keys stay open until the user picks) |
| a new key in an existing shared component | add it for every role · for this role only (field `role`) · move it to the role component |
| found in another role's YAML (a `role_exclusive` rule or another role's component) | place it in this role's component |
| in YAML document 2 or later | an existing component · a new one (as above) |
| type conflicts with the package's declaration | keep the package's type · use the YAML's type |
| the same key written twice in one YAML mapping | use the first value · use the second (the field exists either way) |

- **Also blocking:**
  - the restyle block;
  - every suggestion;
  - the signature question, asked only when a signed package would change.
- **Not blocking**, listed "for your information":
  - shared defaults that differ;
  - coerced values;
  - reviewed labels kept;
  - fields in the package but not in the YAML (kept unless the user says remove).
- **Old `exclude` entries become questions:** "the profile used to drop this key because …; keep it or remove it?". An
  exclusion now counts only as the user's decision. FM-D1 is the only one today.

**`answers.yaml`** is written by the skill from the user's chat replies:

```yaml
remove:      [{key: file.source.command_center.useoutputdestinationsolacecredentials,
               reason: "case variant; kept useOutputDestinationSolaceCredentials"}]
place:       [{key: some.key, component: file-source}]
store:       [{key: some.list, as: join-comma}]
suggestions: [{key: solace.java.host, attribute: required, decision: accept}]
amend:       [{key: file.source.general.adapter_id, set: {required: true, ui.label: "Adapter ID"}}]
restyle: accept
provenance: dev-mode
```

`preview` validates the file. These are reported (exit 2), never silently ignored:
- a key that isn't in the YAML;
- a choice that wasn't offered;
- a secret default in `amend`.

**The profile's decisions block.** On `apply`, the answers are merged into a `decisions:` block at the end of the
profile, between two marker comments:

```yaml
# >>> decisions: written by the skill from your preview answers; do not edit by hand >>>
decisions:
  removed:     [{key, role, date, reason}]
  placed:      [{key, component, role, date}]
  stored:      [{key, as, date}]
  suggestions: [{component, field, attribute, decision, date, evidence}]
  amendments:  [{component, field, set, date, reason}]
  style:       {restyle: accepted | refused, date}
  provenance:  {choice, date}
# <<< decisions <<<
```

- **The hand-written part stays byte-for-byte the same.** Everything above the markers is untouched; only the block
  is regenerated, because PyYAML cannot round-trip comments.
- **Scope:** a decision on a shared key applies to every role; a decision on a role-component key carries its role.
- **Next run:** remembered decisions are applied first, so only new keys, new suggestions or changed situations are
  asked.
- **Two profile settings go away:** `exclude` (replaced by "removed by you") and `ui_order` (there is one style now;
  a package whose restyle was refused keeps its detected style).

**Exit codes:**

| code | meaning |
|---|---|
| 0 | done (`apply`) or nothing open (`preview`) |
| 1 | validation or read-back failed |
| 2 | bad input, including a bad answers file |
| 3 | draft profile |
| 4 | version decision needed |
| **5** | **questions still open** |

**Removed:**
- **The one-shot run.** No package can be created without a preview.
- **`--update-shared` and `--provenance`.** Both are now preview answers.

**Unchanged:**
- the YAML 1.2 loader and type inference;
- secret withholding;
- the reviewed-label merge rules (`preserve_reviewed_ui`);
- version nesting;
- relocation;
- the validation gates;
- the standing note on defaults versus `docs/design/dynamic-ui/design.md` §7.3 (`UI-D*`, PROPOSED; flagged, not
  resolved).

### 3. Style, restyle and learned suggestions (approved)

**Style for every field the skill writes (db-designer):**

- **`ui.order`:** the field's position inside its section, counting from 0 in file order.
- **`ui.helperText`:** only when the YAML line has a comment of four or more words (today's threshold), and then the
  comment alone, with no stock sentence.
- **Secrets:** `type: secret` always comes with `secret: true`, and the default stays empty (`I6`).
- **As today:** `version` on every component and field; `role` on every component (`none` for shared). Labels and
  sections are derived as today, and a reviewed label in the package still wins.
- **Not added by the skill:**
  - `loggerPrefix`, because it names the MI's Java package, which the YAML cannot tell. It comes from the profile only.
  - `actions` and `metrics`, which are never derived from YAML. Existing ones are kept.

**Restyle: one block in the preview, accepted or refused as a whole.**

- **Per component, the block lists** how many positions change and how many stock sentences are removed.
- **What counts as a stock sentence:** one that exactly equals the stock sentence for that field's current label,
  section and type. Anything else is kept (2 fields in File Mesh).
- **Coverage:** every component, including ones the profile marks `preserve` (File Mesh's `cache`), listed separately.
- **Not part of the block:** adding `secret: true` to existing secret fields. It is listed as a change wherever it
  applies (8 fields in File Mesh).
- **If refused, that is remembered.** The package keeps its own style, detected per component (running counter,
  section index, or position within section). New fields follow it, so one package never mixes two styles.

**Learned suggestions (strict):**

- **Samples:** every `package.json` under `frontend/contracts/packages/`, found by walking up from the working folder
  as the schema is found, or under `--samples <folder>`.
  - Skipped: the package of the solution being generated, and `virtual` fields.
  - A component reused across samples (`http-client`, `cache`) counts once, keyed by component type and field key.
  - Every other sample counts, `cache` included. The evidence always names the sample, so a suggestion that comes
    from `cache` is visible as such.
- **Match:** the YAML key's last segment and the sample field key's last segment, split into words (camelCase, `-`,
  `_`) and lower-cased, must be the same words in the same order. `solace.java.host` matches cache's `solace.host`;
  `sftp_settings.ip` matches nothing.
- **Suggestion:** `required` (or `ui.advanced`) is suggested when more than half of the matched sample fields carry it.
- **Evidence:** every matched sample field with its flag and its `when`, if any. For example: "http-client/PASSWORD:
  optional, only when AUTH_MODE = basic".
- **No suggestion** for a field that already has the attribute in the package, or for a key already decided.
- **The user's answer is final.** Accepted: the field gets the attribute. Rejected: remembered and not asked again,
  even if the samples change later.
- **Still never suggested:** `when`, `discriminator`, `pattern`, `min`/`max`, enum options and defaults. The user can
  add any of them as an amendment.
- **No samples found:** the preview says so and shows no suggestions; it does not fail.

### 4. Other changes and verification (approved)

**Scripts** (`.claude/skills/solution-manager-yamlToPackage/scripts/`):

- **`yaml_to_package.py`** gets the `preview` and `apply` commands, the three outcomes, the questions and the read-back
  gate. To keep files focused, three new modules sit beside it:
  - `learn_samples.py`: the sample index and strict matching;
  - `decisions.py`: validating `answers.yaml` and merging the profile block;
  - `style.py`: positions, helper text and restyle detection.
- **`render_config.py roundtrip`** takes its allowed differences from "removed by you" instead of `excluded`/`flagged`.
- **`profile_proposer.py`** drafts profiles without `exclude` or `ui_order`, with an empty decisions block. Its wording
  changes from "would be FLAGGED" to "asked in the preview".
- **`validate_package.py`** warns about `type: secret` without `secret: true`.
- **`gen_contract_test.py` and `ui_check.mjs`** need no change (finding 8). Verification confirms it.

**Skill text and the File Mesh profile:**

- **`SKILL.md`:** Stage 1 is rewritten for the preview → answers → create loop. The file stays under about 300 lines.
- **`references/mapping-guide.md`:** the three outcomes, the questions table, the style, the learning rule and the new
  profile format.
- **`references/package-schema.md`:** fixes the section-index claim (finding 4), and documents the style and
  `secret: true`.
- **`references/profiles/file-mesh.yaml`:** loses `exclude` and `ui_order: running` (FM-D1 becomes a question on the
  next run), gets updated FM-D1 and FM-D5 notes that point here, and gains the empty decisions block.

**Evals and examples:**

- **`file-mesh-source-regenerate`** is rewritten. It now expects:
  - a stop at the preview, with no `package.json` created;
  - FM-D1 asked as a spelling question;
  - 4 suggestions with evidence;
  - the restyle block;
  - the value rules unchanged (`adapter_id`'s default change shown, secrets withheld).
- **`file-mesh-sink-flags-consumer`** is rewritten as a sink preview case. It is already out of date (finding 7).
- **A new `apply-after-answers` case.** Given answers, every key becomes a field or is removed by you, and the
  profile's hand-written part is unchanged.
- **`no-profile-stops-and-asks`, `ui-stage-file-mesh` and `ssh-dry-run-placeholder`** get wording updates only.
- **`examples/file-mesh-source` and `examples/file-mesh-sink`** are regenerated with the new artifacts: preview,
  answers, package, report, diff and the profile change. The Stage 2 and Stage 3 examples stay as plan 001's evidence.

**Verification:**

1. **Engine tests** in `scripts/tests/` (`unittest`, small fixture YAMLs, profiles and a fixture samples folder). They
   cover:
   - `preview` never writes `package.json`;
   - `apply` refuses with open questions (exit 5);
   - the read-back gate, including a removed key that the package already had;
   - every held-back reason produces its question and choices;
   - an old `exclude` entry becomes a question;
   - bad answers are rejected (exit 2), including a secret default;
   - the profile's hand-written part stays byte-identical, tested on the commented File Mesh profile;
   - the style rules;
   - the restyle rules (custom helper text kept; a refused restyle continued in the detected style);
   - strict learning (majority rule, reused components counted once, the target's own package skipped, evidence
     text);
   - the value rules unchanged (secrets withheld, shared defaults kept, list join).
2. **End to end on File Mesh**, source then sink, with scripted answers:
   - every key becomes a field or is removed by you;
   - both validators pass: the skill's `validate_package.py`, and
     `python3 contracts/validation/validate-package.py --dev-mode`;
   - the diff against today's output shows only the restyle, `secret: true`, the accepted suggestions and the FM-D1
     decision;
   - no secret value from either YAML appears in any artifact (checked with grep).
3. **Stage 2 regression** on the restyled package: the generated contract test, `ui-check` for both roles, and the
   round-trip, in the `node:22` container. Without Docker these are reported as BLOCKED, never as passed.
4. **The updated eval cases are run with the skill.** Baselines are not re-run unless the user asks.

## Limits (what this design does not do)

- **Suggestions are thin for File Mesh** (4 required, 0 advanced per role today) until samples closer to File Mesh
  exist. Most of its required and advanced choices are the user's, made once and then remembered.
- **Learning matches field names only.** It cannot infer a condition (`when`): a suggestion copied from a conditional
  sample field shows that condition for the user to judge, and does not reproduce it.
- **A rejected suggestion is never re-asked,** even if the samples change.
- **A `list` or `map` field cannot carry its YAML value as a default** (the schema allows scalar defaults only). The
  value survives only if the user picks "joined with `,`" or "one field per item".
- **A decimal can only become a `string` field.**
- **The preview is markdown,** in chat and in `preview.md`. It is not an interactive form.
- **The restyle is all or nothing per package,** not per component.
- **Learning reads `package.json` files only.** A sample's README notes or tests are not evidence.

## Routed, not designed here

- **The `secret` difference between the two `project()` copies** (finding 3) is reported to the frontend owners. The
  skill only writes `secret: true`.
- **The newer `file-mesh` files in the user's samples folder** (finding 2) reach the repo through the frontend, not
  through this skill.
- **Plan 001's open item** (the `DECISIONS.md` note on defaults versus `dynamic-ui/design.md` §7.3) is unchanged and
  still open.

## Review focus

- **The no-trim gate** must read the written package back, not just count buckets.
- **The profile merge** must leave the hand-written text byte-identical. File Mesh's profile is heavily commented.
- **`answers.yaml` validation** must fail loudly. Nothing may be silently dropped.
- **Secret values:** grep every artifact (`preview.md`, `preview.json`, `answers.yaml`, the profile, the report, the
  diff) for the YAMLs' secret values.
- **The restyle:** stock-sentence detection is exact, custom helper text is untouched, and a refused restyle continues
  the detected style.
- **Learning:** the target's own package is excluded, reused components are counted once, and evidence is printed for
  every suggestion.

## Tasks

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stage 1 previews every YAML key's field, asks the open questions, and creates `package.json` only after
"create". It never drops a key, writes the db-designer style, and offers `required`/`advanced` suggestions learned from
the samples.

**Architecture:** `yaml_to_package.py` keeps one in-memory build (the `Run` class). It sorts every YAML key into
placed, open or removed-by-you and assembles the would-be package. `preview` renders that build and `apply` writes it,
so the preview always shows exactly what `apply` writes. The separable logic lives in `style.py`, `learn_samples.py`,
`decisions.py` and `preview_md.py`. Decisions persist in a marker-delimited block at the end of the profile.

**Tech Stack:** Python 3.12 standard library, PyYAML 6.0.1 and `unittest`; the existing `node:22` Docker runner for
the Stage 2 regression.

**Spec:** the "Design" section above, in this file. Executors read both.

### Global constraints (every task)

- **Everything under "## Global constraints" above.**
- **Scope of edits:** only `.claude/skills/solution-manager-yamlToPackage/` and this plan. `references/profiles/file-mesh.yaml`
  changes only in Task 10, and only its format.
- **Verification uses scratch copies of the File Mesh profile.** Example answers never reach `references/profiles/`;
  they are not the user's decisions.
- **Commit steps run only if the user has asked for commits.** Otherwise leave the change uncommitted and tick the step.
- **How to run the tests:** run all of them with
  `cd .claude/skills/solution-manager-yamlToPackage && python3 -m unittest discover -s scripts/tests -v`. For one file,
  add `-p test_<name>.py`.
- **Imports:** test modules import the scripts by name, after `sys.path.insert(0, str(Path(__file__).resolve().parents[1]))`.
- **Dates** in decisions come from `--today YYYY-MM-DD` (default: today), so tests and examples are deterministic.

### Review focus

Each line has its test in the named task.

1. **A sink run after a source run.** A remembered source-role decision must not remove or question the sink's own
   copy of a key that both role components claim. It must not show as "not in this YAML" in the sink run either.
   → Task 4, `test_role_decisions_stay_with_their_role`.
2. **A first package (no `--package`).** There is no restyle or signature question, every field is in db-designer
   style, and `--applies-to` is required. → Task 6, `test_first_package_has_no_restyle_or_signature_question`;
   Task 8, `test_apply_without_package_requires_applies_to`.
3. **A secret's YAML comment that repeats the secret value** must never become `helperText` (`I6`). → Task 1,
   `test_new_helper_never_repeats_a_secret_value`.
4. **A profile with no decisions markers and no final newline.** The merged profile starts with the original bytes
   exactly. → Task 3, `test_block_appended_without_markers_or_final_newline`.
5. **A samples folder holding an invalid `package.json`, or one without `components`.** It is skipped with a note,
   never a crash. → Task 2, `test_unreadable_samples_are_noted`.

### Files

| file | change | responsibility |
|---|---|---|
| `scripts/style.py` | create | `ui.order` styles, helper text, restyle, `secret: true` |
| `scripts/learn_samples.py` | create | sample index; strict suggestions with evidence |
| `scripts/decisions.py` | create | the profile's decisions block, the answers file, effective rules, the dated merge |
| `scripts/preview_md.py` | create | preview model → markdown |
| `scripts/yaml_to_package.py` | modify | the build (`Run`), `preview` and `apply`, the read-back gate, the masked diff, the report |
| `scripts/common.py` | modify | `load_yaml_leaves` keeps each duplicate key's value |
| `scripts/render_config.py`, `scripts/profile_proposer.py`, `scripts/validate_package.py` | modify | downstream readers (Task 9) |
| `scripts/tests/` | create | `unittest` suites, `helpers.py`, fixtures |
| `SKILL.md`, `references/*.md`, `references/profiles/file-mesh.yaml` | modify | Task 10 |
| `evals/`, `examples/file-mesh-source/`, `examples/file-mesh-sink/` | modify | Tasks 11 and 12 |

### Test fixtures

Each fixture is created by the task that first needs it.

**`scripts/tests/fixtures/samples/`** (Task 2). Each valid sample also carries `packageVersion`, `appliesTo` and a
minimal `ui`; schema validity is not tested here.

| file | content |
|---|---|
| `one/package.json` | solution `one`. Component `svc`: `USERNAME` (`required: true`), `HOST` (`required: true`), `TIMEOUT` (`ui.advanced: true`), `REDIS_URL` (`required: true`, `when: {CACHE_PROVIDER: redis}`). Component `client`: `PASSWORD` (`type: secret`, `secret: true`, `required: false`, `when: {AUTH_MODE: basic}`) |
| `two/package.json` | solution `two`. Component `svc2`: `username` (`required: true`), `host` (no `required`), `timeout` (no `advanced`), `password` (`virtual: true`, `required: true`). Component `client`: `PASSWORD` (`required: true`) |
| `demo/package.json` | solution `demo`. Component `x`: `host` (`required: true`) |
| `broken/package.json` | the text `{not json` |
| `nocomp/package.json` | `{"solution": "nocomp"}` |

**`scripts/tests/fixtures/demo/`** (Task 4; reused by Tasks 5 to 9).

`profile.yaml`:
```yaml
# Demo profile for the engine tests. These comments must survive a decisions merge byte-for-byte.
solution: demo
status: confirmed
roles: [alpha, beta]
key_style: full-dotted
preserve_reviewed_ui: true
explicit_versions: true
components:
  - {type: alpha-core, role: alpha, cardinality: one-of-many, configPath: "", match: [demo.alpha, demo.shared]}
  - {type: beta-core, role: beta, cardinality: one-of-many, configPath: "", match: [demo.beta, demo.shared]}
  - {type: server, role: none, shared: true, cardinality: zero-or-more, configPath: "", match: [server]}
enums: {demo.alpha.mode: [fast, slow]}
exclude:
  - {match: demo.alpha.oldname, reason: "legacy duplicate"}
defaults: {role_components: yaml, shared_components: package}
```

`alpha.yml` has 15 keys: 14 in document 1 and `late` in document 2. `demo.alpha.host` is written twice.
```yaml
server:
  port: 8080
  address: 127.0.0.1
  ssl:
    key-store-password: hunter2pass   # the keystore password is hunter2pass for local runs
demo:
  alpha:
    host: first.example.invalid
    username: bob
    mode: medium
    retries: [1, 2, 3]
    ratio: 0.5
    extras: {}
    fileName: a.txt
    filename: b.txt
    oldname: x
    host: second.example.invalid
  shared:
    binding: in
  unknown:
    thing: 1
---
late: 1
```

`beta.yml` has 4 keys:
```yaml
server:
  port: 8081
demo:
  beta:
    host: beta.example.invalid
  shared:
    binding: out
  alpha:
    leak: 1
```

`package.json` has:
- `solution: demo`, `packageVersion: [1, 2]`, `appliesTo: ">=1.0 <2.0"`;
- `provenance: {author: solace, signature: test-signature}`, `authoring: {reviewedRows: 6, totalRows: 6}`;
- the two components below. Each component and field carries `version: [1, 2]`, and every `helperText` is the stock
  sentence except where noted.

| component | field | type · default | ui: section · order · label |
|---|---|---|---|
| `alpha-core` (role alpha, one-of-many, `configPath ""`) | `demo.alpha.host` | string · `"old.example.invalid"` | Alpha · 5 · "Alpha host" |
| | `demo.alpha.mode` | enum `[fast, slow]` · `"fast"` | Alpha · 6 · "Mode" |
| | `demo.alpha.legacy` | string · `"keep-me"` | Legacy · 7 · "Legacy"; helperText `"Hand-written help."` |
| `server` (role none, zero-or-more, `configPath ""`) | `server.port` | **string** · `"8094"` | Server · 0 · "Server port" |
| | `server.address` | string · `"0.0.0.0"` | Server · 1 · "Server address" |
| | `server.ssl.trust-store-password` | secret · `"oldsecret1"` | SSL · 2 · "SSL trust store password" |

`answers-alpha.yaml` (Task 7) answers every alpha question, given `--samples fixtures/samples`:
```yaml
remove:      [{key: demo.alpha.filename, reason: "case variant; kept fileName"}]
place:       [{key: demo.unknown.thing, component: alpha-core},
              {key: demo.alpha.oldname, component: alpha-core},
              {key: late, component: late, new: {role: none, cardinality: zero-or-more, configPath: ""}},
              {key: server.ssl.key-store-password, component: server, scope: this-role}]
store:       [{key: demo.alpha.retries, as: index}, {key: demo.alpha.ratio, as: string},
              {key: demo.alpha.extras, as: map}, {key: demo.alpha.mode, as: add-option},
              {key: server.port, as: package-type}, {key: demo.alpha.host, as: second}]
suggestions: [{key: demo.alpha.username, attribute: required, decision: accept}]
restyle: accept
provenance: dev-mode
```

**What the alpha preview raises.** With `--package fixtures/demo/package.json --samples fixtures/samples` and no
answers, the alpha preview has exactly 14 questions:

| kind | key |
|---|---|
| spelling | `fileName` / `filename` |
| no-component | `demo.unknown.thing` |
| excluded | `demo.alpha.oldname` |
| doc2 | `late` |
| shared-new | `server.ssl.key-store-password` |
| list | `retries` |
| decimal | `ratio` |
| empty-map | `extras` |
| enum-value | `mode` |
| type-conflict | `server.port` |
| dup-key | `demo.alpha.host` |
| suggest | `alpha-core/demo.alpha.username/required` |
| restyle | (whole package) |
| provenance | (whole package) |

Keys: 15 → 3 placed (`server.address`, `demo.alpha.username`, `demo.shared.binding`), 12 open, 0 removed.

**`scripts/tests/helpers.py`** (Task 4; extended in Task 7):
- constants: `FIXTURES`, `SAMPLES`, `DEMO`, `PROFILE`, `ALPHA`, `BETA`, `PACKAGE`, `ANSWERS_FILE`, `ENGINE`;
- `build_demo(role, answers=None, remembered=None, package=PACKAGE, applies_to=None, samples=SAMPLES) -> Run`. It
  copies the profile into a temp folder (adding `remembered` with `decisions.write_block`), writes `answers` to a temp
  `answers.yaml`, and calls `yaml_to_package.build([...])` with `--target-versions 1,2 --today 2026-11-10`;
- `fields_by_key(pkg) -> dict`;
- `temp_file(text: str) -> Path`, `empty_dir() -> Path`, and `package_copy(**top_level_overrides) -> Path` (a temp
  copy of `PACKAGE`);
- `run_cli(cmd, role, out, answers=None, package=PACKAGE) -> int`;
- `run_cli_err(...) -> tuple[int, str]`.

---

### Task 1: `style.py` (numbering, helper text, restyle, `secret: true`)

**Files:** Create `scripts/style.py`, `scripts/tests/test_style.py`.

**Interfaces:**
- Consumes: `common.HELPER_PATTERNS` and `common.helper_text` (unchanged).
- Produces:
  - **`detect_order_style(fields: list[dict]) -> str | None`.** The first style that fits, tried in this order:
    - `"within-section"`: in each section, file order reads 0, 1, 2… (a repeated number is allowed);
    - `"running"`: strictly increasing across the component;
    - `"section-index"`: equal inside a section, strictly increasing between sections.

    It returns `None` for no fields, and `"mixed"` otherwise.
  - **`detect_helper_style(fields: list[dict]) -> str`:** `"stock"` when every field's `ui.helperText` starts with its
    stock sentence, else `"sparse"`.
  - **`stock_helper(ftype: str, label: str, section: str) -> str`** and **`is_stock_helper(field: dict) -> bool`**
    (exact equality).
  - **`new_helper(ftype, label, section, comment: str | None, helper_style: str, secret_value=None) -> str | None`.**
    - `"sparse"`: the comment alone when it has 4 or more words (first letter upper-cased, a final `.` added), else
      `None`.
    - `"stock"`: `common.helper_text(ftype, label, section, comment)`.
    - A comment containing `str(secret_value)` is never used: `"sparse"` then returns `None`, and `"stock"` returns the
      bare stock sentence.
  - **`order_for_new(fields: list[dict], section: str, order_style: str) -> int`:** the `ui.order` of a field appended
    to the end of `section`.
  - **`restyle(component: dict) -> dict`:** returns `{"order_changed": int, "stock_removed": int, "custom_kept": int}`.
    It renumbers within each section and deletes stock helper text, in place.
  - **`mark_secrets(component: dict) -> list[str]`:** adds `secret: true` to each `type: secret` field that lacks it,
    and returns their keys.

- [ ] **Step 1: Write the failing tests** in `scripts/tests/test_style.py`. The helper `fields(*rows)` builds field
  dicts from `(section, order[, helperText])` rows, with type `"string"` and label `f"L{i}"`. `STOCK` in a row means
  "that field's own stock sentence".

```python
def test_detects_the_three_sample_styles(self):
    self.assertEqual(style.detect_order_style(fields(("A", 0), ("A", 0), ("B", 1), ("B", 1))), "section-index")
    self.assertEqual(style.detect_order_style(fields(("A", 5), ("A", 6), ("B", 7), ("B", 8))), "running")
    self.assertEqual(style.detect_order_style(fields(("C", 0), ("C", 1), ("C", 4), ("C", 4), ("P", 0), ("P", 1), ("M", 0))), "within-section")
    self.assertIsNone(style.detect_order_style([]))
    self.assertEqual(style.detect_order_style(fields(("A", 0), ("A", 2), ("A", 1))), "mixed")

def test_stock_helper_detection_is_exact(self):
    stock = "Enter the numeric value for “SFTP port” in the “SFTP” section."
    self.assertTrue(style.is_stock_helper(field("int", "SFTP port", "SFTP", stock)))
    self.assertFalse(style.is_stock_helper(field("int", "SFTP port", "SFTP", stock + " Extra.")))
    self.assertFalse(style.is_stock_helper(field("int", "Port", "SFTP", stock)))

def test_restyle_counts_and_keeps_custom(self):
    comp = {"fields": fields(("A", 5, STOCK), ("A", 6, "Custom."), ("B", 7, STOCK))}
    self.assertEqual(style.restyle(comp), {"order_changed": 3, "stock_removed": 2, "custom_kept": 1})
    self.assertEqual([f["ui"]["order"] for f in comp["fields"]], [0, 1, 0])
    self.assertEqual(comp["fields"][1]["ui"]["helperText"], "Custom.")

def test_new_helper_sparse_uses_the_comment_only(self):
    self.assertEqual(style.new_helper("string", "Name", "File", "base log file name", "sparse"), "Base log file name.")
    self.assertIsNone(style.new_helper("secret", "Password", "SSL", "or your password", "sparse"))

def test_new_helper_never_repeats_a_secret_value(self):  # review focus 3
    c = "the keystore password is hunter2pass for local runs"
    self.assertIsNone(style.new_helper("secret", "Key store password", "SSL", c, "sparse", "hunter2pass"))
    self.assertEqual(style.new_helper("secret", "Key store password", "SSL", c, "stock", "hunter2pass"),
                     style.stock_helper("secret", "Key store password", "SSL"))

def test_order_for_new_follows_each_style(self):
    w = fields(("A", 0), ("A", 1), ("B", 0))
    self.assertEqual(style.order_for_new(w, "A", "within-section"), 2)
    self.assertEqual(style.order_for_new(w, "Z", "within-section"), 0)
    self.assertEqual(style.order_for_new(fields(("A", 5), ("B", 6)), "A", "running"), 7)
    self.assertEqual(style.order_for_new(fields(("A", 0), ("B", 1)), "A", "section-index"), 0)
    self.assertEqual(style.order_for_new(fields(("A", 0), ("B", 1)), "Z", "section-index"), 2)

def test_mark_secrets(self):
    comp = {"fields": [{"key": "a", "type": "secret"}, {"key": "b", "type": "secret", "secret": True}, {"key": "c", "type": "string"}]}
    self.assertEqual(style.mark_secrets(comp), ["a"])
    self.assertTrue(all(f.get("secret") for f in comp["fields"] if f["type"] == "secret"))
```

- [ ] **Step 2: Run** `python3 -m unittest discover -s scripts/tests -p test_style.py -v`. Expected: ERROR,
  `ModuleNotFoundError: No module named 'style'`.
- [ ] **Step 3: Implement** `scripts/style.py` with the signatures above.
- [ ] **Step 4: Run** the same command. Expected: `Ran 7 tests`, `OK`.
- [ ] **Step 5: Commit** (if authorised): `git add scripts/style.py scripts/tests/test_style.py && git commit -m "feat(skills): yamlToPackage style module"`.

### Task 2: `learn_samples.py` (strict suggestions with evidence)

**Files:** Create `scripts/learn_samples.py`, `scripts/tests/test_learn_samples.py`, `scripts/tests/fixtures/samples/` (see Test fixtures).

**Interfaces:**
- Consumes: `common.split_words`.
- Produces:
  - **`words_of(segment: str) -> tuple[str, ...]`:** the lower-cased `common.split_words(segment)`.
  - **Dataclasses** (all `frozen=True`):
    - `SampleField(ref: str, words: tuple[str, ...], required: bool | None, advanced: bool, when: dict | None)`,
      where `ref = "<solution>/<component type>/<field key>"` and `words = words_of(key.split(".")[-1])`;
    - `Index(fields: tuple[SampleField, ...], packages: int, notes: tuple[str, ...], dir: str | None)`;
    - `Suggestion(attribute: str, support: int, matched: int, evidence: tuple[str, ...])`.
  - **`find_samples_dir(start: str) -> str | None`:** walks up from `start` to the first `frontend/contracts/packages`
    directory.
  - **`build_index(samples_dir: str | None, exclude_solution: str) -> Index`.** It reads `<dir>/*/package.json` in
    sorted order. It skips:
    - the package whose `solution` equals `exclude_solution`;
    - `virtual` fields;
    - any (component type, field key) already indexed.

    A file that fails to load or has no `components` list adds the note `"skipped <relative path>: <reason>"`.
    `packages` counts the packages used. When that count is 0 (including a `None` dir), it adds the note
    `"no samples found; no suggestions"`.
  - **`suggest(index: Index, segment: str) -> list[Suggestion]`.** It matches the fields whose
    `words == words_of(segment)`, and suggests:
    - `"required"` when `2 * count(required is True) > matched`;
    - `"advanced"` when `2 * count(advanced) > matched`.

    The evidence is one line per matched field:
    - for `required`: `"<ref>: required"`, `"<ref>: optional"` or `"<ref>: not marked"`, plus `", only when K = V"`
      (several conditions joined with `" and "`);
    - for `advanced`: `"<ref>: advanced"` or `"<ref>: up front"`.

- [ ] **Step 1: Write the failing tests** in `scripts/tests/test_learn_samples.py`:

```python
def setUp(self):
    self.ix = learn_samples.build_index(str(SAMPLES), "demo")

def test_index_skips_target_virtual_and_duplicates(self):
    self.assertEqual(self.ix.packages, 2)
    refs = [f.ref for f in self.ix.fields]
    self.assertEqual(len(refs), 8)
    self.assertFalse(any(r.startswith("demo/") for r in refs))
    self.assertIn("one/client/PASSWORD", refs)
    self.assertNotIn("two/client/PASSWORD", refs)
    self.assertNotIn("two/svc2/password", refs)

def test_unreadable_samples_are_noted(self):  # review focus 5
    notes = " ".join(self.ix.notes)
    self.assertIn("skipped broken/package.json", notes)
    self.assertIn("skipped nocomp/package.json", notes)

def test_majority_suggests_required_with_evidence(self):
    [s] = learn_samples.suggest(self.ix, "username")
    self.assertEqual((s.attribute, s.support, s.matched), ("required", 2, 2))
    self.assertIn("one/svc/USERNAME: required", s.evidence)

def test_split_vote_gives_nothing_and_the_target_is_excluded(self):
    self.assertEqual(learn_samples.suggest(self.ix, "host"), [])
    [s] = learn_samples.suggest(learn_samples.build_index(str(SAMPLES), "nobody"), "host")
    self.assertEqual((s.support, s.matched), (2, 3))

def test_evidence_shows_the_condition(self):
    [s] = learn_samples.suggest(self.ix, "redis_url")
    self.assertEqual(s.evidence, ("one/svc/REDIS_URL: required, only when CACHE_PROVIDER = redis",))

def test_no_suggestion_without_a_majority_or_a_match(self):
    for seg in ("timeout", "password", "ip"):
        self.assertEqual(learn_samples.suggest(self.ix, seg), [], seg)

def test_find_samples_dir_walks_up(self):
    # tempfile tree <tmp>/frontend/contracts/packages; start at <tmp>/a/b
    self.assertEqual(learn_samples.find_samples_dir(str(start)), str(packages_dir))

def test_no_samples_is_a_note(self):
    ix = learn_samples.build_index(None, "demo")
    self.assertEqual((ix.packages, ix.fields), (0, ()))
    self.assertIn("no samples found; no suggestions", ix.notes)
```

- [ ] **Step 2: Run** `python3 -m unittest discover -s scripts/tests -p test_learn_samples.py -v`. Expected: ERROR,
  `No module named 'learn_samples'`.
- [ ] **Step 3: Implement** `scripts/learn_samples.py` and the sample fixtures.
- [ ] **Step 4: Run** the same command. Expected: `Ran 8 tests`, `OK`.
- [ ] **Step 5: Commit** (if authorised): `feat(skills): yamlToPackage learns required/advanced from the samples`.

### Task 3: `decisions.py` (decisions block, answers, effective rules, merge)

**Files:** Create `scripts/decisions.py`, `scripts/tests/test_decisions.py`.

**Interfaces:**
- Consumes: `common.StrictLoader`.
- Produces:
  - **The markers:**
    - `BEGIN = "# >>> decisions: written by the skill from your preview answers; do not edit by hand >>>"`;
    - `END = "# <<< decisions <<<"`.
  - **`read_block(text: str) -> dict`:** the mapping under `decisions:` between the markers, or `{}` without markers.
    It raises `ValueError` when only one marker is present, or when the block is not `{decisions: <mapping>}`.
  - **`write_block(text: str, decisions: dict) -> str`.**
    - The block is `BEGIN + "\n" + yaml.safe_dump({"decisions": decisions}, sort_keys=False, allow_unicode=True, width=110) + END + "\n"`.
    - An existing block is replaced in place. Otherwise the block is appended, after a `"\n"` that is added only when
      `text` does not already end with one.
    - Text outside the block never changes.
  - **The vocabularies:**
    - `SECTIONS = ("remove", "place", "store", "suggestions", "amend", "restyle", "provenance", "forget")`;
    - `AMENDABLE = ("type", "default", "required", "discriminator", "when", "pattern", "min", "max", "options", "ui.label", "ui.section", "ui.order", "ui.advanced", "ui.helperText", "ui.readOnly")`.
  - **`load_answers(path: str | None) -> dict`:** `{}` for `None`. It raises `ValueError` for a non-mapping, or for a
    section that is not in `SECTIONS`.
  - **`validate_answers(answers: dict, ctx: dict) -> list[str]`.**
    - `ctx` holds: `yaml_keys: set[str]`; `open: dict[str, dict]` (id → item with `kind`, `key`, `choices`);
      `fields: dict[str, tuple[str, str]]` (placed YAML key → (component, field key));
      `package_fields: set[tuple[str, str]]`; `secrets: set[tuple[str, str]]`; `components: set[str]`;
      `remembered: dict`.
    - Each violation is one message, `"<section> <key or component/field>: <reason>"`.
    - Rules per section:

      | section | valid when |
      |---|---|
      | `remove` | `key` is in `yaml_keys`, or `component`+`field` is in `package_fields`; and `reason` is a non-empty string |
      | `place` | there is an open item for `key` of kind `no-component`, `wrong-role`, `doc2`, `excluded`, `shared-new` or `role-exclusive-shared`, or a remembered decision for it; `component` is in `components`, or `new: {role, cardinality, configPath}` gives one of the three cardinalities; `scope` (`all-roles` or `this-role`) appears only for the two shared kinds |
      | `store` | there is an open item for `key`, and `as` is in its `choices` |
      | `suggestions` | there is an open `suggest:<component>/<field>/<attribute>` item for `fields[key]`; `decision` is `accept` or `reject` |
      | `amend` | the target is a `key` in `yaml_keys`, or a `component`+`field` in `package_fields`; the keys of `set` are in `AMENDABLE`; there is no `default` on a secret, and any `default` is a scalar or null |
      | `restyle` | the `restyle` item is open; the value is `accept` or `refuse` |
      | `provenance` | the `provenance` item is open; the value is `keep` or `dev-mode` |
      | `forget` | it names a remembered entry, by `key` or by `component`+`field`+`attribute` |

  - **`effective(remembered: dict, answers: dict, role: str) -> dict`.** It returns:
    `{"removed": {key: reason}, "placed": {key: {"component", "scope"?, "new"?}}, "stored": {key: as}, "suggestions": {(component, field, attribute): decision}, "amendments": [...], "restyle": str | None, "provenance": str | None, "stale_candidates": [key]}`.
    - Remembered entries count when their `role` is `None` or equal to `role`.
    - An answer replaces the remembered entry for the same key, and `forget` drops it.
    - `stale_candidates` are the keys of remembered entries whose `role` equals `role`.
  - **`merge(remembered: dict, answers: dict, *, today: str, placements: dict[str, tuple[str, str, str | None]], evidence: dict[tuple[str, str, str], list[str]]) -> dict`.**
    It returns the new block content, with the spec's sections `removed`, `placed`, `stored`, `suggestions`,
    `amendments`, `style` and `provenance`.
    - Entries made from answers carry `date: today`.
    - Each entry's `role` comes from `placements[key][2]`: `None` for a shared key placed for all roles; the run's role
      for a role component, for `scope: this-role`, and for a key with no component.
    - An answer replaces the remembered entry for its key, and `forget` drops it.
    - Entry keys appear in the spec's order. Empty sections are omitted.

- [ ] **Step 1: Write the failing tests** in `scripts/tests/test_decisions.py`. `CTX` has:
  - `yaml_keys {"a.b", "a.list", "a.pw"}`;
  - `open {"list:a.list": {"kind": "list", "key": "a.list", "choices": ["list", "join-comma", "index", "remove"]}}`;
  - `fields {"a.pw": ("core", "a.pw")}`, `package_fields {("core", "a.pw")}`, `secrets {("core", "a.pw")}`;
  - `components {"core"}`, `remembered {}`.

```python
def test_block_round_trip_keeps_the_rest_byte_identical(self):
    text = "# keep me\na: 1  # inline\n"
    once = decisions.write_block(text, {"removed": [{"key": "x", "role": None, "date": "2026-11-10", "reason": "r"}]})
    twice = decisions.write_block(once, {"style": {"restyle": "accepted", "date": "2026-11-10"}})
    self.assertTrue(once.startswith(text) and twice.startswith(text))
    self.assertEqual(twice.count(decisions.BEGIN), 1)
    self.assertEqual(decisions.read_block(twice), {"style": {"restyle": "accepted", "date": "2026-11-10"}})

def test_block_appended_without_markers_or_final_newline(self):  # review focus 4
    text = "solution: demo  # no final newline"
    self.assertTrue(decisions.write_block(text, {}).startswith(text + "\n" + decisions.BEGIN))

def test_one_marker_is_an_error(self):
    with self.assertRaises(ValueError):
        decisions.read_block("x: 1\n" + decisions.BEGIN + "\n")

def test_unknown_answer_section_is_rejected(self):
    with self.assertRaises(ValueError):
        decisions.load_answers(temp_file("skip: []\n"))

def test_validate_answers_names_each_problem(self):
    errors = decisions.validate_answers({
        "remove": [{"key": "a.b"}],
        "store": [{"key": "a.list", "as": "csv"}],
        "amend": [{"key": "a.pw", "set": {"default": "x"}}],
        "place": [{"key": "a.b", "component": "core"}],
        "suggestions": [{"key": "nope", "attribute": "required", "decision": "accept"}],
    }, CTX)
    self.assertEqual(len(errors), 5)
    for needle in ("remove a.b", "store a.list", "amend a.pw", "place a.b", "suggestions nope"):
        self.assertTrue(any(e.startswith(needle) for e in errors), needle)

def test_effective_keeps_each_role_to_itself(self):
    remembered = {"removed": [{"key": "a", "role": "alpha", "date": "d", "reason": "r"},
                              {"key": "s", "role": None, "date": "d", "reason": "r"}]}
    self.assertEqual(set(decisions.effective(remembered, {}, "beta")["removed"]), {"s"})
    self.assertEqual(set(decisions.effective(remembered, {}, "alpha")["removed"]), {"a", "s"})

def test_merge_dates_roles_overrides_and_forget(self):
    remembered = {"removed": [{"key": "x", "role": "alpha", "date": "2026-01-01", "reason": "old"}],
                  "suggestions": [{"component": "c", "field": "f", "attribute": "required", "decision": "reject",
                                   "date": "2026-01-01", "evidence": []}]}
    answers = {"place": [{"key": "x", "component": "core"}],
               "forget": [{"component": "c", "field": "f", "attribute": "required"}]}
    new = decisions.merge(remembered, answers, today="2026-11-10", placements={"x": ("core", "x", "alpha")}, evidence={})
    self.assertNotIn("removed", new)
    self.assertNotIn("suggestions", new)
    self.assertEqual(new["placed"], [{"key": "x", "component": "core", "role": "alpha", "date": "2026-11-10"}])
```

- [ ] **Step 2: Run** `python3 -m unittest discover -s scripts/tests -p test_decisions.py -v`. Expected: ERROR,
  `No module named 'decisions'`.
- [ ] **Step 3: Implement** `scripts/decisions.py`.
- [ ] **Step 4: Run** the same command. Expected: `Ran 7 tests`, `OK`.
- [ ] **Step 5: Commit** (if authorised): `feat(skills): yamlToPackage decisions block and answers file`.

### Task 4: the build raises questions instead of FLAGGED/EXCLUDED

**Files:**
- Modify `scripts/yaml_to_package.py`: `Run.__init__`, `check_inputs`, `classify`, `derive`, `merge_field`,
  `check_shared`; a new `ask`; a new module-level `build`. Remove `flag`, the `excluded` list and `--update-shared`.
- Create `scripts/tests/helpers.py`, `scripts/tests/test_build_keys.py`, and the `fixtures/demo/` files `profile.yaml`,
  `alpha.yml`, `beta.yml` and `package.json`.

**Interfaces:**
- Consumes: `decisions.read_block`, `decisions.effective`, `decisions.load_answers` and `decisions.validate_answers`
  (Task 3).
- Produces:
  - **`build(argv: list[str]) -> Run`.** It parses the shared arguments
    (`--profile --role --yaml --target-versions --package --applies-to --answers --samples --schema --today --out-dir`)
    and runs the build steps. Later tasks add steps; Task 7 puts the subcommands in front of it.
  - **`Run.ask(kind: str, key: str, line: int | None, text: str, choices: list[str], context: dict | None = None) -> None`**
    stores the item at `Run.open[f"{kind}:{key}"]`. Spelling ids use `common.normalise_key(key)`.
  - **The outcomes:**
    - `Run.placed: list[dict]` with `key`, `line`, `component`, `field`, `shared`, `field_keys`;
    - `Run.removed: list[dict]` with `key`, `line`, `reason`, `role`, `component`;
    - `Run.open: dict[str, dict]` with `id`, `kind`, `key`, `line`, `text`, `choices`, `context`.
  - **Bookkeeping:** `Run.key_count: int` (keys across all documents, a duplicate counted once);
    `Run.balanced() -> bool`; `Run.info: list[str]`; `Run.placements: dict[str, tuple[str, str, str | None]]`.
- **Question kinds** (`choices` exactly as listed):

  | kind | raised when | `choices` |
  |---|---|---|
  | `no-component` | no `match` claims the key, or relative `key_style` and the key is outside `configPath` | `["place", "remove"]` |
  | `wrong-role` | a `role_exclusive` rule excludes this role, or the match is another role's component | `["place", "remove"]` |
  | `doc2` | the key is in YAML document 2 or later | `["place", "remove"]` |
  | `excluded` | a profile `exclude` entry matches (`context`: its `reason`, and the component the key matches) | `["place", "remove"]` |
  | `spelling` | two keys of one component are equal under `common.normalise_key`; **both stay open** | `["remove"]` (`context.keys`: both keys) |
  | `list` | a YAML list with no `lists` rule | `["list", "join-comma", "index", "remove"]` |
  | `empty-map` | `{}` | `["map", "remove"]` |
  | `decimal` | a float value | `["string", "remove"]` |
  | `unsupported` | any other value with no field type | `["string", "remove"]` |
  | `enum-value` | the value is not in the profile `enums` or the package `options` | `["add-option", "string", "remove"]` |
  | `dup-key` | the same key twice in one mapping | `["first", "second", "remove"]` |
  | `type-conflict` | the YAML type is incompatible with the package field (today's compatibility rule) | `["package-type", "yaml-type", "remove"]` |
  | `shared-new` | a key new to an existing shared component | `["all-roles", "this-role", "role-component", "remove"]` |
  | `role-exclusive-shared` | a `role_exclusive` key would land in a shared component | `["this-role", "role-component", "remove"]` |

- **Resolved in this task:**
  - `remove`, for any kind; it beats every other rule.
  - `place` for the first four kinds, including creating a `new` component (`version` = targets).
  - A removal of one spelling places the other.
  - `Run.info` gets `"remembered decision for <key> (role <role>): not in this YAML"`, but only for `stale_candidates`
    absent from the YAML.
- **Not resolved here:** the `store` answers and the shared `place` scopes come in Task 5. Until then those keys stay
  open.

- [ ] **Step 1: Write the failing tests** in `scripts/tests/test_build_keys.py`, using `helpers.build_demo`:

```python
def test_alpha_counts_and_questions(self):
    run = build_demo("alpha")
    self.assertEqual(run.key_count, 15)
    self.assertTrue(run.balanced())
    self.assertEqual(sorted(p["key"] for p in run.placed), ["demo.alpha.username", "demo.shared.binding", "server.address"])
    expected = {"no-component:demo.unknown.thing": ["place", "remove"], "excluded:demo.alpha.oldname": ["place", "remove"],
                "doc2:late": ["place", "remove"], "list:demo.alpha.retries": ["list", "join-comma", "index", "remove"],
                "decimal:demo.alpha.ratio": ["string", "remove"], "empty-map:demo.alpha.extras": ["map", "remove"],
                "enum-value:demo.alpha.mode": ["add-option", "string", "remove"],
                "dup-key:demo.alpha.host": ["first", "second", "remove"],
                "type-conflict:server.port": ["package-type", "yaml-type", "remove"],
                "shared-new:server.ssl.key-store-password": ["all-roles", "this-role", "role-component", "remove"]}
    for qid, choices in expected.items():
        self.assertEqual(run.open[qid]["choices"], choices, qid)
    [sp] = [q for q in run.open.values() if q["kind"] == "spelling"]
    self.assertEqual(set(sp["context"]["keys"]), {"demo.alpha.fileName", "demo.alpha.filename"})

def test_wrong_role_key_is_a_question(self):
    self.assertIn("wrong-role:demo.alpha.leak", build_demo("beta").open)

def test_place_and_remove_answers(self):
    run = build_demo("alpha", answers={
        "remove": [{"key": "demo.alpha.filename", "reason": "case variant; kept fileName"}],
        "place": [{"key": "demo.unknown.thing", "component": "alpha-core"},
                  {"key": "late", "component": "late", "new": {"role": "none", "cardinality": "zero-or-more", "configPath": ""}}]})
    placed = {p["key"]: p["component"] for p in run.placed}
    self.assertEqual((placed["demo.unknown.thing"], placed["late"], placed["demo.alpha.fileName"]), ("alpha-core", "late", "alpha-core"))
    self.assertEqual([r["key"] for r in run.removed], ["demo.alpha.filename"])
    self.assertTrue(run.balanced())

def test_role_decisions_stay_with_their_role(self):  # review focus 1
    block = {"removed": [{"key": "demo.shared.binding", "role": "alpha", "date": "d", "reason": "r"},
                         {"key": "demo.alpha.gone", "role": "alpha", "date": "d", "reason": "r"}]}
    beta = build_demo("beta", remembered=block)
    self.assertIn("demo.shared.binding", [p["key"] for p in beta.placed if p["component"] == "beta-core"])
    self.assertFalse(any("demo.alpha.gone" in i for i in beta.info))
    alpha = build_demo("alpha", remembered=block)
    self.assertIn("demo.shared.binding", [r["key"] for r in alpha.removed])
    self.assertTrue(any("demo.alpha.gone" in i for i in alpha.info))
```

- [ ] **Step 2: Run** `python3 -m unittest discover -s scripts/tests -p test_build_keys.py -v`. Expected: ERROR, because
  `yaml_to_package` has no `build`.
- [ ] **Step 3: Implement** the interfaces above in `scripts/yaml_to_package.py`, with `helpers.py` and the demo
  fixtures.
- [ ] **Step 4: Run** `python3 -m unittest discover -s scripts/tests -v`. Expected: all OK (Tasks 1 to 4: 26 tests).
- [ ] **Step 5: Commit** (if authorised): `feat(skills): yamlToPackage asks instead of dropping keys`.

### Task 5: answers to the value and shared-component questions

**Files:** Modify `scripts/yaml_to_package.py` (applying `store` and the shared `place` answers), `scripts/common.py`
(`load_yaml_leaves`: each entry in `duplicates` gains `"value"`); create `scripts/tests/test_build_values.py`.

**Interfaces:**
- Consumes: Task 4's `Run.open`, `Run.placed` and `ask`; `decisions.effective(...)["stored"]` and `["placed"]`.
- Produces these resolutions on `Run.pkg` (`as` values exactly as in Task 4's `choices`):
  - **Lists:**
    - `list`: type `list`, default `None`, plus a coerced note;
    - `join-comma`: type `string`, with the items joined by `,`;
    - `index`: one field per item, keys `<key>.0`, `<key>.1`…, each typed by `common.infer_type`. The placed entry
      lists every key in `field_keys`.
  - **Single values:**
    - `map`: type `map`, default `None`;
    - `string` (for a decimal or unsupported value): type `string`, default `str(value)`;
    - `add-option`: the value is appended to `options`, and becomes the default;
    - `string` (for an enum value): type `string`, with `options` dropped.
  - **Conflicts and duplicates:**
    - `package-type`: the declaration is unchanged and the YAML default is not used;
    - `yaml-type`: the type and default come from the YAML;
    - `first` / `second`: the default comes from that occurrence.
  - **Shared-component placements:**
    - `place` with `scope: all-roles`: the field is added to the shared component;
    - `scope: this-role`: the same, plus field `role` set to the run's role;
    - `component` set to this role's component: the field is placed there.
  - **Shared defaults that differ** stay as today (the package wins), and `Run.info` gets
    `"shared default differs (package kept): <component> <key>: package <json>, YAML <json>"`.

- [ ] **Step 1: Write the failing tests** in `scripts/tests/test_build_values.py`:

```python
STORE = {"store": [{"key": "demo.alpha.retries", "as": "index"}, {"key": "demo.alpha.ratio", "as": "string"},
                   {"key": "demo.alpha.extras", "as": "map"}, {"key": "demo.alpha.mode", "as": "add-option"},
                   {"key": "server.port", "as": "package-type"}, {"key": "demo.alpha.host", "as": "second"}],
         "place": [{"key": "server.ssl.key-store-password", "component": "server", "scope": "this-role"}]}

def test_store_answers_shape_the_fields(self):
    f = fields_by_key(build_demo("alpha", answers=STORE).pkg)
    self.assertEqual([f[f"demo.alpha.retries.{i}"]["type"] for i in range(3)], ["int"] * 3)
    self.assertEqual((f["demo.alpha.ratio"]["type"], f["demo.alpha.ratio"]["default"]), ("string", "0.5"))
    self.assertEqual((f["demo.alpha.extras"]["type"], f["demo.alpha.extras"]["default"]), ("map", None))
    self.assertEqual((f["demo.alpha.mode"]["options"], f["demo.alpha.mode"]["default"]), (["fast", "slow", "medium"], "medium"))
    self.assertEqual((f["server.port"]["type"], f["server.port"]["default"]), ("string", "8094"))
    self.assertEqual(f["demo.alpha.host"]["default"], "second.example.invalid")

def test_this_role_scope_sets_the_field_role_and_withholds_the_secret(self):
    f = fields_by_key(build_demo("alpha", answers=STORE).pkg)["server.ssl.key-store-password"]
    self.assertEqual((f["role"], f["type"], f["default"]), ("alpha", "secret", None))

def test_join_and_list_shapes(self):
    j = fields_by_key(build_demo("alpha", answers={"store": [{"key": "demo.alpha.retries", "as": "join-comma"}]}).pkg)
    self.assertEqual((j["demo.alpha.retries"]["type"], j["demo.alpha.retries"]["default"]), ("string", "1,2,3"))
    l = fields_by_key(build_demo("alpha", answers={"store": [{"key": "demo.alpha.retries", "as": "list"}]}).pkg)
    self.assertEqual((l["demo.alpha.retries"]["type"], l["demo.alpha.retries"]["default"]), ("list", None))

def test_shared_default_difference_is_information(self):
    run = build_demo("alpha")
    self.assertEqual(fields_by_key(run.pkg)["server.address"]["default"], "0.0.0.0")
    self.assertIn('shared default differs (package kept): server server.address: package "0.0.0.0", YAML "127.0.0.1"', run.info)
```

- [ ] **Step 2: Run** `python3 -m unittest discover -s scripts/tests -p test_build_values.py -v`. Expected: FAIL, because
  the keys stay open and `KeyError` is raised on the field lookups.
- [ ] **Step 3: Implement** the resolutions, and the duplicate value in `common.load_yaml_leaves`.
- [ ] **Step 4: Run** the full suite. Expected: all OK (30 tests).
- [ ] **Step 5: Commit** (if authorised): `feat(skills): yamlToPackage applies value and shared-component answers`.

### Task 6: package-level items (suggestions, restyle, `secret: true`, amendments, signature, rows)

**Files:** Modify `scripts/yaml_to_package.py`; create `scripts/tests/test_build_package.py`.

**Interfaces:**
- Consumes:
  - from Task 1: `style.*`;
  - from Task 2: `learn_samples.find_samples_dir`, `build_index` and `suggest`;
  - from Task 3: the `suggestions`, `amendments`, `restyle` and `provenance` parts of `effective()`;
  - from Tasks 4 and 5: the build.
- Produces:
  - **The samples.** `--samples` overrides `find_samples_dir(os.getcwd())`. The target solution is excluded.
    `Run.samples = {"dir", "packages", "fields", "notes"}`.
  - **Suggestion questions.** For every placed field that lacks the attribute and has no decision, there is one
    `suggest` item per `Suggestion`:
    - id `suggest:<component>/<field>/<attribute>`;
    - choices `["accept", "reject"]`;
    - `context` holds `evidence`, `support` and `matched`.

    Accepting sets `required: true` or `ui.advanced: true`; rejecting changes nothing.
  - **The restyle question.** Item `restyle`, with choices `["accept", "refuse"]`. It is raised only when `--package`
    has a component whose `detect_order_style` is not `within-section`, or whose `detect_helper_style` is `"stock"`.
    - `context.totals` sums `style.restyle` over **copies** of the existing components, and `context.components`
      gives the totals per component.
    - Accept: `style.restyle` runs on every component, including the profile's `preserve` ones.
    - Refuse: every component keeps its detected style. New fields then get `style.order_for_new` in that style and
      `style.new_helper` in its helper style.
    - With no `--package`, or after accepting, new fields get `within-section` and `"sparse"`.
  - **Secrets.** `style.mark_secrets` runs on every component, and `Run.info` lists the keys.
  - **The signature question.** Item `provenance`, with choices `["keep", "dev-mode"]`. It is raised only when the
    package has `provenance.signature` and the build changes it. `dev-mode` sets `provenance = {"author": "dev-mode"}`.
  - **Amendments** are applied last. A `ui.<x>` key sets `field["ui"][x]`.
  - **`Run.rows: list[dict]`**, one per placed field key:
    `component, key, line, field, type, default_display, section, order, shown ("up front" | "advanced"), required (bool), suggested ({attribute: bool}), role, versions, notes`.
    `default_display` is `"withheld (I6)"` for a secret, `"from connection"` for `valueFrom`, and `json.dumps(default)`
    otherwise. `notes` holds any of: `new`, `was <json old default>` (never for a secret), `YAML says <json>` (a
    shared default kept), `valueFrom`, `secret: true added`. No row and no note ever holds a secret value.
  - **More `Run.info` lines:**
    - `coerced: <key>: <note>`, one per coerced value;
    - `in the package, not in this YAML (kept): <component>/<field>`, one per package-only field;
    - `reviewed labels kept: <n>`.
  - **A profile's `ui_order` is ignored.** The style is detected from the package.

- [ ] **Step 1: Write the failing tests** in `scripts/tests/test_build_package.py`. `ANSWERS_ALL` loads
  `fixtures/demo/answers-alpha.yaml`; create that file now (see Test fixtures).

```python
def test_suggestion_question_carries_evidence(self):
    q = build_demo("alpha").open["suggest:alpha-core/demo.alpha.username/required"]
    self.assertEqual(q["choices"], ["accept", "reject"])
    self.assertIn("two/svc2/username: required", q["context"]["evidence"])

def test_accepted_and_rejected_suggestions(self):
    acc = build_demo("alpha", answers={"suggestions": [{"key": "demo.alpha.username", "attribute": "required", "decision": "accept"}]})
    self.assertTrue(fields_by_key(acc.pkg)["demo.alpha.username"]["required"])
    rej = build_demo("alpha", remembered={"suggestions": [{"component": "alpha-core", "field": "demo.alpha.username",
        "attribute": "required", "decision": "reject", "date": "d", "evidence": []}]})
    self.assertNotIn("suggest:alpha-core/demo.alpha.username/required", rej.open)
    self.assertNotIn("required", fields_by_key(rej.pkg)["demo.alpha.username"])

def test_restyle_question_counts_existing_fields(self):
    q = build_demo("alpha").open["restyle"]
    self.assertEqual(q["choices"], ["accept", "refuse"])
    self.assertEqual(q["context"]["totals"], {"order_changed": 4, "stock_removed": 5, "custom_kept": 1})

def test_accepted_and_refused_restyle(self):
    acc = fields_by_key(build_demo("alpha", answers={"restyle": "accept"}).pkg)
    self.assertEqual((acc["demo.alpha.host"]["ui"]["order"], acc["demo.alpha.legacy"]["ui"]["order"]), (0, 0))
    self.assertNotIn("helperText", acc["demo.alpha.host"]["ui"])
    self.assertEqual(acc["demo.alpha.legacy"]["ui"]["helperText"], "Hand-written help.")
    ref = fields_by_key(build_demo("alpha", answers={"restyle": "refuse"}).pkg)
    self.assertEqual(ref["demo.alpha.username"]["ui"]["order"], 8)   # running style continues after 7

def test_first_package_has_no_restyle_or_signature_question(self):  # review focus 2
    run = build_demo("alpha", package=None, applies_to=">=1.0 <2.0")
    self.assertNotIn("restyle", run.open)
    self.assertNotIn("provenance", run.open)
    f = fields_by_key(run.pkg)
    self.assertEqual(f["demo.alpha.username"]["ui"]["order"], 0)
    self.assertTrue(all("helperText" not in x["ui"] for x in f.values()))
    self.assertTrue(all(x.get("secret") for x in f.values() if x["type"] == "secret"))

def test_signature_question_only_when_a_signed_package_changes(self):
    self.assertEqual(build_demo("alpha").open["provenance"]["choices"], ["keep", "dev-mode"])
    self.assertNotIn("provenance", build_demo("alpha", package=package_copy(provenance={"author": "dev-mode"})).open)

def test_amendments_apply(self):
    run = build_demo("alpha", answers={"amend": [{"key": "demo.alpha.username", "set": {"ui.label": "User", "required": True}}]})
    f = fields_by_key(run.pkg)["demo.alpha.username"]
    self.assertEqual((f["ui"]["label"], f["required"]), ("User", True))

def test_rows_never_show_secret_values(self):
    run = build_demo("alpha", answers=ANSWERS_ALL)
    self.assertNotIn("hunter2pass", json.dumps(run.rows))
    self.assertNotIn("oldsecret1", json.dumps(run.rows))
    [row] = [r for r in run.rows if r["field"] == "server.ssl.key-store-password"]
    self.assertEqual(row["default_display"], "withheld (I6)")

def test_no_samples_is_information(self):
    run = build_demo("alpha", samples=empty_dir())
    self.assertFalse(any(q["kind"] == "suggest" for q in run.open.values()))
    self.assertIn("no samples found; no suggestions", run.samples["notes"])
```

- [ ] **Step 2: Run** `python3 -m unittest discover -s scripts/tests -p test_build_package.py -v`. Expected: FAIL, with
  no `suggest:`/`restyle`/`provenance` items and no `Run.rows`.
- [ ] **Step 3: Implement** the interfaces above.
- [ ] **Step 4: Run** the full suite. Expected: all OK (39 tests).
- [ ] **Step 5: Commit** (if authorised): `feat(skills): yamlToPackage suggestions, restyle, secret flag, amendments`.

### Task 7: the `preview` command and `preview_md.py`

**Files:**
- Modify `scripts/yaml_to_package.py`: `main(argv=None) -> int` with the `preview` and `apply` subcommands, and
  `Run.model()`. Remove the old one-shot `main` and `--provenance`.
- Create `scripts/preview_md.py` and `scripts/tests/test_cli_preview.py`.
- Extend `helpers.py` with `run_cli` and `run_cli_err`.

**Interfaces:**
- Consumes: `build` (Tasks 4 to 6).
- Produces:
  - **The subcommands.** `yaml_to_package.py preview|apply` takes the shared arguments; `--profile`, `--role`,
    `--yaml`, `--target-versions` and `--out-dir` are required. No subcommand is an argparse usage error (exit 2).
  - **`Run.model() -> dict`**, the content of `preview.json`, with keys:
    `solution, role, profile, yaml, package, target_versions, packageVersion, appliesTo, roles {all, this_role, shared}, samples, counts {keys, placed, removed, open}, components [{type, role, shared, rows}], questions [{n, id, kind, key, line, text, choices, context}], removed, info, profile_changes, ready`.
    - Questions run in this order: key questions by YAML line, then suggestions, then `restyle`, then `provenance`.
      `n` starts at 1.
    - `profile_changes` is the unified diff, as a list of lines, between the old and new decisions blocks
      (`decisions.merge` against the remembered block).
    - `ready` is true when `Run.open` is empty.
  - **`preview_md.render(model: dict, previous: dict | None) -> str`.** The headings are exactly:
    - `## <solution> / <role>: PREVIEW (nothing created yet)`, followed by the header lines from the spec mock;
    - `### <type> (role <role>): <n> fields`, or `### <type> (shared): <n> fields`, each with a table in the spec
      mock's columns;
    - `### Questions (answer by number)`;
    - `### For your information`;
    - `### Profile changes saved on "create"`.

    When `previous` is given, the text **starts with** `## Changes since the last preview`. That section lists changed
    rows (`<component>/<field>: <column> <old> → <new>`), resolved question ids, and new question ids.
  - **The `preview` command.**
    1. It validates the answers first. On errors it prints them one per line on stderr and exits 2.
    2. It reads any existing `<out-dir>/preview.json` as `previous`.
    3. It writes `preview.json` and `preview.md`, and prints
       `<solution>/<role>: keys=<n> placed=<p> removed=<r> open=<o> questions=<q>`.
    4. It exits 0 when `ready`, 5 otherwise. It never writes `package.json`.

- [ ] **Step 1: Write the failing tests** in `scripts/tests/test_cli_preview.py`. `out` is a fresh temp folder per test,
  and `ALPHA_KEYS` is the 15 keys of `alpha.yml`.

```python
def test_preview_never_writes_a_package_and_exits_5(self):
    self.assertEqual(run_cli("preview", "alpha", out), 5)
    self.assertFalse((out / "package.json").exists())
    model = json.loads((out / "preview.json").read_text())
    self.assertEqual(len(model["questions"]), 14)
    self.assertEqual(model["counts"], {"keys": 15, "placed": 3, "removed": 0, "open": 12})

def test_preview_exits_0_when_everything_is_answered(self):
    self.assertEqual(run_cli("preview", "alpha", out, answers=ANSWERS_FILE), 0)
    self.assertTrue(json.loads((out / "preview.json").read_text())["ready"])

def test_bad_answers_exit_2_naming_the_entry(self):
    code, err = run_cli_err("preview", "alpha", out, answers=temp_file("store: [{key: demo.alpha.ratio, as: float}]\n"))
    self.assertEqual(code, 2)
    self.assertIn("store demo.alpha.ratio", err)

def test_preview_md_mentions_every_key_and_heading(self):
    run_cli("preview", "alpha", out)
    md = (out / "preview.md").read_text()
    for key in ALPHA_KEYS:
        self.assertIn(key, md)
    for heading in ("## demo / alpha: PREVIEW (nothing created yet)", "### Questions (answer by number)",
                    "### For your information", '### Profile changes saved on "create"'):
        self.assertIn(heading, md)

def test_second_preview_starts_with_the_changes(self):
    run_cli("preview", "alpha", out)
    run_cli("preview", "alpha", out, answers=ANSWERS_FILE)
    md = (out / "preview.md").read_text()
    self.assertTrue(md.startswith("## Changes since the last preview"))
    self.assertIn("doc2:late", md)

def test_no_secret_value_in_preview_files(self):
    run_cli("preview", "alpha", out, answers=ANSWERS_FILE)
    for name in ("preview.md", "preview.json"):
        text = (out / name).read_text()
        self.assertNotIn("hunter2pass", text)
        self.assertNotIn("oldsecret1", text)

def test_no_subcommand_is_a_usage_error(self):
    self.assertEqual(subprocess.run([sys.executable, str(ENGINE), "--profile", "x"], capture_output=True).returncode, 2)
```

- [ ] **Step 2: Run** `python3 -m unittest discover -s scripts/tests -p test_cli_preview.py -v`. Expected: FAIL, because
  there is no `preview` subcommand (exit 2 everywhere).
- [ ] **Step 3: Implement** `main`, `Run.model()` and `scripts/preview_md.py`.
- [ ] **Step 4: Run** the full suite. Expected: all OK (46 tests).
- [ ] **Step 5: Commit** (if authorised): `feat(skills): yamlToPackage preview command`.

### Task 8: the `apply` command (gate, writes, profile merge)

**Files:** Modify `scripts/yaml_to_package.py` (`apply`, `read_back`, the report writers, the masked diff); create
`scripts/tests/test_cli_apply.py`.

**Interfaces:**
- Consumes: `build`, `Run.model()` (Task 7), `decisions.merge` and `write_block` (Task 3), and
  `validate_package.validate`.
- Produces:
  - **The `apply` command.**
    1. If `Run.open` is not empty, it prints the open ids to stderr, writes nothing, and exits 5.
    2. Otherwise it writes, to `<out-dir>`:
       - `package.json`, with the `ensure_ascii` rule as today;
       - `package.diff`, when there is a `--package`. Old secret defaults become `"***"` before the diff is taken;
       - `report.md` and `report.json`;
       - `profile.yaml`, which is `decisions.write_block(original profile text, decisions.merge(...))`.
    3. It runs `read_back` and `validate_package.validate`, and exits 1 on any problem or validation error, else 0.
  - **`read_back(package_path: str, placed: list[dict], removed: list[dict]) -> list[str]`** returns one problem per:
    - placed field key missing from its component: `"missing: <component>/<field>"`;
    - removed key still present in its `component`, or in any component when `component` is `None`:
      `"still present: <component>/<key>"`.
  - **`report.json`** has the keys:
    `solution, role, profile, yaml, package, target_versions, counts {keys, placed, removed, open, coerced, secrets_withheld, balanced}, placed [{key, line, component, field, type, shared}], removed [{key, line, reason, role, component}], coerced, secrets_withheld [{key, line}], diff, shared_differences, ui_differences, notes, validation, authoring, restyle, suggestions [{component, field, attribute, decision}]`.
    - `flagged` and `excluded` are gone.
    - A secret's changed default prints as `***`.
    - `report.md` shows the same data. Its coverage line is
      `<keys> YAML keys → <placed> fields, <removed> removed by you (balanced: yes|NO)`.

- [ ] **Step 1: Write the failing tests** in `scripts/tests/test_cli_apply.py`:

```python
def test_apply_refuses_open_questions(self):
    code, err = run_cli_err("apply", "alpha", out)
    self.assertEqual(code, 5)
    self.assertIn("doc2:late", err)
    self.assertFalse((out / "package.json").exists())

def test_apply_writes_every_key_as_a_field_or_removed(self):
    self.assertEqual(run_cli("apply", "alpha", out, answers=ANSWERS_FILE), 0)
    rep = json.loads((out / "report.json").read_text())
    self.assertEqual((rep["counts"]["keys"], rep["counts"]["removed"], rep["counts"]["open"]), (15, 1, 0))
    self.assertTrue(rep["counts"]["balanced"])

def test_read_back_catches_a_missing_field(self):
    problems = yaml_to_package.read_back(str(PACKAGE), [{"key": "x", "component": "alpha-core", "field_keys": ["demo.alpha.nothere"]}], [])
    self.assertEqual(problems, ["missing: alpha-core/demo.alpha.nothere"])

def test_removed_key_drops_an_existing_field(self):
    answers = with_changes(ANSWERS_FILE, drop_store="demo.alpha.host",
                           add_remove={"key": "demo.alpha.host", "reason": "not needed"})
    self.assertEqual(run_cli("apply", "alpha", out, answers=answers), 0)
    pkg = json.loads((out / "package.json").read_text())
    self.assertNotIn("demo.alpha.host", [f["key"] for c in pkg["components"] for f in c.get("fields", [])])

def test_profile_hand_written_part_is_byte_identical(self):
    run_cli("apply", "alpha", out, answers=ANSWERS_FILE)
    merged = (out / "profile.yaml").read_text()
    self.assertTrue(merged.startswith(PROFILE.read_text()))
    block = decisions.read_block(merged)
    self.assertEqual(block["removed"], [{"key": "demo.alpha.filename", "role": "alpha", "date": "2026-11-10",
                                         "reason": "case variant; kept fileName"}])
    [ks] = [p for p in block["placed"] if p["key"] == "server.ssl.key-store-password"]
    self.assertEqual((ks["role"], ks["scope"]), ("alpha", "this-role"))

def test_secret_values_absent_from_every_output(self):
    run_cli("apply", "alpha", out, answers=ANSWERS_FILE)
    for name in ("package.json", "package.diff", "report.md", "report.json", "profile.yaml"):
        text = (out / name).read_text()
        self.assertNotIn("hunter2pass", text, name)
        self.assertNotIn("oldsecret1", text, name)
    self.assertIn('"default": "***"', (out / "package.diff").read_text())

def test_apply_without_package_requires_applies_to(self):  # review focus 2
    self.assertEqual(run_cli("apply", "alpha", out, package=None), 2)
```

- [ ] **Step 2: Run** `python3 -m unittest discover -s scripts/tests -p test_cli_apply.py -v`. Expected: FAIL, because
  `apply` is not implemented.
- [ ] **Step 3: Implement** the interfaces above.
- [ ] **Step 4: Run** the full suite. Expected: all OK (53 tests).
- [ ] **Step 5: Commit** (if authorised): `feat(skills): yamlToPackage apply command with read-back gate`.

### Task 9: downstream scripts

**Files:** Modify `scripts/render_config.py` (`roundtrip`), `scripts/profile_proposer.py`, `scripts/validate_package.py`;
create `scripts/tests/test_downstream.py`.

**Interfaces:**
- Consumes: `report.json` (Task 8) and `decisions.write_block` / `BEGIN` (Task 3).
- Produces:
  - **`render_config.py roundtrip`** reads `report["removed"]`. A removed key absent from the rendered config is
    allowed, as `removed by you: <reason>`; one that is present fails. The `excluded` and `flagged` handling and their
    docstring lines are removed.
  - **`profile_proposer.py`:**
    - the draft has no `exclude` and no `ui_order`, and ends with `decisions.write_block("", {})`;
    - the structure line reads `lists/maps (asked in the preview): …`;
    - the list question reads ``…: keep it as a `list` field, join it with ',', one field per item, or remove it?``.
  - **`validate_package.py`** adds the warning
    `"<component>/<key>: type secret without secret: true (the console's project() withholds the value only with secret: true)"`.

- [ ] **Step 1: Write the failing tests** in `scripts/tests/test_downstream.py`:

```python
def test_roundtrip_lists_keys_removed_by_you(self):
    run_cli("apply", "alpha", out, answers=ANSWERS_FILE)
    subprocess.run([sys.executable, str(RENDER), "roundtrip", "--package", str(out / "package.json"),
                    "--profile", str(out / "profile.yaml"), "--role", "alpha", "--version", "2", "--yaml", str(ALPHA),
                    "--report", str(out / "report.json"), "--out", str(out / "rt.md")], capture_output=True)
    md = (out / "rt.md").read_text()
    self.assertIn("demo.alpha.filename", md)
    self.assertIn("removed by you", md)
    self.assertNotIn("excluded", md)

def test_proposer_drafts_without_exclude_or_ui_order(self):
    res = subprocess.run([sys.executable, str(PROPOSER), "--yaml", str(ALPHA), "--solution", "demo2",
                          "--out", str(out / "draft.yaml")], capture_output=True, text=True)
    text = (out / "draft.yaml").read_text()
    self.assertNotIn("exclude:", text)
    self.assertNotIn("ui_order:", text)
    self.assertIn(decisions.BEGIN, text)
    self.assertIn("asked in the preview", res.stdout)

def test_validator_warns_on_a_secret_without_the_flag(self):
    warn = lambda pkg: [w for w in validate_package.validate(pkg)["warnings"] if "without secret: true" in w]
    self.assertEqual(len(warn(one_secret_package(flag=False))), 1)
    self.assertEqual(warn(one_secret_package(flag=True)), [])
```

- [ ] **Step 2: Run** `python3 -m unittest discover -s scripts/tests -p test_downstream.py -v`. Expected: 3 FAIL.
- [ ] **Step 3: Implement** the three changes.
- [ ] **Step 4: Run** the full suite. Expected: all OK (56 tests).
- [ ] **Step 5: Commit** (if authorised): `fix(skills): yamlToPackage downstream readers follow the new report`.

### Task 10: skill text and the File Mesh profile

**Files:** Modify `references/profiles/file-mesh.yaml`, `SKILL.md`, `references/mapping-guide.md`,
`references/package-schema.md`, `references/ui-testing.md`.

**Interfaces:**
- Consumes: the commands and exit codes from Tasks 7 and 8, `decisions.write_block`, and the question kinds from
  Tasks 4 to 6.

- [ ] **Step 1: The profile.**
  - Delete the `ui_order: running` line and its comment continuation, and the whole `exclude:` block.
  - Set FM-D1's resolution to `"superseded 2026-11-10 (plan tooling/002): no longer excluded; the preview asks which
    spelling to keep, and the answer is recorded under decisions.removed"`.
  - Set FM-D5's resolution to `"superseded 2026-11-10 (plan tooling/002): db-designer numbering once the user accepts
    the restyle; a refused restyle keeps the detected running style"`.
  - Append the empty decisions block with `decisions.write_block(text, {})`.
- [ ] **Step 2: Check the profile on File Mesh** (preview only; it never writes the profile). From the skill folder:
  `python3 scripts/yaml_to_package.py preview --profile references/profiles/file-mesh.yaml --role source --yaml examples/file-mesh-source/input/application-source-file-v2.yml --target-versions 1,2,3 --package ../../../frontend/contracts/packages/file-mesh/package.json --out-dir <scratch>/t10`.
  Expected: exit 5. `preview.json` has `counts == {"keys": 110, "placed": 108, "removed": 0, "open": 2}` and these
  7 questions:
  - 1 `spelling`: the FM-D1 pair;
  - 4 `suggest`: `solace/solace.java.host/required`, `solace/solace.connector.management.session.host/required`,
    `application/spring.application.name/required`, `logging/logging.file.name/required`;
  - `restyle`, with `totals == {"order_changed": 138, "stock_removed": 154, "custom_kept": 2}`;
  - `provenance`.
- [ ] **Step 3: `SKILL.md` Stage 1.** Rewrite these parts:
  - "Inputs to establish first": add the samples folder.
  - "Workflow A": preview → answers (the skill writes `answers.yaml`) → re-preview → "Create package.json? (yes/no)"
    → `apply` → copy `<out-dir>/profile.yaml` into `references/profiles/` → report → the canonical-copy gate. Use the
    exit-code table 0 to 5.
  - "Running several roles in a row": pass the previous run's `package.json` **and** `profile.yaml`.
  - "Rules that are easy to get wrong":
    - never drop a key; only the user removes one;
    - spelling pairs;
    - every suggestion needs acceptance and shows its evidence;
    - secrets, including comments;
    - the restyle block;
    - never hand-edit the decisions block (use `forget`);
    - example answers never go into the real profile.
  - "Output of every run": the preview (the full table the first time, the changes after) and the `apply` report.

  `SKILL.md` stays at or under 300 lines.
- [ ] **Step 4: `references/mapping-guide.md`.**
  - Engine rules 6 to 10 change to: the three outcomes, the question-kind table (Task 4), the style (Task 1), the
    learning rule (Task 2), and versions (unchanged).
  - Merge rules: a removal drops an existing field; shared new keys are handled through questions; `--update-shared`
    is gone.
  - Profile format: `exclude` and `ui_order` are removed (one line saying so); the decisions block, its markers and
    `forget` are added.
  - The worked example uses Step 2's numbers.
- [ ] **Step 5: `references/package-schema.md`.**
  - The `ui.order` section: db-designer and the other hand-written samples number by position within section, cache
    by section index, File Mesh by a running counter. The skill writes the db-designer style.
  - The helper-text section: the skill writes the comment alone. The stock sentences are what older packages carry and
    what the restyle removes.
  - Add a field row: `secret: true` always comes with `type: secret`.
- [ ] **Step 6: `references/ui-testing.md` line 110** reads "removed by you, coerced and withheld leaves".
- [ ] **Step 7: Verify.**
  - `wc -l SKILL.md` prints ≤ 300.
  - `grep -rn -e "--update-shared" -e FLAGGED -e EXCLUDED SKILL.md references/ scripts/*.py` prints nothing.
  - `grep -rn ui_order SKILL.md references/ scripts/*.py` prints only mapping-guide's "removed" line.
  - The full suite is all OK.
- [ ] **Step 8: Commit** (if authorised): `docs(skills): yamlToPackage Stage 1 preview-and-create workflow`.

### Task 11: end to end on File Mesh, and the regenerated examples

**Files:** Replace the contents of `examples/file-mesh-source/output/` and `examples/file-mesh-sink/output/`. Scratch
work goes to `<scratch>/e2e/` and `<scratch>/base/`. Run commands from the repo root; `S` is the skill folder.

- [ ] **Step 1: Baseline (today's engine).**
  1. Run `git worktree add --detach <scratch>/baseline 305807d`.
  2. Run the baseline's `yaml_to_package.py` with the old one-shot arguments: the source YAML
     `$S/examples/file-mesh-source/input/application-source-file-v2.yml` (the sink YAML is under
     `examples/file-mesh-sink/input/`; both are tracked, byte-identical to the repo-root copies, which are untracked
     and absent from a worktree) against `frontend/contracts/packages/file-mesh/package.json`, targets 1,2,3, `--schema contracts/solution-package.schema.json`,
     out `<scratch>/base/source`. Then the sink, chained on `<scratch>/base/source/package.json`, out
     `<scratch>/base/sink`. Both exit 0.
  3. Run `git worktree remove <scratch>/baseline`.
- [ ] **Step 2: Source preview.**
  1. `cp $S/references/profiles/file-mesh.yaml <scratch>/e2e/profile.yaml`.
  2. Run `python3 $S/scripts/yaml_to_package.py preview --profile <scratch>/e2e/profile.yaml --role source --yaml $S/examples/file-mesh-source/input/application-source-file-v2.yml --target-versions 1,2,3 --package frontend/contracts/packages/file-mesh/package.json --samples frontend/contracts/packages --today 2026-11-10 --out-dir <scratch>/e2e/source`.
     Sink and `apply` runs take the same arguments, changed only as noted.
  3. Expected: exit 5, with the same counts and 7 questions as Task 10 Step 2. Copy `preview.md` to
     `<scratch>/e2e/source/preview.first.md`.
- [ ] **Step 3: Source answers and apply.**
  1. Write `<scratch>/e2e/answers-source.yaml`, with first line
     `# EXAMPLE answers used to exercise the skill (plan tooling/002); not the user's decisions.`, containing:
     - remove `file.source.command_center.useoutputdestinationsolacecredentials`, reason
       `"case variant; kept useOutputDestinationSolaceCredentials"`;
     - accept `required` on the two hosts, and reject it on the two names;
     - `restyle: accept` and `provenance: keep`.
  2. Run `preview` with the answers. Expected: exit 0.
  3. Run `apply`. Expected: exit 0, and `<scratch>/e2e/source/profile.yaml` starts with the exact bytes of
     `<scratch>/e2e/profile.yaml`. This checks the hand-written part on the heavily commented File Mesh profile.
- [ ] **Step 4: Sink.** Run `preview --role sink --yaml $S/examples/file-mesh-sink/input/application-sink-file-v2.yml --package <scratch>/e2e/source/package.json --profile <scratch>/e2e/source/profile.yaml --out-dir <scratch>/e2e/sink`.
  - Expected: exit 0 with `counts == {"keys": 102, "placed": 102, "removed": 0, "open": 0}`. Every suggestion is on a
    shared key that is already decided, the restyle is already applied, and the signature decision is remembered.
  - Then run `apply`. Expected: exit 0.
- [ ] **Step 5: Validators.** Both print PASS for `<scratch>/e2e/sink/package.json`:
  - `python3 $S/scripts/validate_package.py <scratch>/e2e/sink/package.json`;
  - `python3 contracts/validation/validate-package.py --dev-mode <scratch>/e2e/sink/package.json`.
- [ ] **Step 6: Compare with the baseline.** A throwaway `<scratch>/e2e/compare.py` diffs `<scratch>/base/sink/package.json`
  with `<scratch>/e2e/sink/package.json`. Expected:
  - identical component types and field keys;
  - per-field differences limited to `ui.order`, a removed `ui.helperText` (154 fields), an added `secret` (8 fields),
    and `required: true` on `solace.java.host` and `solace.connector.management.session.host`;
  - a top-level difference only in `authoring`.
- [ ] **Step 7: Secrets.**
  - Collect the secret values: the YAMLs' secret leaves (`common.load_yaml_leaves` + `common.infer_type`), and the old
    secret defaults of the canonical package.
  - Values of 6 or more characters: a whole-word grep over every file under `<scratch>/e2e/` finds none.
  - Shorter values: every secret default in both e2e packages is `null`, and every secret row in `report.md` and
    `preview.md` shows `withheld` or `***`.
- [ ] **Step 8: Examples.**
  - Replace each `output/` folder with that role's `preview.md`, `preview.json`, `package.json`, `package.diff`,
    `report.md` and `report.json`.
  - Source also gets `preview.first.md` and `answers.yaml`.
  - Add `profile.diff`: `diff -u $S/references/profiles/file-mesh.yaml <run>/profile.yaml`.
  - `input/` is unchanged.
- [ ] **Step 9: Run** the full suite. Expected: all OK.
- [ ] **Step 10: Commit** (if authorised): `test(skills): yamlToPackage File Mesh end to end, regenerated examples`.

### Task 12: evals

**Files:**
- Rewrite `evals/file-mesh-source-regenerate/{case.yaml,graders/*}`.
- Rename `evals/file-mesh-sink-flags-consumer/` to `evals/file-mesh-sink-preview/` (use `git mv` if the user allows
  commits, else `mv`) and rewrite its `case.yaml` and graders.
- Create `evals/apply-after-answers/` (`case.yaml`, `prompt.md`, `place-files.sh`, `files/`, `graders/`).
- Check `evals/no-profile-stops-and-asks/`, `evals/ui-stage-file-mesh/` and `evals/ssh-dry-run-placeholder/` for
  wording.

- [ ] **Step 1: `file-mesh-source-regenerate`.** The prompt is unchanged. Rewrite the `expected_outcome`, then replace
  the graders with these 7:

  | grader | passes when |
  |---|---|
  | 01 | the run stops at the preview, and no `package.json` was created |
  | 02 | FM-D1 is asked as a spelling question, offering to keep either key |
  | 03 | the four required suggestions are shown, each with evidence naming a sample field |
  | 04 | the restyle block shows 138 / 154 / 2 |
  | 05 | `adapter_id`'s default change and the withheld secrets are shown, with no secret value |
  | 06 | the input package is unmodified |
  | 07 | the coerced values are listed for information |
- [ ] **Step 2: `file-mesh-sink-preview`.** Rewrite the `expected_outcome`, then replace the graders with these 4:

  | grader | passes when |
  |---|---|
  | 01 | the three `spring.cloud.stream.solace.default.consumer.*` keys are placed in `file-sink` (FM-D2) |
  | 02 | shared default differences are listed for information, and the package values are kept |
  | 03 | the `file-source` component is untouched |
  | 04 | the run stops at the preview, with no `package.json` |
- [ ] **Step 3: `apply-after-answers`.**
  - `files/`: `application-source-file-v2.yml` and `package.json`, copied from
    `evals/file-mesh-source-regenerate/files/`, plus Task 11's `answers.yaml`.
  - Prompt: "here are my answers for the file mesh source preview (answers.yaml). targets 1,2,3. create the package."
  - Graders:

    | grader | passes when |
    |---|---|
    | 01 | `package.json` is created in a scratch folder |
    | 02 | the report shows 110 keys = 109 fields + 1 removed by you |
    | 03 | the decisions block is written with the date, and the hand-written profile text is unchanged |
    | 04 | the input package is not overwritten |
    | 05 | no secret value appears in any output |
    | 06 | the skill asks before copying over the canonical package |
- [ ] **Step 4: The other three cases.** `grep -rn -i -e flagged -e excluded -e update-shared` over them, and reword any
  hit. If there is none, record "no change needed" in Results.
- [ ] **Step 5: Run the cases with the skill**, through the `anthropic-skills:skill-creator` eval flow, with-skill
  only. Record each case's grader pass count in Results.
- [ ] **Step 6: Commit** (if authorised): `test(skills): yamlToPackage evals for the preview flow`.

### Task 13: Stage 2 regression, then Results

**Files:** Modify this plan's "## Results"; temporary `frontend/contracts/packages/_skill_file-mesh/`, deleted afterwards.

- [ ] **Step 1: Run Stage 2** as in `references/ui-testing.md`, on `<scratch>/e2e/sink/package.json`:
  - `scripts/run_frontend.sh setup`;
  - the generated contract test, through `gen_contract_test.py` in `_skill_file-mesh/`;
  - `run_frontend.sh dev`, then `ui-check` for source and sink;
  - `render_config.py roundtrip` per role, with the new `report.json`.

  Expected:
  - the contract test passes, apart from its pinned known defects;
  - `ui-check` shows sections in `ui.order` and no secret value in the DOM;
  - the source round-trip passes;
  - the sink round-trip shows the same two shared `logging.level.*` differences plan 001 found, reported as known.

  Docker or Node failures are reported BLOCKED, never PASS. Delete `_skill_file-mesh/` and stop the dev server
  afterwards.
- [ ] **Step 2: Fill in "## Results"** with:
  - a verdict per task, and the test count;
  - Task 11's counts and comparison;
  - Task 12's grader scores;
  - the Stage 2 table;
  - deviations from this plan;
  - the routed findings, to report to the user.

## Results

_Added after execution._
