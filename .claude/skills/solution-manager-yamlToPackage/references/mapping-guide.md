# Mapping guide: the generic engine and how to write a profile

## Contents
- The split: engine vs profile
- Engine rules (apply to every solution)
- Merge rules
- The answers file
- Profile format
- Writing a profile for a new solution
- Worked example: File Mesh

## The split: engine vs profile

The **engine** (`scripts/yaml_to_package.py` and its modules `common.py`, `style.py`, `learn_samples.py`,
`decisions.py`, `preview_md.py`) is identical for every solution. It knows YAML, types, wording patterns and
the package schema. It knows nothing about File Mesh, Spring, or any solution's layout.

The **profile** (`references/profiles/<solution>.yaml`) is data. It holds every decision the engine
cannot infer reliably: which subtree feeds which component, key form, configPath, roles,
cardinality, enum options and known discrepancies. If you are tempted to add an
`if solution == ...` to the engine, the decision belongs in the profile. What the user decides in the
preview is kept in the profile too, in its decisions block (see "Profile format").

## Engine rules (apply to every solution)

1. **Flatten.** Every scalar becomes a leaf with a dotted key; path segments are kept as a tuple, so a
   key that itself contains dots (`logging.level: {com.solace.jms: INFO}`) keeps its real parent.
   Case is preserved. Env-style top-level keys are leaves already.
2. **YAML 1.2 semantics.** Only `true`/`false` are booleans and only plain digits are ints. `no`, `yes`,
   `on`, `off`, `12:30`, `0755` and dates stay strings. Unquoted yes/no/on/off is reported, because a
   YAML 1.1 reader (PyYAML's default, older Spring) would read them differently.
3. **Types.** bool → `bool`; int → `int`; leaf named password/passwd/pwd/secret/token/api-key/private-key/
   passphrase with a string or null value → `secret`; string `'true'`/`'false'` → `bool` (**reported as
   coerced**); `5m`, `30s`, `PT2H` → `duration`; other strings → `string`; null → `string` (reported as
   assumed). `enum` only when the profile supplies options. Floats, lists and empty maps have no
   field type and are **asked in the preview** (rule 7), unless the profile's `lists` / `types` says otherwise.
4. **Secrets.** A secret's default is always `null` (I6), in every component, including existing package
   fields the YAML does not feed. Each withheld value is listed, never its value.
5. **ui.** `section` = the last ≤2 path segments between the component root and the leaf, Title Case,
   minus noise suffixes (`_settings`, `config`, `properties`); with no such segments, the root's last
   segment; with no root (env-style), the component's `section_default` or its type. `label` =
   humanised leaf in sentence case, unit suffixes as `(seconds)`; a generic leaf (`enabled`, `host`,
   `port`, ...) is prefixed with its section ("SFTP port"). Known acronyms stay upper case. `order` and
   `helperText` follow the style (rule 8).
6. **Three outcomes.** Every YAML key, in every YAML document, ends up as exactly one of:
   - **placed:** it becomes a field of the component the profile (or the user's answer) names. Component,
     key form and configPath come only from the profile or the user, never from a guess;
   - **open:** it is a question in the preview, with fixed choices;
   - **removed by you:** the user's recorded decision, with a date and a reason.

   `apply` refuses while anything is open (exit 5). Its no-trim gate is a **read-back**: it reads the
   written package and checks that every placed key is a field of its component and every removed key is
   absent. Removing a key also drops its field if the package already has one. A mismatch fails the run
   (exit 1).
7. **Questions.** Every reason a key used to be held back is a question. "Remove it (you), with a reason"
   is always a choice. The preview prints each one with its choices and an answer id:

   | kind | asked because | choices (besides `remove`) | answered in |
   |---|---|---|---|
   | `no-component` | no component claims the key (also a key outside a relative `configPath`) | `place` (existing or new component) | `place` |
   | `doc2` | the key is only in YAML document 2 or later | `place` | `place` |
   | `wrong-role` | another role's key (`role_exclusive`, or another role's component) | `place` | `place` |
   | `excluded` | an old profile's `exclude` entry: keep it or remove it? | `place` | `place` |
   | `shared-new` | a new key in an existing shared component | `all-roles` · `this-role` · `role-component` | `place` |
   | `role-exclusive-shared` | a role-exclusive key whose component is shared | `this-role` · `role-component` | `place` |
   | `list` | a list with no `lists` rule | `list` · `join-comma` · `index` (offered by item type) | `store` |
   | `empty-map` | an empty mapping | `map` | `store` |
   | `decimal` | a decimal value (the schema has no decimal type) | `string`, keeping the text | `store` |
   | `unsupported` | another value with no field type | `string` | `store` |
   | `enum-value` | the value is not in the enum's options | `add-option` · `string` | `store` |
   | `type-conflict` | the YAML type conflicts with the package's declaration | `package-type` · `yaml-type` | `store` |
   | `dup-key` | the same key written twice in one mapping (the field exists either way) | `first` · `second` | `store` |
   | `spelling` | keys of one component differ only in case, `-` or `_` (all stay open until the user picks) | (remove the spellings not kept) | `remove` |
   | `suggest` | a learned suggestion (rule 9) | `accept` · `reject` | `suggestions` |
   | `restyle` | the package is not in the skill's style (rule 8) | `accept` · `refuse` | `restyle` |
   | `provenance` | a signed package would change | `keep` · `dev-mode` | `provenance` |

   A shared field that serves another role only (`role` set) is not this run's target: its key raises
   `shared-new` with `all-roles` · `role-component` · `remove`. A mapping (not a leaf) written twice is bad
   input (exit 2), because the keys under one copy would be lost. **Not blocking**, listed "for your
   information": shared defaults that differ, coerced values, reviewed labels kept, and fields in the package
   but not in this YAML (kept unless the user removes them).
8. **Style (db-designer)** for every field the skill writes:
   - `ui.order` is the field's position inside its section, counting from 0 in file order;
   - `ui.helperText` is written only when the YAML line has a comment of four or more words, and then it is
     the comment alone, with no stock sentence. A comment that repeats a secret value is never used (I6);
   - `type: secret` always comes with `secret: true`, and the default stays empty;
   - `loggerPrefix` comes only from the profile; `actions` and `metrics` are never derived (existing ones
     are kept).

   **The restyle** is one preview question for the whole package. Per component (profile `preserve` ones
   too), it lists how many positions change and how many stock helper sentences are removed. A stock sentence
   is one that exactly equals the stock sentence for the field's current label, section and type; any other
   helper text is kept. Accepted, every component gets the db-designer style. Refused, the decision is
   remembered and each component keeps its detected style (running counter, section index, or position
   within section); new fields follow it, and a new component gets the db-designer style. Adding
   `secret: true` to an existing secret field is not part of the restyle; it is listed wherever it applies.
9. **Learned suggestions (strict).** The samples are every `package.json` under
   `frontend/contracts/packages/` (found by walking up from the working folder, or `--samples <folder>`),
   minus the package of the solution being generated and `virtual` fields. A component reused across samples
   (`http-client`, `cache`) counts once. A YAML key matches a sample field when the last segments of both,
   split into words (camelCase, `-`, `_`) and lower-cased, are the same words in the same order
   (`solace.java.host` matches cache's `solace.host`). `required` or `ui.advanced` is suggested when more
   than half of the matched sample fields carry it, with every matched field as evidence
   (`<sample>/<component>/<field>: required | optional | not marked`, plus `, only when K = v` for a `when`;
   for example `cache/cache-engine/solace.host: required`). No suggestion for a field that
   already has the attribute (an explicit `false` counts) or for one already decided. Accepted, the field gets
   the attribute; rejected, it is remembered and never asked again, even if the samples change. **Never
   suggested or invented:** `when`, `discriminator`, `pattern`, `min`/`max`, enum options and defaults; the
   user can add any of them as an amendment. No samples found: the preview says so and suggests nothing.
10. **Versions.** `--target-versions` is required. Ask the user every time. Targets must already be in
    `packageVersion`, and in the component's version. Otherwise the engine stops (exit 4), because adding
    a version to one component only is a package-wide decision.
11. **authoring** is recomputed: `totalRows` = field count; `reviewedRows` = fields carried over unchanged
    (equal to their input copy), capped at the previous value. A new package starts at 0. **provenance** is
    preserved; a signed package that would change raises the `provenance` question. A signature is never
    written.

## Merge rules

- Only the component(s) of the chosen **role** are rebuilt. Every other component, and every
  metric, action, `virtual`, `valueFrom` and `optionsSource` declaration, is carried over untouched.
- **A field already in the package** keeps its declaration: reviewed `ui` (when `preserve_reviewed_ui`),
  options, predicates, version. The YAML updates its `default` (when `defaults.role_components: yaml`)
  and adds the target versions. A compatible type difference (package `enum`/`secret` vs YAML
  `string`, `string` vs `duration`) keeps the package's type and notes it. Any other difference is a
  `type-conflict` question.
- **A `valueFrom` field** keeps its default; a differing YAML value is reported only.
- **A field in the YAML but not the package** is added in its section, with `version` = targets, numbered
  in the component's style.
- **A field in the package but not the YAML** is kept and listed for information. The user can remove it
  (`remove` by component and field).
- **A removal drops an existing field**, in this role's component or a shared one, never another role's
  copy. The preview shows it, and the read-back checks it.
- **Shared components** (`shared: true` or role `none`): the first run that meets one creates it. Later
  runs verify it and report default differences (the package's value is kept). A new key in an existing
  shared component is a `shared-new` question: add it for every role, for this role only (field `role`), or
  move it to the role component. Secret defaults are still withheld.
- **Relocation** (profile `relocate`): fields matching the pattern are removed from the named shared
  component on every run, and the role components whose `match` claims them carry their own copy. A
  relocated field keeps its reviewed `ui` (label, section, helper text) in its new home; so does a field
  whose key already exists in a sibling role component. When several components claim the same prefix,
  the component of the run's own role wins.
- **Amendments** apply last, after the restyle. An amendment never gives a secret a default.
- The engine never overwrites an input. `preview` writes `preview.md` and `preview.json`; `apply` writes
  `package.json`, `package.diff`, `report.md`, `report.json` and `profile.yaml`, all to `--out-dir`.

## The answers file

The skill writes `<out-dir>/answers.yaml` from the user's chat replies and passes it with `--answers`.
Every section is optional; the shapes are in `SKILL.md` ("The answers file"). The rules:

- **`remove`**: `{key, reason}` for a YAML key, or `{component, field, reason}` for a field only the package
  has. A reason is required. A spelling pair is answered by removing the spellings the user does not keep.
- **`place`**: `{key, component}`, always naming `component`. For `role-component`, `component` is this
  role's component. `scope: all-roles | this-role` is only for a shared component, and only a scope the
  question offered. With `new: {role, configPath, cardinality}`, `component` is the new component's type.
- **`store`**: `{key, as}` for every other key-level choice. A key may carry several `store` answers, one per
  question (a duplicated list needs `first`/`second` and a list shape); two answers to one question are refused.
- **`suggestions`**: `{key, attribute: required | advanced, decision: accept | reject}`.
- **`amend`**: `{key, set, reason?}` or `{component, field, set, reason?}`. `set` takes `type`, `default`,
  `required`, `discriminator`, `when`, `pattern`, `min`, `max`, `options`, `ui.label`, `ui.section`, `ui.order`,
  `ui.advanced`, `ui.helperText` and `ui.readOnly`; null removes an attribute. A secret's default is refused.
  A key stored as one field per item (`index`) is amended by component and field.
- **`restyle`**: `accept | refuse`. **`provenance`**: `keep | dev-mode`, only when the signature question is asked.
- **`forget`**: `{key}` drops the remembered `removed`, `placed` and `stored` decisions for that key (all-roles
  ones and this role's); `{component, field, attribute}` drops a remembered suggestion. The question is asked again on the next preview.

Answers are **validated in stages**, each against a build with the earlier stages applied, because some
questions exist only after an earlier answer: (1) `remove`, `place`, `forget`; (2) the `first`/`second`
picks; (3) the other `store` answers; (4) `amend`; (5) `suggestions`, `restyle`; (6) `provenance`. A final
check on the full build refuses any answer that settles nothing. Any problem exits 2, one line per entry
(`error: answers: store demo.alpha.ratio: 'map' was not offered`). Refused: a key not in the YAML, a choice
not offered, a secret default, an answer to a question nobody asked, a key both placed and removed.

## Profile format

```yaml
solution: <kebab-case>             # must equal the package's `solution`
status: draft | confirmed          # the engine only runs `confirmed`
package: <repo-relative path>      # hint: the canonical package.json
roles: [<role>, ...]               # [] means no roles: run with --role none

key_style: full-dotted | relative-to-configPath | env-style   # default for components
preserve_reviewed_ui: true         # existing fields keep their reviewed ui
explicit_versions: true            # write version[] on every new field

components:                        # declaration order = order of new components in the package
  - type: <component type>
    role: <role> | none
    shared: true                   # optional; role none implies shared
    cardinality: exactly-one | one-of-many | zero-or-more
    configPath: ""                 # key_style relative strips this prefix from keys
    key_style: env-style           # optional per-component override
    loggerPrefix: com.example
    section_default: General       # optional, for leaves with no parent group
    match: [prefix.path, "GLOB_*"] # longest plain prefix wins; globs match the whole key

role_exclusive:                    # leaves that belong to some roles only
  - {match: <pattern>, roles: [sink], reason: "..."}
lists: {<pattern>: flag | index | join-comma | list}   # flag (the default) asks in the preview
enums: {<pattern>: [opt, ...]}     # quote yes/no so they stay strings
types: {<pattern>: <field type>}   # explicit overrides, e.g. map, duration
secret_patterns: [<regex>]         # extra secret leaf names
acronyms: [lvq]                    # extra upper-case words for labels
sections: {<pattern>: <Section Title>}   # where the generic section rule reads badly
relocate: [{match: <pattern>, from: <shared component>, reason: "..."}]   # move fields out of a shared component
preserve: {components: [...], fields: [...], metrics: [...]}   # documentation of non-YAML content
defaults: {role_components: yaml | package, shared_components: package}
discrepancies: [{id, what, resolution}]  # every known YAML-vs-package mismatch, with its resolution

# >>> decisions: written by the skill from your preview answers; do not edit by hand >>>
decisions:
  removed:     [{key, role, date, reason}]          # or {component, field, role, date, reason}
  placed:      [{key, component, role, date}]       # + scope or new when the answer had them
  stored:      [{key, as, role, date}]
  suggestions: [{component, field, attribute, decision, date, evidence}]
  amendments:  [{component, field, set, date, reason}]
  style:       {restyle: accepted | refused, date}
  provenance:  {choice, date}
# <<< decisions <<<
```

`exclude` and `ui_order` are removed (an old profile's `exclude` entries are asked, the other is ignored).
An exclusion is now the user's "removed by you" decision, asked as an `excluded` question, and there is one
style (rule 8).

**The decisions block** sits at the end of the profile between the two marker comments. `apply` writes it
into `<out-dir>/profile.yaml` from the remembered block plus this run's answers; everything above the
markers stays byte-for-byte (PyYAML cannot round-trip comments, so only the block is regenerated). A new
profile ends with an empty block (`decisions: {}`). A decision on a shared key applies to every role; one on
a role-component key carries its `role`. On the next run remembered decisions are applied first, so only new
keys, new suggestions or changed situations are asked. **Never edit the block by hand:** to revisit a
decision, give a `forget` answer (or a new answer for the same key, which replaces it).

Patterns: a plain pattern is a dotted prefix on a segment boundary (`file.source` matches
`file.source.x`, not `file.sourcex`); a pattern containing `* ? [` is a glob over the whole key
(`file.*.sftp_settings.port`, `CACHE_*`).

## Writing a profile for a new solution

1. `scripts/profile_proposer.py --yaml <file> [--yaml <other-role file>] --solution <name> --out <scratch>/draft.yaml`
2. Show the user: structure, closest existing profile or package and why, the draft, the numbered questions.
3. **Stop.** Do not decide roles, component boundaries, key style, cardinality, enums or list handling
   yourself, even when one reading looks obvious.
4. Fold the answers into the draft. Record every resolved mismatch under `discrepancies` with an id
   (`<PREFIX>-D<n>`), set `status: confirmed`, and save to `references/profiles/<solution>.yaml`.
5. Run flow A (`SKILL.md`): preview, answers, preview until nothing is open, then `apply`. The answers land
   in the profile's decisions block, not in its hand-written part.

## Worked example: File Mesh

`references/profiles/file-mesh.yaml`. The first preview of the source YAML against
`frontend/contracts/packages/file-mesh/package.json` at versions 1,2,3, with the samples in
`frontend/contracts/packages` (8 packages, 103 distinct fields), exits 5:

| role | keys | placed | removed by you | open | questions |
|---|---|---|---|---|---|
| source | 110 | 108 | 0 | 2 | 7 |

The 7 questions:
1. `spelling`: FM-D1, `useOutputDestinationSolaceCredentials` vs the lowercase
   `useoutputdestinationsolacecredentials` (the 2 open keys). The profile used to exclude the lowercase one;
   now the user picks.
2. to 5. `suggest` `required` on `spring.application.name`, `solace.connector.management.session.host`,
   `solace.java.host` and `logging.file.name`, each with its evidence (for example
   `cache/cache-engine/solace.host: required`).
6. `restyle`: new positions on 138 of 156 fields, stock helper text removed from 154, 2 custom kept.
7. `provenance`: the package is signed and would change.

For information: 7 shared defaults differ (package kept), 6 values coerced, `secret: true` added to 8 fields.
The other differences are listed in the profile's `discrepancies`:
- shared-component defaults that differ per role (FM-D7, FM-D10, FM-D11);
- the coerced `'true'` strings (FM-D3);
- the joined health-status list (FM-D4);
- the preserved broker selectors (FM-D8).

The `examples/` folder holds the runs.
