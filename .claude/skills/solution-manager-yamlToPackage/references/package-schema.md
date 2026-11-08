# Solution Package shape and validation gates

Derived from the nine reference packages (`frontend/contracts/packages/*/package.json`) and the
canonical schema `contracts/solution-package.schema.json`. **The schema is the authority**; this page
is a reading aid. If they disagree, the schema wins. Say so in the report.

## Contents
- Top level
- Component
- Field
- What varies between solutions (variation points)
- Validation gates (`scripts/validate_package.py`)

## Top level

| key | required | notes |
|---|---|---|
| `solution` | yes | kebab-case, `^[a-z][a-z0-9-]*$` |
| `packageVersion` | yes | non-empty, ordered, unique integers, e.g. `[1, 2, 3]` |
| `appliesTo` | yes | semver range of MI versions, e.g. `">=1.0 <2.0"` |
| `provenance` | no | `{author: solace \| dev-mode, signature?}`. `solace` needs a signature; **never fabricate one** |
| `authoring` | no | `{reviewedRows, totalRows, source?}` (UI-D17). The references count extraction rows here, so their totals do not equal field counts. The engine writes the field count |
| `components` | yes | at least one |

## Component

| key | required | notes |
|---|---|---|
| `type` | yes | kebab-case name, unique in the package |
| `cardinality` | yes | `exactly-one` · `one-of-many` · `zero-or-more` |
| `configPath` | yes | property prefix SM writes under; may be `""` |
| `role` | no | package-defined string (`source`, `sink`, ...); `none` or absent means shared |
| `loggerPrefix` | no | required if `logs.loggers` is declared |
| `version` | no | subset of `packageVersion`; absent means every package version |
| `fields` | no | see below |
| `metrics`, `actions`, `restartSignal`, `health`, `logs`, `configFile` | no | never generated from YAML; always preserved |

## Field

| key | notes |
|---|---|
| `key` | config property path relative to `configPath`. Never a display label |
| `type` | `string` · `int` · `bool` · `enum` · `secret` · `duration` · `list` · `map` · `table` · `yaml` |
| `default` | string, number, bool or null. **Always null for a secret** (I6) |
| `ui` | `{section, order, label, helperText}` (+ optional `readOnly`) |
| `version` | subset of the component's effective versions |
| `options` | enum values. Exactly one of `options` / `optionsSource` for an enum |
| `optionsSource` | catalog-backed options, e.g. `{kind: sm-catalog, entity: connection, connectionType: solace-broker}` |
| `valueFrom` | `{ref: <virtual field key>, attribute}`: value populated from a Connection Object |
| `virtual` | drives the form, never written to the config file |
| `required`, `discriminator`, `when`, `pattern`, `min`, `max` | **never invented**; only from the profile or the user |
| `when` | `{key: value}`: equality only, AND-ed, keys in the same component |
| `discriminator` | only on `enum` or `bool` |
| `columns` | `type: table` only |
| `yamlSubtree` | `type: yaml` only |
| `role` | optional per-field role inside a shared component |

### helperText wording (from the references)

| type | pattern |
|---|---|
| string | Enter the value for “{label}” in the “{section}” section. |
| int | Enter the numeric value for “{label}” in the “{section}” section. |
| bool | Turn “{label}” on or off in the “{section}” section. |
| enum | Select the “{label}” option in the “{section}” section. |
| secret | Enter “{label}” for the “{section}” section. This value is treated as sensitive. |
| duration | Enter the duration for “{label}” in the “{section}” section. |

A descriptive YAML comment (four or more words) is appended as a second sentence.

### ui.order

`project()` (frontend/contracts/view-model/project.ts) places a section by the `ui.order` of the
**first field it sees in that section**, and orders fields within a section by declaration order.
Cache, db-designer and the other hand-authored packages write the **section index** on every field.
File Mesh writes a **running per-field counter**. Both render the same; the profile's `ui_order` picks
one per solution.

## What varies between solutions (variation points)

Each row is something the engine must NOT assume. It comes from the profile.

| variation point | file-mesh | cache | db-designer | others (orders, payments, ...) |
|---|---|---|---|---|
| key form | full dotted YAML path | relative to configPath (`mode`, `solace.host`) | env-style UPPER_SNAKE | env-style |
| configPath | `""` (role and Spring components); `cache` for its env-style cache component | `cache-engine` | `db-connector` | = component type |
| roles | `source`, `sink` (one-of-many) + 8 shared `none` components | none (role absent) | `none` | `none` |
| cardinality | one-of-many (roles), zero-or-more (shared) | exactly-one | exactly-one | exactly-one, one-of-many, zero-or-more |
| ui.order | running counter | section index | section index | section index |
| predicates | none (generated, flat) | `when`, `discriminator`, `virtual`, `valueFrom` | `when`, `discriminator`, `pattern`, `min`/`max`, `table` | mixed |
| metrics/actions | metrics on role components | both | both | both |
| version scoping | component + field `version[]`, v4 only on `solace` | `[6, 7]` with v6-only and v7-only fields | `[1]` | components at `[1,2]` and `[1,2,3]`/`[2,3]` |
| mixed key styles | env-style `cache` component inside a Spring-style package | n/a | n/a | env-style `cache` reused across packages |

## Validation gates (`scripts/validate_package.py`)

Errors fail the run; warnings are reported and left for a human.

**Errors**
1. JSON Schema (when `contracts/solution-package.schema.json` is found or passed).
2. `packageVersion` non-empty, integer, ordered, unique.
3. Component `version` ⊆ `packageVersion`; field `version` ⊆ its component's version.
4. **No configPath double prefix:** with a non-empty `configPath`, a key must not start with `configPath.`, or it renders as `configPath.configPath.key`.
5. **Keys unique per component, case-insensitively.**
6. **Every password is a secret:** a key whose leaf ends in password/passwd/pwd must be `type: secret`.
7. enum has `options` or `optionsSource`; an enum default is one of its options.
8. `discriminator` only on enum/bool.
9. `when` keys name a field of the same component; values are in that field's options.
10. `valueFrom.ref` names a field of the same component.
11. `provenance.author: solace` needs a signature.

**Warnings**
- a secret field with a non-null default (I6);
- the latest package version carried by only some components (an instance at that version loses the others);
- a package version carried by no component;
- `authoring.totalRows` ≠ field count;
- a field without `ui.section` / `ui.label`.

When working inside the Solution Manager repo, also run the repo's own gates:
`python3 contracts/validation/validate-package.py --dev-mode <package.json>` (adds the reachability,
gate-12 and gate-14 cross-reference checks).
