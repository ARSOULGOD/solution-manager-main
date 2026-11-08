# Mapping guide: the generic engine and how to write a profile

## Contents
- The split: engine vs profile
- Engine rules (apply to every solution)
- Merge rules
- Profile format
- Writing a profile for a new solution
- Worked example: File Mesh

## The split: engine vs profile

The **engine** (`scripts/yaml_to_package.py` + `scripts/common.py`) is identical for every solution.
It knows YAML, types, wording patterns and the package schema. It knows nothing about File Mesh,
Spring, or any solution's layout.

The **profile** (`references/profiles/<solution>.yaml`) is data. It holds every decision the engine
cannot infer reliably: which subtree feeds which component, key form, configPath, roles,
cardinality, enum options, exclusions and known discrepancies. If you are tempted to add an
`if solution == ...` to the engine, the decision belongs in the profile.

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
   field type and are **FLAGGED**, unless the profile's `lists` / `types` says otherwise.
4. **Secrets.** A secret's default is always `null` (I6), in every component the YAML feeds. Each
   withheld value is listed in the report.
5. **ui.** `section` = the last ≤2 path segments between the component root and the leaf, Title Case,
   minus noise suffixes (`_settings`, `config`, `properties`); with no such segments, the root's last
   segment; with no root (env-style), the component's `section_default` or its type. `label` =
   humanised leaf in sentence case, unit suffixes as `(seconds)`; a generic leaf (`enabled`, `host`,
   `port`, ...) is prefixed with its section ("SFTP port"). Known acronyms stay upper case. `helperText`
   follows the reference wording per type (package-schema.md). `order` follows the profile's `ui_order`.
6. **Component, key form and configPath come only from the profile.** A leaf no component matches
   is FLAGGED, never guessed into the nearest one.
7. **Never invent** `when`, `required`, `discriminator`, `pattern`, `min`/`max`, enum options or defaults.
   Offer them as suggestions in the open questions.
8. **Three buckets.** Every leaf is exactly one of MAPPED, FLAGGED (list, duplicate or near-duplicate key,
   type conflict, no component, wrong role, multi-document) or EXCLUDED (by the profile, with a reason).
   The report states whether the buckets add up to the leaf count.
9. **Versions.** `--target-versions` is required. Ask the user every time. Targets must already be in
   `packageVersion`, and in the component's version. Otherwise the engine stops (exit 4), because adding
   a version to one component only is a package-wide decision.
10. **authoring** is recomputed: `totalRows` = field count; `reviewedRows` = fields carried over unchanged
    (untouched components plus merged fields with no change), capped at the previous value. A new
    package starts at 0. **provenance** is preserved. If a signed package changed, an open question asks
    whether to switch to `--provenance dev-mode`; a signature is never written.

## Merge rules

- Only the component(s) of the chosen **role** are rebuilt. Every other component, and every
  metric, action, `virtual`, `valueFrom` and `optionsSource` declaration, is carried over untouched.
- **A field already in the package** keeps its declaration: reviewed `ui` (when `preserve_reviewed_ui`),
  options, predicates, version. The YAML updates its `default` (when `defaults.role_components: yaml`)
  and adds the target versions. A compatible type difference (package `enum`/`secret` vs YAML
  `string`, `string` vs `duration`) keeps the package's type and notes it. Any other difference is a
  FLAGGED type conflict and the field is left unchanged.
- **A `valueFrom` field** keeps its default; a differing YAML value is reported only.
- **A field in the YAML but not the package** is added after the last field of its section, with
  `version` = targets.
- **A field in the package but not the YAML** is kept, and an open question asks whether to remove it.
- **Shared components** (`shared: true` or role `none`): the first run that meets one creates it. Later
  runs only verify. Default differences are reported; new leaves are FLAGGED until the user agrees and
  re-runs with `--update-shared`. Secret defaults are still withheld.
- The engine never overwrites `--package`. It writes `package.json`, `package.diff`, `report.md` and
  `report.json` to `--out-dir`.

## Profile format

```yaml
solution: <kebab-case>             # must equal the package's `solution`
status: draft | confirmed          # the engine only runs `confirmed`
package: <repo-relative path>      # hint: the canonical package.json
roles: [<role>, ...]               # [] means no roles: run with --role none

key_style: full-dotted | relative-to-configPath | env-style   # default for components
ui_order: section-index | running  # default section-index
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
lists: {<pattern>: flag | index | join-comma | list}
enums: {<pattern>: [opt, ...]}     # quote yes/no so they stay strings
types: {<pattern>: <field type>}   # explicit overrides, e.g. map, duration
secret_patterns: [<regex>]         # extra secret leaf names
acronyms: [lvq]                    # extra upper-case words for labels
sections: {<pattern>: <Section Title>}   # where the generic section rule reads badly
exclude: [{match: <pattern>, reason: "..."}]
preserve: {components: [...], fields: [...], metrics: [...]}   # documentation of non-YAML content
defaults: {role_components: yaml | package, shared_components: package}
discrepancies: [{id, what, resolution}]  # every known YAML-vs-package mismatch, with its resolution
```

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
5. Run the engine. Anything FLAGGED goes back to the user; repeat until nothing is FLAGGED or every
   FLAGGED leaf has a recorded decision.

## Worked example: File Mesh

`references/profiles/file-mesh.yaml`. On the two role YAMLs against
`frontend/contracts/packages/file-mesh/package.json` at versions 1,2,3:

| role | leaves | mapped | flagged | excluded | changes to the role component |
|---|---|---|---|---|---|
| source | 110 | 109 | 0 | 1 (FM-D1) | `adapter_id` default from YAML; 2 secret defaults withheld |
| sink | 102 | 99 | 3 (FM-D2) | 0 | `adapter_id` default from YAML; 2 secret defaults withheld |

The other differences are listed in the profile's `discrepancies`:
- shared-component defaults that differ per role (FM-D7, FM-D10, FM-D11);
- the coerced `'true'` strings (FM-D3);
- the joined health-status list (FM-D4);
- the preserved broker selectors (FM-D8).

The `examples/` folder holds both runs.
