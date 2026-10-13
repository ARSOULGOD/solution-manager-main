# Connection Objects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an operator configure a Solace broker or remote server once in a Connection Objects catalog, reference it from many solution instances, and have the Solution Package declare — generically — which Designer fields that connection populates.

**Architecture:** A Connection Type Descriptor is SM-owned data declaring a type's attributes using the *existing* `$defs/field` vocabulary, so the catalog form and the Designer share one renderer. The Solution Package declares a `virtual` selector field (`optionsSource.entity: "connection"` + `connectionType`) and marks target fields with `valueFrom: {ref, attribute}`. `project()` gains exactly one generic rule resolving those bindings under a fixed precedence; it learns nothing about what a broker is.

**Tech Stack:** TypeScript (strict), React 18 + Vite + MUI, Vitest + Testing Library, JSON Schema 2020-12 (ajv in the frontend, `jsonschema` in Python gates), bash for `contracts/run-checks.sh`.

**Spec:** [`docs/design/dynamic-ui/connection-objects.md`](../../design/dynamic-ui/connection-objects.md) — decisions `UI-D27`, `UI-D28`, `UI-D29`. Read it alongside this plan; the plan argues from it and does not restate its rationale.

## Implementation status

Tasks 1–11 are implemented in commits `80a50b7` through `3ba863c`; reports are in
`docs/plans/frontend/reports/task-131-01-report.md` through `task-131-11-report.md`. The step
checkboxes below are the original execution script and were not retroactively rewritten because
the final repository-wide verification is currently blocked by unrelated working-tree/environment
conditions: an uncommitted File Mesh package rewrite, unavailable `localStorage` in the full Vitest
run, and the external File Mesh SQL dump missing from the strict contract run. Connection Objects
focused tests, typecheck, lint, build, view-model tests, binding gates, and descriptor validation
pass.

---

## Global Constraints

Every task's requirements implicitly include this section.

- **⚠️ `project()` EXISTS TWICE and both copies must change in lockstep.** The canonical package is `contracts/view-models/src/project.ts` (482 lines). The frontend resolves `@contracts/*` to `frontend/contracts/` (see `frontend/vite.config.ts:18`, `frontend/tsconfig.json:25`), a **separate** 442-line implementation at `frontend/contracts/view-model/project.ts`. They have already diverged in prose. Changing one only will leave the frontend green and the contract suite red, or vice versa. Tasks 4 and 5 are deliberately split so a reviewer gates each.
- **`contracts/` is single-writer.** A contract change is a deliberate reviewed change, never a convenience edit — it is consumed by four modules. Run `./contracts/run-checks.sh --strict` before and after every task that touches `contracts/`.
- **`I5` — organization scope is never derived from client input.** Connection list endpoints take no client-supplied scope. Any fixture or repository that appears to send `org`/`subOrg`/`env` as a filter parameter is modelling a *server-applied* scope and must say so in a comment.
- **`I6` — secrets never land in config storage.** A secret attribute lives only in `secretRefs`. No `FieldValue` arm may carry a resolved secret; `FieldValue` has exactly four arms and a fifth appearing means `I6` was breached upstream.
- **`I1` — no MI development.** Nothing in this plan asks an MI for a new endpoint or new telemetry.
- **`I3` — decision IDs are immutable.** `UI-D26` is taken (plan 129). This work is `UI-D27`/`UI-D28`/`UI-D29`. Never renumber, never edit a decision's meaning in place.
- **⚠️ `connection.*` RBAC verbs are NOT in LOCKED `D18`.** They ship as `status: "proposed"` following the precedent in `docs/design/frontend/reference/app/admin_data.jsx`, which models unratified permissions with tone chips and states *"Adding a verb is DATA, not a layout change."* **Do not** add them to any ratified catalog, and do not gate any screen on them being grantable.
- **The visual reference has no Connections screen.** Layouts come from spec §8, built by analogy to Targets and Admin lists. Visual-regression baselines stay blocked for these screens (spec §9.5). Do not invent brand paint; `FE-D5` still blocks it.
- **Commit discipline:** tasks commit directly to `dev/frontend`, no per-task branch or PR. **Ask the developer before every commit**, not just the first.
- **Per-task reports:** after each task write `docs/plans/frontend/reports/task-131-NN-report.md` recording what was built, what was verified, and anything the task discovered that the plan got wrong.
- **Test commands:** frontend `cd frontend && npm test -- <path>`; contract engine `cd contracts/view-models && npx vitest run <path>`; full contract suite `./contracts/run-checks.sh --strict`.

---

## File Structure

| Path | Responsibility | Task |
|---|---|---|
| `contracts/connection-types/solace-broker.json` | Descriptor: Solace broker attributes | 1 |
| `contracts/connection-types/remote-server.json` | Descriptor: remote server attributes | 1 |
| `contracts/connection-types/descriptor.schema.json` | JSON Schema the descriptors validate against | 1 |
| `contracts/solution-package.schema.json` | `connectionType`, `valueFrom`, `ui` additions; `broker` retired | 2 |
| `frontend/contracts/solution-package/types.ts` · `validate.ts` · `normalize.ts` | Frontend mirror of the schema change | 2 |
| `contracts/run-checks.sh` | Gates 12–16 | 3 |
| `contracts/view-models/src/types.ts` · `project.ts` | Canonical engine: `Catalog` entries, `valueFrom` resolution | 4 |
| `frontend/contracts/view-model/types.ts` · `project.ts` | Frontend engine: identical behaviour, fixture parity | 5 |
| `frontend/src/shared/testdoubles/data/connections.ts` | Fixture connections + descriptors | 6 |
| `frontend/src/features/connections/connectionsRepository.ts` | Read/write seam for the catalog | 6 |
| `frontend/src/features/connections/ConnectionsPage/` | Catalog list, type tabs, generated columns | 7 |
| `frontend/src/features/connections/ConnectionDrawer/` | Descriptor-driven detail/edit form | 8 |
| `frontend/src/features/inventory/instances/declareForm.ts` · `DeclareInstanceDialog.tsx` | `promoteToDeclare` rendering, scope-filtered selectors | 9 |
| `frontend/src/features/designer/FieldRow/` (+ `SectionCard`) | Provenance chips, override/revert | 10 |
| `frontend/src/features/designer/ConnectionDriftBanner/` | `UI-D29` drift banner | 11 |

---

## Task 1: Connection Type Descriptors

**Files:**
- Create: `contracts/connection-types/descriptor.schema.json`
- Create: `contracts/connection-types/solace-broker.json`
- Create: `contracts/connection-types/remote-server.json`
- Test: `contracts/connection-types/validate-descriptors.py`

**Interfaces:**
- Consumes: nothing.
- Produces: the descriptor file format. Tasks 3, 4, 6 and 8 read descriptors. A descriptor is `{connectionType: string, label: string, descriptorVersion: number, attributes: PackageField[]}` where `attributes` reuses `$defs/field` from the package schema minus `valueFrom`, `virtual`, `optionsSource` and `yamlSubtree`.

- [ ] **Step 1: Write the failing validator**

Create `contracts/connection-types/validate-descriptors.py`:

```python
#!/usr/bin/env python3
"""UI-D27 gate: every descriptor validates, attribute keys are unique, and a
`when` predicate names a discriminator declared in the same descriptor."""
import json, pathlib, sys
from jsonschema import Draft202012Validator

here = pathlib.Path(__file__).parent
schema = json.loads((here / "descriptor.schema.json").read_text())
validator = Draft202012Validator(schema)
failures = []

for path in sorted(here.glob("*.json")):
    if path.name == "descriptor.schema.json":
        continue
    doc = json.loads(path.read_text())
    for err in validator.iter_errors(doc):
        failures.append(f"{path.name}: {'/'.join(map(str, err.path))}: {err.message}")

    keys = [a["key"] for a in doc.get("attributes", [])]
    dupes = {k for k in keys if keys.count(k) > 1}
    if dupes:
        failures.append(f"{path.name}: duplicate attribute keys: {sorted(dupes)}")

    discriminators = {a["key"] for a in doc.get("attributes", []) if a.get("discriminator")}
    for attr in doc.get("attributes", []):
        for pred_key in (attr.get("when") or {}):
            if pred_key not in discriminators:
                failures.append(
                    f"{path.name}: {attr['key']}.when names '{pred_key}', "
                    f"which is not a discriminator in this descriptor"
                )

if failures:
    print("\n".join(f"  FAIL {f}" for f in failures))
    sys.exit(1)
print(f"  OK: {len(list(here.glob('*.json'))) - 1} descriptors valid")
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python3 contracts/connection-types/validate-descriptors.py`
Expected: FAIL — `FileNotFoundError` for `descriptor.schema.json` (nothing exists yet).

- [ ] **Step 3: Write the descriptor schema**

Create `contracts/connection-types/descriptor.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://solace.com/sm/connection-type-descriptor.schema.json",
  "title": "Connection Type Descriptor (UI-D27)",
  "description": "SM-owned, never package-supplied. Attributes reuse UI-D14's field vocabulary so the catalog form and the Designer share one renderer.",
  "type": "object",
  "required": ["connectionType", "label", "descriptorVersion", "attributes"],
  "additionalProperties": false,
  "properties": {
    "connectionType": { "enum": ["solace-broker", "remote-server"] },
    "label": { "type": "string", "minLength": 1 },
    "descriptorVersion": { "type": "integer", "minimum": 1 },
    "attributes": {
      "type": "array",
      "minItems": 1,
      "items": { "$ref": "#/$defs/attribute" }
    }
  },
  "$defs": {
    "attribute": {
      "type": "object",
      "required": ["key", "type"],
      "additionalProperties": false,
      "properties": {
        "key": { "type": "string", "pattern": "^[a-zA-Z][a-zA-Z0-9]*$" },
        "type": { "enum": ["string", "int", "bool", "enum", "duration", "secret"] },
        "options": { "type": "array", "items": { "type": ["string", "number", "boolean"] } },
        "default": { "type": ["string", "number", "boolean", "null"] },
        "required": { "type": "boolean" },
        "secret": { "type": "boolean" },
        "discriminator": { "type": "boolean" },
        "when": { "type": "object", "additionalProperties": { "type": ["string", "number", "boolean"] } },
        "min": { "type": "number" },
        "max": { "type": "number" },
        "pattern": { "type": "string" },
        "ui": {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "label": { "type": "string" },
            "helperText": { "type": "string" },
            "order": { "type": "integer" },
            "listColumn": { "type": "boolean" }
          }
        }
      },
      "allOf": [
        {
          "description": "a secret-typed attribute must be marked secret, and vice versa (I6)",
          "if": { "properties": { "type": { "const": "secret" } }, "required": ["type"] },
          "then": { "properties": { "secret": { "const": true } }, "required": ["secret"] }
        },
        {
          "description": "an enum attribute must declare its options",
          "if": { "properties": { "type": { "const": "enum" } }, "required": ["type"] },
          "then": { "required": ["options"] }
        }
      ]
    }
  }
}
```

- [ ] **Step 4: Write the two descriptors**

Create `contracts/connection-types/solace-broker.json`:

```json
{
  "connectionType": "solace-broker",
  "label": "Solace broker",
  "descriptorVersion": 1,
  "attributes": [
    { "key": "host", "type": "string", "required": true,
      "ui": { "label": "Host", "helperText": "tcps://host", "order": 1, "listColumn": true } },
    { "key": "port", "type": "int", "default": 55555, "min": 1, "max": 65535,
      "ui": { "label": "Port", "order": 2 } },
    { "key": "vpn", "type": "string", "default": "default",
      "ui": { "label": "Message VPN", "order": 3, "listColumn": true } },
    { "key": "username", "type": "string",
      "ui": { "label": "Client username", "order": 4 } },
    { "key": "password", "type": "secret", "secret": true,
      "ui": { "label": "Password", "order": 5 } }
  ]
}
```

Create `contracts/connection-types/remote-server.json`:

```json
{
  "connectionType": "remote-server",
  "label": "Remote server",
  "descriptorVersion": 1,
  "attributes": [
    { "key": "host", "type": "string", "required": true,
      "ui": { "label": "Host", "order": 1, "listColumn": true } },
    { "key": "port", "type": "int", "default": 22, "min": 1, "max": 65535,
      "ui": { "label": "Port", "order": 2 } },
    { "key": "username", "type": "string", "required": true,
      "ui": { "label": "Username", "order": 3, "listColumn": true } },
    { "key": "authMethod", "type": "enum", "required": true, "discriminator": true,
      "options": ["basic", "private-key"], "default": "basic",
      "ui": { "label": "Authentication", "order": 4, "listColumn": true } },
    { "key": "password", "type": "secret", "secret": true,
      "when": { "authMethod": "basic" },
      "ui": { "label": "Password", "order": 5 } },
    { "key": "privateKey", "type": "secret", "secret": true,
      "when": { "authMethod": "private-key" },
      "ui": { "label": "Private key", "helperText": "PEM. Stored in the secret backend, never displayed.", "order": 5 } },
    { "key": "passphrase", "type": "secret", "secret": true,
      "when": { "authMethod": "private-key" },
      "ui": { "label": "Key passphrase", "helperText": "Optional.", "order": 6 } }
  ]
}
```

- [ ] **Step 5: Run the validator to verify it passes**

Run: `python3 contracts/connection-types/validate-descriptors.py`
Expected: `OK: 2 descriptors valid`

- [ ] **Step 6: Prove the `when`-names-a-discriminator check actually fails**

Temporarily change `remote-server.json`'s `passphrase.when` to `{ "authMode": "private-key" }` and re-run.
Expected: FAIL naming `passphrase.when names 'authMode'`. **Revert the edit** and re-run to green.

This step exists because of lesson `L7` in this repo: a check that cannot fail is not a check.

- [ ] **Step 7: Commit** (ask the developer first)

```bash
git add contracts/connection-types/
git commit -m "feat(contracts): connection type descriptors for UI-D27

Solace broker and remote server descriptors, reusing UI-D14's field
vocabulary so the catalog form and the Designer share one renderer.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: Solution Package schema — `connectionType`, `valueFrom`, `ui` additions

**Files:**
- Modify: `contracts/solution-package.schema.json` (`$defs/optionsSource`, `$defs/field`, `$defs/ui`, `$defs/action`)
- Modify: `frontend/contracts/solution-package/types.ts`
- Modify: `frontend/contracts/solution-package/validate.ts`
- Create: `contracts/validation/negative/valueFrom-unknown-ref.json`
- Create: `contracts/validation/negative/valueFrom-secret-mismatch.json`
- Create: `contracts/validation/negative/connection-without-type.json`

**Interfaces:**
- Consumes: Task 1's descriptor format.
- Produces: `OptionsSource` gains `readonly connectionType?: 'solace-broker' | 'remote-server'`; `PackageField` gains `readonly valueFrom?: { readonly ref: string; readonly attribute: string }`; `UiHints` gains `readonly listColumn?: boolean` and `readonly promoteToDeclare?: boolean`. Tasks 4 and 5 consume all three.

- [ ] **Step 1: Write the failing negative fixtures**

Create `contracts/validation/negative/connection-without-type.json` — a package whose selector omits `connectionType`:

```json
{
  "solution": "cache",
  "packageVersion": [7],
  "components": [{
    "type": "cache-engine",
    "configPath": "application.yml",
    "fields": [{
      "key": "solaceBroker",
      "type": "enum",
      "virtual": true,
      "optionsSource": { "kind": "sm-catalog", "entity": "connection" },
      "ui": { "section": "Broker", "label": "Broker" }
    }]
  }]
}
```

Create `contracts/validation/negative/valueFrom-unknown-ref.json` — `valueFrom.ref` names a field that does not exist:

```json
{
  "solution": "cache",
  "packageVersion": [7],
  "components": [{
    "type": "cache-engine",
    "configPath": "application.yml",
    "fields": [{
      "key": "solace.host",
      "type": "string",
      "valueFrom": { "ref": "noSuchSelector", "attribute": "host" },
      "ui": { "section": "Broker", "label": "Host" }
    }]
  }]
}
```

Create `contracts/validation/negative/valueFrom-secret-mismatch.json` — a non-secret field bound to a secret attribute:

```json
{
  "solution": "cache",
  "packageVersion": [7],
  "components": [{
    "type": "cache-engine",
    "configPath": "application.yml",
    "fields": [
      { "key": "solaceBroker", "type": "enum", "virtual": true,
        "optionsSource": { "kind": "sm-catalog", "entity": "connection", "connectionType": "solace-broker" },
        "ui": { "section": "Broker", "label": "Broker" } },
      { "key": "solace.password", "type": "string",
        "valueFrom": { "ref": "solaceBroker", "attribute": "password" },
        "ui": { "section": "Broker", "label": "Password" } }
    ]
  }]
}
```

- [ ] **Step 2: Run the suite to verify the negatives are NOT yet rejected**

Run: `./contracts/run-checks.sh --strict`
Expected: step 2 ("Negative fixtures must ALL be rejected") FAILS, naming all three new files — the schema currently accepts them.

`connection-without-type.json` is rejected by this task's schema change. The other two need Task 3's gates 15 and 17; they will still fail at the end of this task, which is expected and is why Task 3 follows immediately.

- [ ] **Step 3: Amend `$defs/optionsSource`**

In `contracts/solution-package.schema.json`, replace the whole `$defs/optionsSource` with:

```json
"optionsSource": {
  "type": "object",
  "required": ["kind", "entity"],
  "additionalProperties": false,
  "description": "UI-D22, amended by UI-D27 — a package names an SM-owned entity. It can never supply a URL.",
  "properties": {
    "kind": { "enum": ["sm-catalog"] },
    "entity": { "enum": ["connection", "environment", "instance", "metric"] },
    "connectionType": { "enum": ["solace-broker", "remote-server"] }
  },
  "allOf": [{
    "description": "UI-D27 — a connection selector must say which connection type it wants",
    "if": { "properties": { "entity": { "const": "connection" } }, "required": ["entity"] },
    "then": { "required": ["connectionType"] }
  }]
}
```

Note `"broker"` is **gone** from the enum (`UI-D27`). Also remove `"broker"` from `$defs/action.properties.entity`'s enum, which shares the same member list.

- [ ] **Step 4: Add `$defs/valueFrom` and reference it from `$defs/field`**

Add to `$defs`:

```json
"valueFrom": {
  "type": "object",
  "required": ["ref", "attribute"],
  "additionalProperties": false,
  "description": "UI-D28 — this field takes its value from the connection chosen in `ref`. Same component only.",
  "properties": {
    "ref": { "type": "string", "description": "field.key of a connection selector in the SAME component" },
    "attribute": { "type": "string", "description": "attribute key in that selector's connectionType descriptor" }
  }
}
```

Add to `$defs/field.properties`:

```json
"valueFrom": { "$ref": "#/$defs/valueFrom" }
```

Add to `$defs/ui.properties`:

```json
"listColumn": { "type": "boolean", "description": "UI-D27 — show this attribute as a column in the catalog list. Descriptors only." },
"promoteToDeclare": { "type": "boolean", "description": "UI-D27 — the Declare Instance dialog surfaces this field early." }
```

- [ ] **Step 5: Mirror the change in the frontend types**

In `frontend/contracts/solution-package/types.ts`, update the three declarations:

```ts
export interface OptionsSource {
  readonly kind: 'sm-catalog';
  /** `broker` retired by `UI-D27` — a Solace broker is `connection` + `connectionType`. */
  readonly entity: 'connection' | 'environment' | 'instance' | 'metric';
  /** Required when `entity === 'connection'` (schema-enforced). */
  readonly connectionType?: 'solace-broker' | 'remote-server';
}

/** `UI-D28` — binds this field to an attribute of the connection chosen in `ref`. */
export interface ValueFrom {
  readonly ref: string;
  readonly attribute: string;
}
```

Add `readonly valueFrom?: ValueFrom;` to `PackageField`, and to `UiHints`:

```ts
  readonly listColumn?: boolean;
  readonly promoteToDeclare?: boolean;
```

- [ ] **Step 6: Regenerate the frontend validator**

Run: `cd frontend && npm run contracts:generate`

If that script does not exist, regenerate `frontend/contracts/solution-package/generated/solution-package-validator.mjs` with whatever command its file header names, and record the command in this task's report.

- [ ] **Step 7: Update the shipped packages to the new vocabulary**

In `frontend/contracts/packages/cache/package.json`, add the selector immediately before `solace.host` and add `valueFrom` to the five broker fields:

```json
{
  "key": "solaceBroker",
  "type": "enum",
  "virtual": true,
  "required": true,
  "optionsSource": { "kind": "sm-catalog", "entity": "connection", "connectionType": "solace-broker" },
  "when": { "solace.enabled": true },
  "ui": { "section": "Broker", "order": 6, "label": "Broker", "promoteToDeclare": true },
  "version": [6, 7]
}
```

Then add to each existing field, keeping every other property exactly as it is:

| Field | Added property |
|---|---|
| `solace.host` | `"valueFrom": { "ref": "solaceBroker", "attribute": "host" }` |
| `solace.vpn` | `"valueFrom": { "ref": "solaceBroker", "attribute": "vpn" }` |
| `solace.port` | `"valueFrom": { "ref": "solaceBroker", "attribute": "port" }` |
| `solace.username` | `"valueFrom": { "ref": "solaceBroker", "attribute": "username" }` |
| `solace.password` | `"valueFrom": { "ref": "solaceBroker", "attribute": "password" }` |

Then `grep -rn '"entity": "broker"' contracts/ frontend/contracts/` and convert every hit to `"entity": "connection"` with the matching `connectionType`. Expect hits in `frontend/contracts/fixtures/view-model/multi-component-selection.json`.

- [ ] **Step 8: Verify**

Run: `./contracts/run-checks.sh --strict`
Expected: step 1 (positive fixtures) PASSES, step 2 rejects `connection-without-type.json` but **still accepts** the two `valueFrom` negatives. That residue is Task 3's.

Run: `cd frontend && npm test -- contracts/`
Expected: PASS.

- [ ] **Step 9: Commit** (ask first)

```bash
git add contracts/solution-package.schema.json contracts/validation/negative/ frontend/contracts/
git commit -m "feat(contracts): connectionType + valueFrom for UI-D27/UI-D28

Retires optionsSource entity 'broker' in favour of 'connection' plus a
required connectionType. Adds valueFrom on fields, and ui.listColumn /
ui.promoteToDeclare.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: Validation gates 15–19

**Files:**
- Modify: `contracts/run-checks.sh` (add a step after step 10)
- Create: `contracts/validation/check_value_from.py`

**Interfaces:**
- Consumes: Task 1 descriptors, Task 2 schema.
- Produces: `check_value_from.py`, invoked by `run-checks.sh` step 11, exiting non-zero on gate failure. Gate 15 prints a WARNING and does **not** affect exit status.

- [ ] **Step 1: Write the failing gate script**

Create `contracts/validation/check_value_from.py`:

```python
#!/usr/bin/env python3
"""Gates 15-19 (UI-D28). Structural checks the JSON Schema cannot express,
because they are cross-field and cross-document.

  15  valueFrom.ref resolves to a connection selector in the SAME component
  16  valueFrom.attribute exists in that connectionType's descriptor, and
      its type is assignable to the target field's type
  17  secret binds only to secret, and non-secret never to secret (I6)
  18  two fields binding the same (ref, attribute) WARN - never fail
  19  a required field with valueFrom needs its selector required too
"""
import json, pathlib, sys

root = pathlib.Path(__file__).resolve().parents[2]
descriptors = {
    json.loads(p.read_text())["connectionType"]: json.loads(p.read_text())
    for p in (root / "contracts" / "connection-types").glob("*.json")
    if p.name != "descriptor.schema.json"
}

# Gate 13's assignability table. A descriptor attribute type on the left may
# populate a field type on the right. Deliberately narrow: widening it is a
# decision, not a convenience.
ASSIGNABLE = {
    "string": {"string", "enum"},
    "int": {"int", "string"},
    "bool": {"bool"},
    "enum": {"enum", "string"},
    "duration": {"duration", "string"},
    "secret": {"secret"},
}

failures, warnings = [], []

def check_package(path, doc):
    for comp in doc.get("components", []):
        fields = comp.get("fields", [])
        by_key = {f["key"]: f for f in fields}
        seen_bindings = {}
        where = f"{path.name}:{comp.get('type', '?')}"

        for f in fields:
            vf = f.get("valueFrom")
            if not vf:
                continue
            ref, attribute = vf["ref"], vf["attribute"]

            # gate 15
            selector = by_key.get(ref)
            if selector is None:
                failures.append(f"{where}: {f['key']}.valueFrom.ref '{ref}' is not a field in this component (gate 15)")
                continue
            src = selector.get("optionsSource") or {}
            if src.get("entity") != "connection":
                failures.append(f"{where}: {f['key']}.valueFrom.ref '{ref}' is not a connection selector (gate 15)")
                continue

            conn_type = src.get("connectionType")
            descriptor = descriptors.get(conn_type)
            if descriptor is None:
                failures.append(f"{where}: selector '{ref}' names unknown connectionType '{conn_type}' (gate 15)")
                continue

            attrs = {a["key"]: a for a in descriptor["attributes"]}
            attr = attrs.get(attribute)

            # gate 16
            if attr is None:
                failures.append(f"{where}: {f['key']}.valueFrom.attribute '{attribute}' is not in the {conn_type} descriptor (gate 16)")
                continue
            if f["type"] not in ASSIGNABLE.get(attr["type"], set()):
                failures.append(f"{where}: {f['key']} is type '{f['type']}' but attribute '{attribute}' is '{attr['type']}' (gate 16)")

            # gate 17 - I6, checked both directions
            field_secret = f.get("secret") is True or f["type"] == "secret"
            attr_secret = attr.get("secret") is True or attr["type"] == "secret"
            if field_secret != attr_secret:
                failures.append(
                    f"{where}: {f['key']} (secret={field_secret}) bound to attribute "
                    f"'{attribute}' (secret={attr_secret}) - I6 forbids either direction (gate 17)"
                )

            # gate 19
            if f.get("required") and not selector.get("required"):
                failures.append(f"{where}: {f['key']} is required but its selector '{ref}' is not (gate 19)")

            # gate 18 - WARNING ONLY. Two MI properties may legitimately need
            # the same attribute (a host in both a data-plane and a
            # management-plane property). It is also what a copy-paste looks
            # like, so it is reported and never fatal.
            binding = (ref, attribute)
            if binding in seen_bindings:
                warnings.append(f"{where}: {f['key']} and {seen_bindings[binding]} both bind {ref}#{attribute} (gate 18)")
            else:
                seen_bindings[binding] = f["key"]

for pattern in ("contracts/validation/fixtures/*.json", "frontend/contracts/packages/*/package.json"):
    for path in sorted(root.glob(pattern)):
        try:
            doc = json.loads(path.read_text())
        except json.JSONDecodeError as exc:
            failures.append(f"{path.name}: not valid JSON: {exc}")
            continue
        if "components" in doc:
            check_package(path, doc)

for w in warnings:
    print(f"  WARN {w}")
if failures:
    print("\n".join(f"  FAIL {f}" for f in failures))
    sys.exit(1)
print(f"  OK: gates 15-19 pass ({len(warnings)} warning(s))")
```

- [ ] **Step 2: Run it to verify it currently fails**

Run: `python3 contracts/validation/check_value_from.py`
Expected: FAIL — the `contracts/validation/fixtures/` glob may find no `valueFrom` yet, but the negative fixtures from Task 2 are not in that glob. Confirm the script *runs* and prints OK, then proceed to step 3 which makes it actually exercise the negatives.

- [ ] **Step 3: Point the negative-fixture runner at the new gate**

The negatives live in `contracts/validation/negative/`. Extend the glob list in the script so they are checked and *expected* to fail — add to the tuple in the `for pattern` loop:

```python
for pattern in ("contracts/validation/fixtures/*.json",
                "contracts/validation/negative/valueFrom-*.json",
                "frontend/contracts/packages/*/package.json"):
```

Run: `python3 contracts/validation/check_value_from.py`
Expected: FAIL, naming `valueFrom-unknown-ref.json` (gate 15) and `valueFrom-secret-mismatch.json` (gate 17). **This proves the gates bite.**

- [ ] **Step 4: Restore the glob to positives only, and wire the negatives into step 2**

Revert step 3's glob edit — the gate script checks *valid* packages. The negatives are the existing step 2's job. Add to `contracts/run-checks.sh`, after step 10:

```bash
step "11. valueFrom bindings: gates 15-19 (UI-D28)"
python3 "$here/validation/check_value_from.py" || rc=1

step "12. Connection type descriptors must validate (UI-D27)"
python3 "$here/connection-types/validate-descriptors.py" || rc=1
```

Then extend step 2's negative loop so each `valueFrom-*.json` negative is additionally run through `check_value_from.py` and **must** exit non-zero. Follow the existing loop's structure in that file exactly; record in the task report the line numbers you changed.

- [ ] **Step 5: Verify the whole suite**

Run: `./contracts/run-checks.sh --strict`
Expected: ALL CHECKS PASSED, 0 skipped, with gate 18 possibly printing warnings (warnings do not fail).

- [ ] **Step 6: Commit** (ask first)

```bash
git add contracts/run-checks.sh contracts/validation/check_value_from.py
git commit -m "feat(contracts): gates 15-19 for valueFrom bindings

Gate 18 warns rather than fails: two MI properties may legitimately need
the same connection attribute.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: `project()` — canonical engine

**Files:**
- Modify: `contracts/view-models/src/types.ts` (`Catalog`, `FieldValue`, `OptionsSource`, `PackageField`)
- Modify: `contracts/view-models/src/project.ts:254-270` (pass 1) and `:319` (field view)
- Test: `contracts/view-models/src/project.test.ts`

**Interfaces:**
- Consumes: Task 2's `valueFrom` and `connectionType`.
- Produces:
  - `CatalogEntry = { readonly id: string; readonly label: string; readonly connectionType?: string; readonly attributes: Readonly<Record<string, unknown>>; readonly revision: number }`
  - `Catalog = Readonly<Partial<Record<OptionsSource['entity'], readonly CatalogEntry[]>>>`
  - `FieldValue`'s `value` arm gains `source: 'draft' | 'default' | 'connection' | 'override'`
  - Task 5 reimplements all of this; Tasks 9–11 consume `source`.

⚠️ **The draft and the save payload use different shapes, deliberately.** In the *draft*, a bound field has **no entry at all** — absence is what makes it bound, and a literal entry is what makes it an override. In the *save payload* (Task 10) a bound field serializes as `{ $from: 'conn-7#host' }`, because the server must be told the binding rather than inferring it from a gap. Do not introduce a `$from` marker into the draft; pass 1 below reads the selector, not a marker.

- [ ] **Step 1: Write the failing tests**

Add to `contracts/view-models/src/project.test.ts`:

```ts
const BROKER_PKG: SolutionPackage = {
  solution: 'cache',
  packageVersion: [7],
  components: [{
    type: 'cache-engine',
    configPath: 'application.yml',
    fields: [
      { key: 'solaceBroker', type: 'enum', virtual: true, required: true,
        optionsSource: { kind: 'sm-catalog', entity: 'connection', connectionType: 'solace-broker' },
        ui: { section: 'Broker', label: 'Broker' } },
      { key: 'solace.host', type: 'string', required: true,
        valueFrom: { ref: 'solaceBroker', attribute: 'host' },
        ui: { section: 'Broker', label: 'Host' } },
      { key: 'solace.port', type: 'int', default: 55555,
        valueFrom: { ref: 'solaceBroker', attribute: 'port' },
        ui: { section: 'Broker', label: 'Port' } },
      { key: 'solace.username', type: 'string', default: 'guest',
        valueFrom: { ref: 'solaceBroker', attribute: 'username' },
        ui: { section: 'Broker', label: 'Username' } },
    ],
  }],
};

const CATALOG: Catalog = {
  connection: [{
    id: 'conn-7', label: 'prod-broker-01', connectionType: 'solace-broker', revision: 4,
    attributes: { host: 'tcps://broker.acme.internal', port: 55443, vpn: 'payments-prod' },
    // NOTE: `username` deliberately absent - exercises precedence row 3.
  }],
};

const SELECTION = { instances: [{ instanceKey: 'i1', componentType: 'cache-engine' }] };

function fieldOf(vm: ViewModel, key: string) {
  return vm.sections.flatMap((s) => s.fields).find((f) => f.key === key);
}

it('resolves a bound field from the selected connection', () => {
  const vm = project(BROKER_PKG, SELECTION, { i1: { solaceBroker: 'conn-7' } }, OBSERVED_OK, CATALOG, 7);
  const host = fieldOf(vm, 'solace.host');
  expect(host?.value).toEqual({ kind: 'value', value: 'tcps://broker.acme.internal', source: 'connection' });
  expect(host?.enabled).toBe(false); // bound fields are read-only until overridden
});

it('falls through to the declared default when the connection leaves an attribute unset', () => {
  const vm = project(BROKER_PKG, SELECTION, { i1: { solaceBroker: 'conn-7' } }, OBSERVED_OK, CATALOG, 7);
  expect(fieldOf(vm, 'solace.username')?.value)
    .toEqual({ kind: 'value', value: 'guest', source: 'default' });
});

it('treats a literal draft value on a bound field as an override', () => {
  const vm = project(BROKER_PKG, SELECTION,
    { i1: { solaceBroker: 'conn-7', 'solace.port': 55556 } }, OBSERVED_OK, CATALOG, 7);
  const port = fieldOf(vm, 'solace.port');
  expect(port?.value).toEqual({ kind: 'value', value: 55556, source: 'override' });
  expect(port?.enabled).toBe(true); // an override is editable
});

it('blocks a required bound field when no connection is selected', () => {
  const vm = project(BROKER_PKG, SELECTION, { i1: {} }, OBSERVED_OK, CATALOG, 7);
  const host = fieldOf(vm, 'solace.host');
  expect(host?.value).toEqual({ kind: 'unset' });
  expect(host?.validations.some((v) => v.severity === 'block')).toBe(true);
});

it('diagnoses a selector pointing at a connection the catalog does not hold', () => {
  const vm = project(BROKER_PKG, SELECTION, { i1: { solaceBroker: 'conn-99' } }, OBSERVED_OK, CATALOG, 7);
  expect(vm.diagnostics.some((d) => d.code === 'CONNECTION_UNRESOLVED')).toBe(true);
});

it('never lets a resolved secret reach a FieldValue (I6)', () => {
  const pkg = { ...BROKER_PKG, components: [{ ...BROKER_PKG.components[0], fields: [
    BROKER_PKG.components[0].fields[0],
    { key: 'solace.password', type: 'secret' as const, secret: true,
      valueFrom: { ref: 'solaceBroker', attribute: 'password' }, ui: { section: 'Broker', label: 'Password' } },
  ]}]};
  const catalog: Catalog = { connection: [{ ...CATALOG.connection![0],
    attributes: { ...CATALOG.connection![0].attributes, password: 'hunter2' } }] };
  const vm = project(pkg, SELECTION, { i1: { solaceBroker: 'conn-7' } }, OBSERVED_OK, catalog, 7);
  expect(fieldOf(vm, 'solace.password')?.value).toEqual({ kind: 'secretUnset' });
});
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd contracts/view-models && npx vitest run src/project.test.ts`
Expected: FAIL — `valueFrom` is not a known property and `Catalog` entries are strings, so every new test errors.

- [ ] **Step 3: Update the types**

In `contracts/view-models/src/types.ts`:

```ts
export interface OptionsSource {
  readonly kind: 'sm-catalog';
  /** `broker` retired by `UI-D27`. */
  readonly entity: 'connection' | 'environment' | 'instance' | 'metric';
  readonly connectionType?: 'solace-broker' | 'remote-server';
}

/** `UI-D28` — binds this field to an attribute of the entry chosen in `ref`. */
export interface ValueFrom {
  readonly ref: string;
  readonly attribute: string;
}

/**
 * `UI-D27`. A catalog entry now carries its attributes, because `UI-D28`
 * resolves field values from them. `revision` is what makes `UI-D29`'s
 * fingerprint move when a connection changes.
 *
 * `attributes` NEVER holds a secret value — a secret attribute contributes
 * only its presence, and `project()` emits `secretUnset` for it (`I6`).
 */
export interface CatalogEntry {
  readonly id: string;
  readonly label: string;
  readonly connectionType?: string;
  readonly attributes: Readonly<Record<string, unknown>>;
  readonly revision: number;
}

export type Catalog = Readonly<Partial<Record<OptionsSource['entity'], readonly CatalogEntry[]>>>;
```

Add `readonly valueFrom?: ValueFrom;` to `PackageField`, and widen `FieldValue`'s first arm:

```ts
export type FieldValue =
  | { readonly kind: 'value'; readonly value: unknown;
      readonly source: 'draft' | 'default' | 'connection' | 'override' }
  | { readonly kind: 'unset' }
  | { readonly kind: 'secretRef'; readonly ref: string }
  | { readonly kind: 'secretUnset' };
```

- [ ] **Step 4: Implement resolution in pass 1**

In `contracts/view-models/src/project.ts`, replace the pass-1 block at lines 254–270 with:

```ts
    // ---- pass 1: resolve values under UI-D28 precedence
    //   override -> selected connection -> declared default -> unset
    const values = new Map<string, unknown>();
    const sources = new Map<string, 'draft' | 'default' | 'connection' | 'override'>();
    const bindErrors = new Map<string, string>();

    // Selectors resolve FIRST. A `valueFrom` field may be declared before its
    // selector in the document, and a one-pass loop would then read an
    // unresolved selector. Declaration order must not change behaviour.
    const chosen = new Map<string, CatalogEntry>();
    for (const f of fields) {
      if (f.optionsSource?.entity !== 'connection') continue;
      const picked = instDraft[f.key];
      if (picked === undefined || picked === null || picked === '') continue;
      const entry = (catalog.connection ?? []).find((c) => c.id === String(picked));
      if (entry === undefined) {
        diagnostics.push({
          code: 'CONNECTION_UNRESOLVED',
          message: `${f.key} selects connection ${String(picked)}, which the catalog does not hold.`,
          instanceKey: inst.instanceKey, key: f.key,
        });
        continue;
      }
      chosen.set(f.key, entry);
    }

    for (const f of fields) {
      const raw = instDraft[f.key];
      const bound = f.valueFrom;
      const hasDraft = raw !== undefined && raw !== null && raw !== '';

      // A literal in the draft on a BOUND field is an explicit override
      // (UI-D28). The operator clicked Override and typed; that beats the
      // connection and survives every later connection edit.
      if (hasDraft) {
        const n = normalize(f, raw);
        if (n.ok) { values.set(f.key, n.value); sources.set(f.key, bound ? 'override' : 'draft'); }
        else { bindErrors.set(f.key, n.reason); values.set(f.key, undefined); }
        continue;
      }

      if (bound) {
        const entry = chosen.get(bound.ref);
        const attrValue = entry?.attributes[bound.attribute];
        // A selected connection PARTICIPATES in precedence; it does not
        // short-circuit it. An attribute the connection leaves unset falls
        // through to the field's own default rather than writing an empty
        // string into the MI's config file.
        if (attrValue !== undefined && attrValue !== null && attrValue !== '') {
          const n = normalize(f, attrValue);
          if (n.ok) { values.set(f.key, n.value); sources.set(f.key, 'connection'); }
          else { bindErrors.set(f.key, n.reason); values.set(f.key, undefined); }
          continue;
        }
      }

      if (f.default !== undefined && f.default !== null) {
        values.set(f.key, f.default);
        sources.set(f.key, 'default');
      } else {
        values.set(f.key, undefined);
      }
    }
```

Import `CatalogEntry` alongside `Catalog` in the type import at line 10.

- [ ] **Step 5: Make bound fields read-only in the field view**

At `project.ts:319`, the `kind: 'value'` construction already reads `src`, so it carries the new sources for free. Add read-only handling where `enabled` is computed for the `FieldView` — a field is not enabled when its source is `'connection'`:

```ts
      const boundReadOnly = src === 'connection';
```

and include `&& !boundReadOnly` in the existing `enabled` expression. Locate that expression by searching for `enabled:` inside the `const view: FieldView = {` literal; do not duplicate the existing `editingBlocked`/`ui.readOnly` logic, extend it.

- [ ] **Step 6: Run the tests**

Run: `cd contracts/view-models && npx vitest run src/project.test.ts`
Expected: PASS, including the pre-existing tests. If a pre-existing test broke, the `enabled` change is too broad — narrow it rather than editing the old test.

- [ ] **Step 7: Regenerate fixtures and confirm no silent goalpost move**

Run: `./contracts/run-checks.sh --strict`
Expected: step 8 ("View-model fixtures must be reproducible") PASSES. If it reports a diff, inspect it: a changed `source` on a broker field is *expected and correct*; any other change is a regression. Record the accepted diff in the task report before regenerating.

- [ ] **Step 8: Commit** (ask first)

```bash
git add contracts/view-models/src/
git commit -m "feat(contracts): valueFrom resolution in project() (UI-D28)

Precedence: override -> connection -> default -> unset. Selectors resolve
in a pre-pass so declaration order cannot change behaviour. A selected
connection participates in precedence rather than short-circuiting it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: `project()` — frontend engine

**Files:**
- Modify: `frontend/contracts/view-model/types.ts`
- Modify: `frontend/contracts/view-model/project.ts`
- Test: `frontend/contracts/view-model/project.test.ts`

**Interfaces:**
- Consumes: Task 4's semantics.
- Produces: byte-identical view-model output to Task 4 for the shared fixtures. Tasks 9–11 import from `@contracts/view-model/types`.

⚠️ This is **not** a copy-paste of Task 4. The two files have diverged (442 vs 482 lines, different prose, different internal helpers). Port the *behaviour*, matching this file's own conventions.

- [ ] **Step 1: Port the six tests from Task 4 Step 1**

Copy them into `frontend/contracts/view-model/project.test.ts`, adjusting imports to this module's paths. The assertions must be **identical** — that equality is what makes the two engines one contract.

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npm test -- contracts/view-model/project.test.ts`
Expected: FAIL, same reasons as Task 4.

- [ ] **Step 3: Port the type changes**

Apply Task 4 Step 3's `OptionsSource`, `ValueFrom`, `CatalogEntry`, `Catalog`, `PackageField.valueFrom` and `FieldValue` changes to `frontend/contracts/view-model/types.ts`, **keeping this file's own doc-comment style** (it documents SPECIFIED vs INFERRED per type — say which each addition is: `CatalogEntry.attributes` and `valueFrom` are SPECIFIED by `UI-D27`/`UI-D28`; `CatalogEntry.revision` is SPECIFIED by `UI-D29`).

- [ ] **Step 4: Port the resolution logic**

Apply Task 4 Step 4's pass-1 replacement and Step 5's read-only rule to `frontend/contracts/view-model/project.ts`, adapted to this file's structure. The `CONNECTION_UNRESOLVED` diagnostic code must match exactly.

- [ ] **Step 5: Run the tests**

Run: `cd frontend && npm test -- contracts/view-model/`
Expected: PASS.

- [ ] **Step 6: Prove the two engines agree**

Run: `./contracts/run-checks.sh --strict`
Expected: step 8 PASSES against both engines' fixture output.

Then run the full frontend suite: `cd frontend && npm test`
Expected: PASS. The Designer tests exercise the Cache package, which Task 2 Step 7 changed — if `DesignerPage.test.tsx` fails on broker fields now being read-only, that is **correct new behaviour**; update the test and note it in the report.

- [ ] **Step 7: Commit** (ask first)

```bash
git add frontend/contracts/view-model/
git commit -m "feat(frontend): valueFrom resolution in the frontend engine

Behaviour-identical port of the canonical engine's UI-D28 resolution.
Both engines pass the same view-model fixtures.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 6: Connections repository and test doubles

**Files:**
- Create: `frontend/src/shared/testdoubles/data/connections.ts`
- Create: `frontend/src/shared/testdoubles/data/connections.test.ts`
- Create: `frontend/src/features/connections/connectionsRepository.ts`
- Create: `frontend/src/features/connections/connectionsRepository.test.ts`
- Create: `frontend/src/features/connections/index.ts`

**Interfaces:**
- Consumes: Task 1 descriptors, Task 4 `CatalogEntry`.
- Produces:
  - `ConnectionRow = { id, name, connectionType, descriptorVersion, scope: {org, subOrg, env}, description?, attributes, secretStatus: Record<string,'set'|'unset'>, status: 'active'|'inactive', revision, updatedAt, updatedBy, usedByCount }`
  - `ConnectionsRepository` with `list`, `get`, `create`, `update`, `deactivate`, `remove`, `listDescriptors`
  - `toCatalogEntries(rows): CatalogEntry[]`
  - Tasks 7–11 consume all of these.

- [ ] **Step 1: Write the failing test for the data shape**

Create `frontend/src/shared/testdoubles/data/connections.test.ts`:

```ts
import { describe, expect, it } from 'vitest';

import { CONNECTIONS, DESCRIPTORS, toCatalogEntries } from './connections';

describe('connection test doubles', () => {
  it('holds no secret VALUE anywhere (I6)', () => {
    const serialized = JSON.stringify(CONNECTIONS);
    expect(serialized).not.toMatch(/hunter2|BEGIN (RSA )?PRIVATE KEY/);
    for (const row of CONNECTIONS) {
      for (const status of Object.values(row.secretStatus)) {
        expect(['set', 'unset']).toContain(status);
      }
    }
  });

  it('reports secret status only for attributes the descriptor marks secret', () => {
    for (const row of CONNECTIONS) {
      const descriptor = DESCRIPTORS[row.connectionType];
      const secretKeys = new Set(
        descriptor.attributes.filter((a) => a.secret === true).map((a) => a.key),
      );
      for (const key of Object.keys(row.secretStatus)) {
        expect(secretKeys.has(key)).toBe(true);
      }
      // and the converse: a secret attribute never leaks into `attributes`
      for (const key of secretKeys) {
        expect(row.attributes).not.toHaveProperty(key);
      }
    }
  });

  it('projects to catalog entries carrying the revision UI-D29 needs', () => {
    const entries = toCatalogEntries(CONNECTIONS);
    const prod = entries.find((e) => e.id === 'conn-7');
    expect(prod).toMatchObject({ label: 'prod-broker-01', revision: 4 });
    expect(prod?.attributes).toMatchObject({ vpn: 'payments-prod' });
  });

  it('offers at least one inactive connection so the filter has something to exclude', () => {
    expect(CONNECTIONS.some((c) => c.status === 'inactive')).toBe(true);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npm test -- src/shared/testdoubles/data/connections.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the test doubles**

Create `frontend/src/shared/testdoubles/data/connections.ts`:

```ts
/**
 * Fixture connections (`UI-D27`). Fixture-backed exactly like every other
 * feature in this repo — there is no server behind this.
 *
 * **No secret VALUE appears here, ever.** `secretStatus` reports set/unset
 * and nothing else, because that is all the real API will return (`I6`,
 * spec §9.4). A fixture that carried a password would teach the screen a
 * shape the server can never serve.
 */
import type { CatalogEntry } from '@contracts/view-model/types';

import solaceBrokerDescriptor from '../../../../../contracts/connection-types/solace-broker.json';
import remoteServerDescriptor from '../../../../../contracts/connection-types/remote-server.json';

export type ConnectionTypeKey = 'solace-broker' | 'remote-server';

export interface ConnectionDescriptorAttribute {
  readonly key: string;
  readonly type: 'string' | 'int' | 'bool' | 'enum' | 'duration' | 'secret';
  readonly options?: readonly (string | number | boolean)[];
  readonly default?: string | number | boolean | null;
  readonly required?: boolean;
  readonly secret?: boolean;
  readonly discriminator?: boolean;
  readonly when?: Readonly<Record<string, string | number | boolean>>;
  readonly ui?: { readonly label?: string; readonly helperText?: string;
                  readonly order?: number; readonly listColumn?: boolean };
}

export interface ConnectionDescriptor {
  readonly connectionType: ConnectionTypeKey;
  readonly label: string;
  readonly descriptorVersion: number;
  readonly attributes: readonly ConnectionDescriptorAttribute[];
}

export interface ConnectionRow {
  readonly id: string;
  readonly name: string;
  readonly connectionType: ConnectionTypeKey;
  readonly descriptorVersion: number;
  /** Server-derived (`I5`). The client never sends this as a filter. */
  readonly scope: { readonly org: string; readonly subOrg: string; readonly env: string };
  readonly description?: string;
  readonly attributes: Readonly<Record<string, unknown>>;
  readonly secretStatus: Readonly<Record<string, 'set' | 'unset'>>;
  readonly status: 'active' | 'inactive';
  /** `UI-D29` — bumps on every save; what makes the fingerprint move. */
  readonly revision: number;
  readonly updatedAt: string;
  readonly updatedBy: string;
  /** Gates deletion and sizes the impact confirmation (spec §6.2). */
  readonly usedByCount: number;
}

export const DESCRIPTORS: Readonly<Record<ConnectionTypeKey, ConnectionDescriptor>> = {
  'solace-broker': solaceBrokerDescriptor as ConnectionDescriptor,
  'remote-server': remoteServerDescriptor as ConnectionDescriptor,
};

export const CONNECTIONS: readonly ConnectionRow[] = [
  {
    id: 'conn-7', name: 'prod-broker-01', connectionType: 'solace-broker', descriptorVersion: 1,
    scope: { org: 'acme', subOrg: 'payments', env: 'PROD' },
    description: 'Payments production broker, Frankfurt.',
    attributes: { host: 'tcps://broker.acme.internal', port: 55443, vpn: 'payments-prod', username: 'svc-cache' },
    secretStatus: { password: 'set' },
    status: 'active', revision: 4,
    updatedAt: '2026-09-23T10:14:00Z', updatedBy: 'ops@acme.example', usedByCount: 3,
  },
  {
    id: 'conn-8', name: 'stage-broker-01', connectionType: 'solace-broker', descriptorVersion: 1,
    scope: { org: 'acme', subOrg: 'payments', env: 'TEST' },
    attributes: { host: 'tcps://broker-st.acme.internal', port: 55443, vpn: 'payments-stage' },
    // `username` deliberately absent - exercises precedence row 3 end to end.
    secretStatus: { password: 'set' },
    status: 'active', revision: 1,
    updatedAt: '2026-09-18T09:00:00Z', updatedBy: 'ops@acme.example', usedByCount: 1,
  },
  {
    id: 'conn-9', name: 'retired-broker', connectionType: 'solace-broker', descriptorVersion: 1,
    scope: { org: 'acme', subOrg: 'payments', env: 'DEV' },
    attributes: { host: 'tcps://old.acme.internal', port: 55555, vpn: 'default' },
    secretStatus: { password: 'unset' },
    status: 'inactive', revision: 2,
    updatedAt: '2026-08-02T12:00:00Z', updatedBy: 'ops@acme.example', usedByCount: 0,
  },
  {
    id: 'conn-12', name: 'sftp-inbound-prod', connectionType: 'remote-server', descriptorVersion: 1,
    scope: { org: 'acme', subOrg: 'payments', env: 'PROD' },
    description: 'Inbound settlement files.',
    attributes: { host: 'sftp.acme.internal', port: 22, username: 'ingest', authMethod: 'private-key' },
    secretStatus: { privateKey: 'set', passphrase: 'unset' },
    status: 'active', revision: 2,
    updatedAt: '2026-09-20T16:40:00Z', updatedBy: 'ops@acme.example', usedByCount: 2,
  },
  {
    id: 'conn-13', name: 'ftp-archive', connectionType: 'remote-server', descriptorVersion: 1,
    scope: { org: 'acme', subOrg: 'payments', env: 'TEST' },
    attributes: { host: 'archive.acme.internal', port: 22, username: 'archive', authMethod: 'basic' },
    secretStatus: { password: 'set' },
    status: 'active', revision: 1,
    updatedAt: '2026-09-11T08:30:00Z', updatedBy: 'ops@acme.example', usedByCount: 0,
  },
];

/**
 * `UI-D28` feeds `project()` from here. Secret attributes contribute
 * nothing — `project()` emits `secretUnset` for them regardless (`I6`).
 */
export function toCatalogEntries(rows: readonly ConnectionRow[]): CatalogEntry[] {
  return rows.map((row) => ({
    id: row.id,
    label: row.name,
    connectionType: row.connectionType,
    attributes: row.attributes,
    revision: row.revision,
  }));
}
```

- [ ] **Step 4: Run the data test**

Run: `cd frontend && npm test -- src/shared/testdoubles/data/connections.test.ts`
Expected: PASS (4 tests).

If the JSON imports fail, add `"resolveJsonModule": true` to `frontend/tsconfig.json` — check first, it may already be set for the package fixtures.

- [ ] **Step 5: Write the failing repository test**

Create `frontend/src/features/connections/connectionsRepository.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest';

import { createConnectionsRepository } from './connectionsRepository';

function clientWith(result: unknown) {
  return { get: vi.fn().mockResolvedValue(result), post: vi.fn().mockResolvedValue(result),
           put: vi.fn().mockResolvedValue(result), del: vi.fn().mockResolvedValue(undefined) };
}

describe('connectionsRepository', () => {
  it('lists by connection type without sending any scope (I5)', async () => {
    const client = clientWith([]);
    await createConnectionsRepository(client as never).list('solace-broker');
    expect(client.get).toHaveBeenCalledWith('/connections?type=solace-broker&status=active', {});
    const url = client.get.mock.calls[0][0] as string;
    expect(url).not.toMatch(/org=|subOrg=|env=/);
  });

  it('refuses to delete a referenced connection before any request is made', async () => {
    const client = clientWith(undefined);
    const repo = createConnectionsRepository(client as never);
    await expect(repo.remove('conn-7', 3)).rejects.toThrow(/referenced by 3/);
    expect(client.del).not.toHaveBeenCalled();
  });

  it('deletes when nothing references the connection', async () => {
    const client = clientWith(undefined);
    await createConnectionsRepository(client as never).remove('conn-9', 0);
    expect(client.del).toHaveBeenCalledWith('/connections/conn-9', {});
  });
});
```

- [ ] **Step 6: Run to verify it fails**

Run: `cd frontend && npm test -- src/features/connections/connectionsRepository.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 7: Write the repository**

Create `frontend/src/features/connections/connectionsRepository.ts`:

```ts
/**
 * `ConnectionsRepository` (`UI-D27`) — the read/write seam for the
 * connection catalog, following `targetsRepository.ts`'s shape.
 *
 * **Scope is never sent** (`I5`). The server derives org/sub-org/env from
 * the caller and applies it; a client-supplied filter would be a scope
 * the client chose.
 *
 * **Secret values never cross this seam in either direction on read.** A
 * write carries a new secret once, to be stored; a read returns
 * `secretStatus` only (`I6`).
 */
import type { PlaneClient } from '@shared/api';
import type { ConnectionRow, ConnectionTypeKey, ConnectionDescriptor }
  from '@shared/testdoubles/data/connections';

export interface ConnectionWriteInput {
  readonly name: string;
  readonly connectionType: ConnectionTypeKey;
  readonly description?: string;
  readonly attributes: Readonly<Record<string, unknown>>;
  /** Only attributes the operator actually entered this time. Omitted = unchanged. */
  readonly secrets?: Readonly<Record<string, string>>;
}

export interface ConnectionsRepository {
  list(type?: ConnectionTypeKey, includeInactive?: boolean, signal?: AbortSignal): Promise<ConnectionRow[]>;
  get(id: string, signal?: AbortSignal): Promise<ConnectionRow>;
  create(input: ConnectionWriteInput, signal?: AbortSignal): Promise<ConnectionRow>;
  update(id: string, input: ConnectionWriteInput, signal?: AbortSignal): Promise<ConnectionRow>;
  deactivate(id: string, active: boolean, signal?: AbortSignal): Promise<ConnectionRow>;
  /** Refuses at `usedByCount > 0` (spec §6.3) before any request is made. */
  remove(id: string, usedByCount: number, signal?: AbortSignal): Promise<void>;
  listDescriptors(signal?: AbortSignal): Promise<ConnectionDescriptor[]>;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

export function createConnectionsRepository(cpClient: PlaneClient): ConnectionsRepository {
  return {
    list(type, includeInactive = false, signal) {
      const params = [
        ...(type === undefined ? [] : [`type=${type}`]),
        ...(includeInactive ? [] : ['status=active']),
      ];
      const query = params.length === 0 ? '' : `?${params.join('&')}`;
      return cpClient.get<ConnectionRow[]>(`/connections${query}`, opts(signal));
    },
    get(id, signal) {
      return cpClient.get<ConnectionRow>(`/connections/${id}`, opts(signal));
    },
    create(input, signal) {
      return cpClient.post<ConnectionRow>('/connections', input, opts(signal));
    },
    update(id, input, signal) {
      return cpClient.put<ConnectionRow>(`/connections/${id}`, input, opts(signal));
    },
    deactivate(id, active, signal) {
      return cpClient.put<ConnectionRow>(`/connections/${id}/status`,
        { status: active ? 'active' : 'inactive' }, opts(signal));
    },
    async remove(id, usedByCount, signal) {
      // Checked here, not only server-side: the count is already on screen,
      // and a request that the server will certainly refuse is a worse
      // error message than this one.
      if (usedByCount > 0) {
        throw new Error(`Cannot delete: referenced by ${usedByCount} instance(s). Deactivate it instead.`);
      }
      await cpClient.del(`/connections/${id}`, opts(signal));
    },
    listDescriptors(signal) {
      return cpClient.get<ConnectionDescriptor[]>('/connection-types', opts(signal));
    },
  };
}
```

Create `frontend/src/features/connections/index.ts`:

```ts
export { createConnectionsRepository } from './connectionsRepository';
export type { ConnectionsRepository, ConnectionWriteInput } from './connectionsRepository';
```

- [ ] **Step 8: Verify**

Run: `cd frontend && npm test -- src/features/connections/ src/shared/testdoubles/data/connections.test.ts`
Expected: PASS (7 tests).

Run: `cd frontend && npm run lint`
Expected: PASS — confirms `eslint-plugin-boundaries` accepts the new feature's imports.

- [ ] **Step 9: Commit** (ask first)

```bash
git add frontend/src/features/connections/ frontend/src/shared/testdoubles/data/connections.ts frontend/src/shared/testdoubles/data/connections.test.ts
git commit -m "feat(frontend): connections repository and fixtures (UI-D27)

Secret values never cross the read seam; secretStatus reports set/unset.
Scope is server-derived and never sent as a filter (I5).

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 7: Connections catalog list

**Files:**
- Create: `frontend/src/features/connections/ConnectionsPage/ConnectionsPage.tsx`
- Create: `frontend/src/features/connections/ConnectionsPage/columns.ts`
- Create: `frontend/src/features/connections/ConnectionsPage/columns.test.ts`
- Create: `frontend/src/features/connections/ConnectionsPage/ConnectionsPage.test.tsx`
- Create: `frontend/src/features/connections/ConnectionsPage/ConnectionsPage.a11y.test.tsx`
- Modify: the app route table and nav (find them: `grep -rn "targets" frontend/src/app/`)

**Interfaces:**
- Consumes: Task 6's `ConnectionRow`, `DESCRIPTORS`, `ConnectionsRepository`.
- Produces: `deriveColumns(descriptor): ColumnDef[]` where `ColumnDef = { key: string; label: string }`. Task 8 opens from this page's row click.

- [ ] **Step 1: Write the failing column-derivation test**

Create `frontend/src/features/connections/ConnectionsPage/columns.test.ts`:

```ts
import { describe, expect, it } from 'vitest';

import { DESCRIPTORS } from '@shared/testdoubles/data/connections';

import { deriveColumns } from './columns';

describe('deriveColumns', () => {
  it('takes exactly the attributes the descriptor marks listColumn, in ui.order', () => {
    expect(deriveColumns(DESCRIPTORS['solace-broker'])).toEqual([
      { key: 'host', label: 'Host' },
      { key: 'vpn', label: 'Message VPN' },
    ]);
  });

  it('derives a different column set per type with no per-type code', () => {
    expect(deriveColumns(DESCRIPTORS['remote-server']).map((c) => c.key))
      .toEqual(['host', 'username', 'authMethod']);
  });

  it('never exposes a secret attribute as a column (I6)', () => {
    for (const descriptor of Object.values(DESCRIPTORS)) {
      const secretKeys = descriptor.attributes.filter((a) => a.secret).map((a) => a.key);
      const columnKeys = deriveColumns(descriptor).map((c) => c.key);
      expect(columnKeys.filter((k) => secretKeys.includes(k))).toEqual([]);
    }
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npm test -- src/features/connections/ConnectionsPage/columns.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement column derivation**

Create `frontend/src/features/connections/ConnectionsPage/columns.ts`:

```ts
/**
 * Catalog list columns, DERIVED from the descriptor (`UI-D27`). A third
 * connection type gets its columns from its own descriptor with no change
 * here — which is the point of the descriptor being a field list.
 */
import type { ConnectionDescriptor } from '@shared/testdoubles/data/connections';

export interface ColumnDef {
  readonly key: string;
  readonly label: string;
}

export function deriveColumns(descriptor: ConnectionDescriptor): ColumnDef[] {
  return descriptor.attributes
    // A secret can never be a column. The guard is here as well as in the
    // descriptor schema because this is the code that would leak it (`I6`).
    .filter((attribute) => attribute.ui?.listColumn === true && attribute.secret !== true)
    .slice()
    .sort((a, b) => (a.ui?.order ?? 0) - (b.ui?.order ?? 0))
    .map((attribute) => ({ key: attribute.key, label: attribute.ui?.label ?? attribute.key }));
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd frontend && npm test -- src/features/connections/ConnectionsPage/columns.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Write the failing page test**

Create `frontend/src/features/connections/ConnectionsPage/ConnectionsPage.test.tsx`:

```tsx
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { CONNECTIONS } from '@shared/testdoubles/data/connections';

import { ConnectionsPage } from './ConnectionsPage';

function repo(rows = CONNECTIONS) {
  return {
    list: vi.fn().mockResolvedValue([...rows]),
    get: vi.fn(), create: vi.fn(), update: vi.fn(),
    deactivate: vi.fn(), remove: vi.fn(), listDescriptors: vi.fn(),
  };
}

describe('ConnectionsPage', () => {
  it('shows one tab per connection type, counted', async () => {
    render(<ConnectionsPage repository={repo() as never} />);
    expect(await screen.findByRole('tab', { name: /Solace brokers \(3\)/ })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Remote servers \(2\)/ })).toBeInTheDocument();
  });

  it('renders the columns the active tab\'s descriptor declares', async () => {
    render(<ConnectionsPage repository={repo() as never} />);
    expect(await screen.findByRole('columnheader', { name: 'Message VPN' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: /Remote servers/ }));
    expect(await screen.findByRole('columnheader', { name: 'Authentication' })).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Message VPN' })).not.toBeInTheDocument();
  });

  it('shows the reference count, which is what gates deletion', async () => {
    render(<ConnectionsPage repository={repo() as never} />);
    const row = await screen.findByRole('row', { name: /prod-broker-01/ });
    expect(within(row).getByText('3')).toBeInTheDocument();
  });

  it('renders an empty state rather than a bare table when a type has no connections', async () => {
    render(<ConnectionsPage repository={repo([]) as never} />);
    expect(await screen.findByText(/No connections yet/)).toBeInTheDocument();
  });

  it('never renders a secret value', async () => {
    const { container } = render(<ConnectionsPage repository={repo() as never} />);
    await screen.findByRole('row', { name: /prod-broker-01/ });
    expect(container.textContent).not.toMatch(/password|privateKey/i);
  });
});
```

- [ ] **Step 6: Run to verify it fails**

Run: `cd frontend && npm test -- src/features/connections/ConnectionsPage/ConnectionsPage.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 7: Implement the page**

Create `frontend/src/features/connections/ConnectionsPage/ConnectionsPage.tsx`. Requirements the tests pin, in this repo's existing conventions (follow `InstancesPage.tsx` for the load/empty/error shape and `@shared/ui` for `Button`, `Chip`, `Alert`):

- One `<Tabs>` generated by iterating `DESCRIPTORS` — never a hardcoded pair. Tab label is `` `${descriptor.label}s (${count})` ``; counts come from the loaded rows.
- The table's `columnheader`s come from `deriveColumns(descriptor)`, plus fixed leading `Name` and trailing `Scope` and `Used` columns.
- Row click calls `onOpen(row)` (Task 8 supplies the drawer).
- `+ New connection` button calls `onCreate(activeType)`.
- Empty state: *"No connections yet. Create one to reuse it across instances."*
- Inactive rows render with a muted `Chip` reading `inactive` and are hidden unless a "Show inactive" toggle is on.
- **No secret rendering anywhere** — the page reads `attributes` only, never `secretStatus`.

⚠️ Per the Global Constraints, this layout is spec §8.1, built by analogy to Targets; it is **not** in the visual reference. Do not add brand colours.

- [ ] **Step 8: Run the page tests**

Run: `cd frontend && npm test -- src/features/connections/ConnectionsPage/`
Expected: PASS (8 tests).

- [ ] **Step 9: Write the a11y test**

Create `ConnectionsPage.a11y.test.tsx` following the exact shape of `frontend/src/features/inventory/instances/InstancesPage.a11y.test.tsx` — same axe setup, same assertions, this page's render.

Run: `cd frontend && npm test -- src/features/connections/`
Expected: PASS, zero axe violations.

- [ ] **Step 10: Wire the route and nav**

Run `grep -rn "targets" frontend/src/app/` to find the route table and nav list. Add a `Connections` entry immediately after `Targets`, route `/connections`. Follow whatever lazy-loading pattern the neighbouring routes use.

Run: `cd frontend && npm test && npm run lint && npm run build`
Expected: all PASS.

- [ ] **Step 11: Commit** (ask first)

```bash
git add frontend/src/features/connections/ frontend/src/app/
git commit -m "feat(frontend): Connections catalog list (UI-D27)

Tabs and columns are derived from the connection type descriptors, so a
third type needs no code here.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 8: Connection detail drawer

**Files:**
- Create: `frontend/src/features/connections/ConnectionDrawer/ConnectionDrawer.tsx`
- Create: `frontend/src/features/connections/ConnectionDrawer/descriptorForm.ts`
- Create: `frontend/src/features/connections/ConnectionDrawer/descriptorForm.test.ts`
- Create: `frontend/src/features/connections/ConnectionDrawer/ConnectionDrawer.test.tsx`
- Create: `frontend/src/features/connections/ConnectionDrawer/ConnectionDrawer.a11y.test.tsx`

**Interfaces:**
- Consumes: Task 6 repository and descriptors.
- Produces: `visibleAttributes(descriptor, values): ConnectionDescriptorAttribute[]` (applies `when` predicates) and `validateConnection(descriptor, values, secretStatus): Record<string, string>`. Task 9's inline-create reuses the drawer.

- [ ] **Step 1: Write the failing form-logic test**

Create `descriptorForm.test.ts`:

```ts
import { describe, expect, it } from 'vitest';

import { DESCRIPTORS } from '@shared/testdoubles/data/connections';

import { validateConnection, visibleAttributes } from './descriptorForm';

const REMOTE = DESCRIPTORS['remote-server'];

describe('visibleAttributes', () => {
  it('shows password for basic auth and hides the key fields', () => {
    const keys = visibleAttributes(REMOTE, { authMethod: 'basic' }).map((a) => a.key);
    expect(keys).toContain('password');
    expect(keys).not.toContain('privateKey');
    expect(keys).not.toContain('passphrase');
  });

  it('shows the key fields for private-key auth and hides password', () => {
    const keys = visibleAttributes(REMOTE, { authMethod: 'private-key' }).map((a) => a.key);
    expect(keys).toEqual(expect.arrayContaining(['privateKey', 'passphrase']));
    expect(keys).not.toContain('password');
  });

  it('is driven purely by the descriptor - no auth-method knowledge in code', () => {
    // An unknown discriminator value hides every predicated attribute
    // rather than falling back to a hardcoded default.
    const keys = visibleAttributes(REMOTE, { authMethod: 'kerberos' }).map((a) => a.key);
    expect(keys).not.toContain('password');
    expect(keys).not.toContain('privateKey');
  });
});

describe('validateConnection', () => {
  it('requires a required attribute', () => {
    const errors = validateConnection(REMOTE, { authMethod: 'basic', port: 22 }, {});
    expect(errors.host).toBeDefined();
    expect(errors.username).toBeDefined();
  });

  it('requires a secret only when it is unset AND visible', () => {
    const missing = validateConnection(REMOTE,
      { host: 'h', username: 'u', authMethod: 'private-key', port: 22 }, {});
    expect(missing.privateKey).toBeDefined();
    expect(missing.passphrase).toBeUndefined(); // optional

    const alreadySet = validateConnection(REMOTE,
      { host: 'h', username: 'u', authMethod: 'private-key', port: 22 }, { privateKey: 'set' });
    expect(alreadySet.privateKey).toBeUndefined();
  });

  it('enforces the declared numeric range', () => {
    const errors = validateConnection(REMOTE,
      { host: 'h', username: 'u', authMethod: 'basic', port: 70000 }, { password: 'set' });
    expect(errors.port).toBeDefined();
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npm test -- src/features/connections/ConnectionDrawer/descriptorForm.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement the form logic**

Create `descriptorForm.ts`:

```ts
/**
 * Descriptor-driven form logic (`UI-D27`) — pure, so every rule is
 * asserted without rendering a drawer, the same split `declareForm.ts`
 * uses.
 *
 * There is NO connection-type knowledge in this file. `authMethod` is an
 * ordinary discriminator; a future type's discriminator works unchanged.
 */
import type { ConnectionDescriptor, ConnectionDescriptorAttribute }
  from '@shared/testdoubles/data/connections';

export type AttributeValues = Readonly<Record<string, unknown>>;

/** `when` is an all-must-match equality map, exactly as `project()` evaluates it. */
function matches(when: ConnectionDescriptorAttribute['when'], values: AttributeValues): boolean {
  if (when === undefined) return true;
  return Object.entries(when).every(([key, expected]) => values[key] === expected);
}

export function visibleAttributes(
  descriptor: ConnectionDescriptor,
  values: AttributeValues,
): ConnectionDescriptorAttribute[] {
  return descriptor.attributes
    .filter((attribute) => matches(attribute.when, values))
    .slice()
    .sort((a, b) => (a.ui?.order ?? 0) - (b.ui?.order ?? 0));
}

export function validateConnection(
  descriptor: ConnectionDescriptor,
  values: AttributeValues,
  secretStatus: Readonly<Record<string, 'set' | 'unset'>>,
): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const attribute of visibleAttributes(descriptor, values)) {
    const isSecret = attribute.secret === true || attribute.type === 'secret';
    const value = values[attribute.key];

    if (attribute.required === true) {
      // A secret satisfies "required" by already being set - the operator
      // must not retype a password to change a port.
      const satisfied = isSecret
        ? secretStatus[attribute.key] === 'set' || (typeof value === 'string' && value !== '')
        : value !== undefined && value !== null && value !== '';
      if (!satisfied) {
        errors[attribute.key] = 'Required.';
        continue;
      }
    }

    if (isSecret) continue; // nothing below inspects a secret's value

    if (typeof value === 'number') {
      if (attribute.min !== undefined && value < attribute.min) {
        errors[attribute.key] = `Must be at least ${String(attribute.min)}.`;
      } else if (attribute.max !== undefined && value > attribute.max) {
        errors[attribute.key] = `Must be at most ${String(attribute.max)}.`;
      }
    }

    if (attribute.pattern !== undefined && typeof value === 'string' && value !== ''
        && !new RegExp(attribute.pattern).test(value)) {
      errors[attribute.key] = 'Invalid format.';
    }
  }
  return errors;
}
```

Note: `privateKey` in the `required`-secret test is not marked `required` in the descriptor, so add `"required": true` to `privateKey` in `contracts/connection-types/remote-server.json` — a private-key connection without a key is not a connection. Re-run Task 1's validator after that edit.

- [ ] **Step 4: Run to verify it passes**

Run: `cd frontend && npm test -- src/features/connections/ConnectionDrawer/descriptorForm.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 5: Write the failing drawer test**

Create `ConnectionDrawer.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { CONNECTIONS } from '@shared/testdoubles/data/connections';

import { ConnectionDrawer } from './ConnectionDrawer';

const BROKER = CONNECTIONS[0];
const SFTP = CONNECTIONS.find((c) => c.id === 'conn-12')!;

describe('ConnectionDrawer', () => {
  it('renders fields from the descriptor, in declared order', async () => {
    render(<ConnectionDrawer connection={BROKER} onSave={vi.fn()} onClose={vi.fn()} />);
    expect(await screen.findByLabelText('Host')).toHaveValue('tcps://broker.acme.internal');
    expect(screen.getByLabelText('Message VPN')).toHaveValue('payments-prod');
  });

  it('shows a secret as set/unset and never as a value', () => {
    render(<ConnectionDrawer connection={BROKER} onSave={vi.fn()} onClose={vi.fn()} />);
    expect(screen.getByText(/set · never displayed/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Replace' })).toBeInTheDocument();
  });

  it('swaps the credential block when the auth discriminator changes', async () => {
    render(<ConnectionDrawer connection={SFTP} onSave={vi.fn()} onClose={vi.fn()} />);
    expect(await screen.findByLabelText('Private key')).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('Authentication'), 'basic');
    expect(screen.queryByLabelText('Private key')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
  });

  it('warns how many instances a save will affect', async () => {
    const onSave = vi.fn();
    render(<ConnectionDrawer connection={BROKER} onSave={onSave} onClose={vi.fn()} />);
    await userEvent.clear(screen.getByLabelText('Host'));
    await userEvent.type(screen.getByLabelText('Host'), 'tcps://new.acme.internal');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText(/3 instances reference this connection/)).toBeInTheDocument();
    expect(onSave).not.toHaveBeenCalled(); // confirmation first
  });

  it('renders Test connection disabled, with a reason', () => {
    render(<ConnectionDrawer connection={BROKER} onSave={vi.fn()} onClose={vi.fn()} />);
    const button = screen.getByRole('button', { name: /Test connection/ });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription(/server support/i);
  });
});
```

- [ ] **Step 6: Run to verify it fails**

Run: `cd frontend && npm test -- src/features/connections/ConnectionDrawer/ConnectionDrawer.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 7: Implement the drawer**

Create `ConnectionDrawer.tsx` against spec §8.2, using `visibleAttributes` for what to render and `validateConnection` for the gate. Requirements the tests pin:

- Fixed header fields: Name, Description, and read-only Scope (`I5` — scope is displayed, never edited).
- Attribute fields rendered from `visibleAttributes(descriptor, values)`; control chosen by `attribute.type` (`string`→text, `int`→number, `bool`→switch, `enum`→select, `secret`→the existing `SecretField` from `@shared/ui`).
- A secret renders as `set · never displayed` or `unset`, plus a `Replace` button that swaps in a write-only input. **There is no read path.**
- `Test connection` is rendered **disabled**, with `aria-describedby` pointing at the text *"Available when server support lands."* (spec §7).
- Saving with `usedByCount > 0` shows the impact confirmation first: *"N instances reference this connection. Their configuration-of-record will change; the change reaches each MI only when someone applies it."* `onSave` fires only after confirmation.
- Type switching on the discriminator retains what was typed in the hidden branch (`UI-D4` retention) — hold all values in one state object and filter at render, never delete on switch.

- [ ] **Step 8: Verify**

Run: `cd frontend && npm test -- src/features/connections/`
Expected: PASS.

Add `ConnectionDrawer.a11y.test.tsx` mirroring `DeclareInstanceDialog.a11y.test.tsx`, and re-run.
Expected: PASS, zero axe violations.

- [ ] **Step 9: Commit** (ask first)

```bash
git add frontend/src/features/connections/ConnectionDrawer contracts/connection-types/remote-server.json
git commit -m "feat(frontend): descriptor-driven connection drawer (UI-D27)

Secrets are set/unset with a write-only replace path; there is no read
path. Test connection ships disabled per spec section 7.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 9: Declare Instance — connection selectors

**Files:**
- Modify: `frontend/src/features/inventory/instances/declareForm.ts`
- Modify: `frontend/src/features/inventory/instances/declareForm.test.ts`
- Modify: `frontend/src/features/inventory/instances/DeclareInstanceDialog.tsx`
- Modify: `frontend/src/features/inventory/instances/DeclareInstanceDialog.test.tsx`
- Modify: `frontend/src/features/inventory/instances/instancesRepository.ts` (`DeclareInstanceInput`)

**Interfaces:**
- Consumes: Task 2's `ui.promoteToDeclare`, Task 6's repository.
- Produces: `promotedFields(pkg, packageVersion): PackageField[]`; `DeclareFormState` gains `connections: Readonly<Record<string, string>>`; `DeclareInstanceInput` gains `connections?`. Task 10 reads the same draft keys.

- [ ] **Step 1: Write the failing form tests**

Add to `declareForm.test.ts`:

```ts
describe('promoted connection selectors', () => {
  it('collects fields the package marks promoteToDeclare, for the chosen version', () => {
    expect(promotedFields(CACHE_PKG, 7).map((f) => f.key)).toEqual(['solaceBroker']);
  });

  it('ignores a promoted field that the chosen package version excludes', () => {
    const pkg = { ...CACHE_PKG, components: [{ ...CACHE_PKG.components[0], fields: [
      { key: 'futureBroker', type: 'enum' as const, virtual: true, version: [8],
        optionsSource: { kind: 'sm-catalog' as const, entity: 'connection' as const,
                         connectionType: 'solace-broker' as const },
        ui: { promoteToDeclare: true, label: 'Broker' } },
    ]}]};
    expect(promotedFields(pkg, 7)).toEqual([]);
  });

  it('requires a promoted selector the package marks required', () => {
    const state = { ...initialDeclareForm('cache', [], [7]), name: 'cache-01',
                    packageVersion: '7', org: 'acme', subOrg: 'payments', env: 'PROD' as const };
    const errors = validateDeclareForm(state, [], MESSAGES, [7], CACHE_PKG);
    expect(errors.connections?.solaceBroker).toBe(MESSAGES.required);
  });

  it('accepts a chosen connection', () => {
    const state = { ...initialDeclareForm('cache', [], [7]), name: 'cache-01',
                    packageVersion: '7', org: 'acme', subOrg: 'payments', env: 'PROD' as const,
                    connections: { solaceBroker: 'conn-7' } };
    expect(validateDeclareForm(state, [], MESSAGES, [7], CACHE_PKG).connections).toBeUndefined();
  });

  it('clears chosen connections when the scope cascade changes', () => {
    const state = { ...initialDeclareForm('cache', [], [7]),
                    org: 'acme', subOrg: 'payments', connections: { solaceBroker: 'conn-7' } };
    // A connection is scoped to one exact triple (UI-D27), so a connection
    // chosen under the old sub-org is not offered under the new one.
    expect(setSubOrg(state, 'treasury').connections).toEqual({});
    expect(setOrg(state, 'globex').connections).toEqual({});
  });

  it('carries chosen connections into the declare input', () => {
    const state = { ...initialDeclareForm('cache', [], [7]), name: 'cache-01',
                    packageVersion: '7', org: 'acme', subOrg: 'payments', env: 'PROD' as const,
                    connections: { solaceBroker: 'conn-7' } };
    expect(toDeclareInput(state, [7], CACHE_PKG).connections).toEqual({ solaceBroker: 'conn-7' });
  });
});
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npm test -- src/features/inventory/instances/declareForm.test.ts`
Expected: FAIL — `promotedFields` not exported, `connections` not on the state.

- [ ] **Step 3: Implement the form changes**

In `declareForm.ts`:

```ts
/**
 * Connection selectors the package promotes to declare time
 * (`UI-D27`). Version-filtered exactly like every other field, so a
 * selector introduced in package version 8 never appears on a version-7
 * declare form.
 */
export function promotedFields(pkg: SolutionPackage, packageVersion: PackageVersionId): PackageField[] {
  return pkg.components
    .flatMap((component) => component.fields)
    .filter((field) =>
      field.ui?.promoteToDeclare === true &&
      (field.version === undefined || field.version.some((v) => String(v) === String(packageVersion))));
}
```

Add to `DeclareFormState`:

```ts
  /**
   * Chosen connection per promoted selector: `selectorFieldKey -> connectionId`.
   * Cleared whenever the scope cascade changes, because a connection is
   * scoped to one exact org/sub-org/environment triple (`UI-D27`) and a
   * stale choice would submit a connection this instance cannot see.
   */
  connections: Readonly<Record<string, string>>;
```

Initialise it to `{}` in `initialDeclareForm`. Add `connections: {}` to the objects returned by `setOrg` and `setSubOrg` (both already clear the levels below them — this is the same rule one step further). Add to `DeclareFormErrors`:

```ts
  connections?: Readonly<Record<string, string>>;
```

In `validateDeclareForm`, after the cascade block:

```ts
  if (pkg !== undefined && parsedVersion !== undefined) {
    const connectionErrors: Record<string, string> = {};
    for (const field of promotedFields(pkg, parsedVersion)) {
      if (field.required === true && (state.connections[field.key] ?? '') === '') {
        connectionErrors[field.key] = messages.required;
      }
    }
    if (Object.keys(connectionErrors).length > 0) {
      errors.connections = connectionErrors;
    }
  }
```

In `toDeclareInput`, add:

```ts
    ...(Object.keys(state.connections).length === 0 ? {} : { connections: state.connections }),
```

Add `readonly connections?: Readonly<Record<string, string>>;` to `DeclareInstanceInput` in `instancesRepository.ts`, documented as *"selector field key → connection id. Saved as a selection; applies nothing (spec §5.1)."*

- [ ] **Step 4: Run to verify they pass**

Run: `cd frontend && npm test -- src/features/inventory/instances/declareForm.test.ts`
Expected: PASS, including every pre-existing test.

- [ ] **Step 5: Write the failing dialog test**

Add to `DeclareInstanceDialog.test.tsx`:

```tsx
it('offers only connections matching the chosen scope triple (I5/UI-D27)', async () => {
  renderDialog(); // existing helper
  await chooseScope('acme', 'payments', 'PROD'); // existing helper
  const select = await screen.findByLabelText('Broker');
  expect(within(select).getByRole('option', { name: /prod-broker-01/ })).toBeInTheDocument();
  expect(within(select).queryByRole('option', { name: /stage-broker-01/ })).not.toBeInTheDocument();
  expect(within(select).queryByRole('option', { name: /retired-broker/ })).not.toBeInTheDocument();
});

it('previews how many fields the chosen connection will set', async () => {
  renderDialog();
  await chooseScope('acme', 'payments', 'PROD');
  await userEvent.selectOptions(await screen.findByLabelText('Broker'), 'conn-7');
  expect(screen.getByText(/5 fields will be set/)).toBeInTheDocument();
  expect(screen.getByText(/tcps:\/\/broker\.acme\.internal/)).toBeInTheDocument();
});

it('disables the selector until a scope is chosen', async () => {
  renderDialog();
  expect(await screen.findByLabelText('Broker')).toBeDisabled();
});
```

- [ ] **Step 6: Run to verify they fail**

Run: `cd frontend && npm test -- src/features/inventory/instances/DeclareInstanceDialog.test.tsx`
Expected: FAIL — no `Broker` control.

- [ ] **Step 7: Implement the dialog section**

In `DeclareInstanceDialog.tsx`, render a `Connections` section **after** the org/sub-org/env cascade and **before** Replicas (the ordering is required — the cascade supplies the scope that filters the list). For each `promotedFields(pkg, packageVersion)` entry:

- A `<select>` labelled from `field.ui.label`, disabled until `org && subOrg && env` are all chosen.
- Options from `repository.list(field.optionsSource.connectionType)` filtered to `status === 'active'` and the exact scope triple.
- Below a chosen option, a summary line of that connection's `listColumn` attributes, and *"N fields will be set"* where N counts package fields whose `valueFrom.ref === field.key`.
- A `+ New connection…` action opening Task 8's `ConnectionDrawer` in create mode; on success, select the new connection.

- [ ] **Step 8: Verify**

Run: `cd frontend && npm test -- src/features/inventory/instances/`
Expected: PASS, including the a11y test.

- [ ] **Step 9: Commit** (ask first)

```bash
git add frontend/src/features/inventory/instances/
git commit -m "feat(frontend): connection selectors in Declare Instance (UI-D27)

Selectors are scope-gated behind the org/sub-org/env cascade and cleared
when it changes. Declare saves a selection and applies nothing.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 10: Designer — provenance chips and override

**Files:**
- Modify: `frontend/src/features/designer/FieldRow/FieldRow.tsx` (find it: `ls frontend/src/features/designer/`)
- Modify: `frontend/src/features/designer/form/useDesignerDraft.ts`
- Test: `frontend/src/features/designer/FieldRow/FieldRow.test.tsx`
- Test: `frontend/src/features/designer/form/useDesignerDraft.test.ts`

**Interfaces:**
- Consumes: Task 5's `FieldValue.source`.
- Produces: `onOverride(instanceId, fieldKey)` and `onRevert(instanceId, fieldKey)` on the draft hook. Task 11 reads the same draft.

- [ ] **Step 1: Write the failing draft-hook tests**

Add to `useDesignerDraft.test.ts`:

```ts
it('override copies the resolved value into the draft as a literal', () => {
  const { result } = renderHook(() => useDesignerDraft(BROKER_ARGS));
  act(() => { result.current.onOverride('i1', 'solace.port'); });
  expect(result.current.getInstanceValues('i1')['solace.port']).toBe(55443);
  const port = fieldOf(result.current.viewModel, 'solace.port');
  expect(port?.value).toMatchObject({ source: 'override' });
});

it('revert removes the literal so the field binds again', () => {
  const { result } = renderHook(() => useDesignerDraft(BROKER_ARGS));
  act(() => { result.current.onOverride('i1', 'solace.port'); });
  act(() => { result.current.onFieldChange('i1', 'solace.port', 55556); });
  act(() => { result.current.onRevert('i1', 'solace.port'); });
  expect(fieldOf(result.current.viewModel, 'solace.port')?.value)
    .toMatchObject({ value: 55443, source: 'connection' });
});

it('changing the selector re-binds non-overridden fields and keeps overrides', () => {
  const { result } = renderHook(() => useDesignerDraft(BROKER_ARGS));
  act(() => { result.current.onOverride('i1', 'solace.port'); });
  act(() => { result.current.onFieldChange('i1', 'solace.port', 55556); });
  act(() => { result.current.onFieldChange('i1', 'solaceBroker', 'conn-8'); });
  expect(fieldOf(result.current.viewModel, 'solace.host')?.value)
    .toMatchObject({ value: 'tcps://broker-st.acme.internal', source: 'connection' });
  expect(fieldOf(result.current.viewModel, 'solace.port')?.value)
    .toMatchObject({ value: 55556, source: 'override' });
});

it('a bound field contributes its reference, not its value, to the save payload', () => {
  const { result } = renderHook(() => useDesignerDraft(BROKER_ARGS));
  expect(result.current.payload.i1['solace.host']).toEqual({ $from: 'conn-7#host' });
  expect(result.current.payload.i1['solace.port']).toEqual({ $from: 'conn-7#port' });
});
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npm test -- src/features/designer/form/useDesignerDraft.test.ts`
Expected: FAIL — `onOverride` is not a function.

- [ ] **Step 3: Implement override/revert and the reference payload**

In `useDesignerDraft.ts`, add to `DesignerDraft`:

```ts
  /** `UI-D28` — detach one field from its connection, seeding it with the currently resolved value. */
  onOverride: (instanceId: string, fieldKey: string) => void;
  /** Re-bind an overridden field to its connection. */
  onRevert: (instanceId: string, fieldKey: string) => void;
```

`onOverride` reads the current `ViewField.value.value` for that key and calls the existing `setValue` path with it as a literal. `onRevert` sets the key to `undefined` so pass 1 falls back to the binding.

In `visibleInstanceValues`, a field whose `ViewField.value.source === 'connection'` contributes `{ $from: \`${connectionId}#${attribute}\` }` rather than its resolved value. Derive `connectionId` from the draft's selector value and `attribute` from the package's `valueFrom` — do not re-resolve the value.

⚠️ Keep the module header's `FE-D17` rule intact: the payload still comes from `visibleValues()`, never from `getValues()`. The `no-restricted-syntax` lint rule enforces this.

- [ ] **Step 4: Run to verify they pass**

Run: `cd frontend && npm test -- src/features/designer/form/`
Expected: PASS.

- [ ] **Step 5: Write the failing FieldRow tests**

```tsx
it('shows the source connection and an Override action on a bound field', () => {
  render(<FieldRow field={boundField('solace.host', 'connection')} {...handlers} />);
  expect(screen.getByText('from prod-broker-01')).toBeInTheDocument();
  expect(screen.getByRole('textbox', { name: 'Host' })).toHaveAttribute('readonly');
  expect(screen.getByRole('button', { name: 'Override' })).toBeInTheDocument();
});

it('shows the overridden chip and a revert action once detached', () => {
  render(<FieldRow field={boundField('solace.port', 'override')} {...handlers} />);
  expect(screen.getByText('overridden · was prod-broker-01')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Revert to connection' })).toBeInTheDocument();
});

it('offers Replace rather than Override on a bound secret (I6)', () => {
  render(<FieldRow field={boundSecret('solace.password')} {...handlers} />);
  expect(screen.getByRole('button', { name: 'Replace' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Override' })).not.toBeInTheDocument();
});

it('adds no chip to an ordinary unbound field', () => {
  render(<FieldRow field={plainField('cache.name')} {...handlers} />);
  expect(screen.queryByText(/from |overridden/)).not.toBeInTheDocument();
});
```

- [ ] **Step 6: Run to verify they fail, then implement**

Run: `cd frontend && npm test -- src/features/designer/FieldRow/`
Expected: FAIL.

Implement in `FieldRow.tsx`: a `Chip` when `value.source` is `'connection'` or `'override'`; the input `readOnly` when `'connection'`; an `Override` / `Revert to connection` button accordingly. A **secret** never gets `Override` — it gets the existing `Replace` affordance, because overriding a secret means entering a new one and there is nothing to copy.

- [ ] **Step 7: Verify**

Run: `cd frontend && npm test -- src/features/designer/`
Expected: PASS. Expect to update pre-existing Designer snapshots where broker fields now carry a chip — that is correct new behaviour.

- [ ] **Step 8: Commit** (ask first)

```bash
git add frontend/src/features/designer/
git commit -m "feat(frontend): connection provenance and per-field override (UI-D28)

A bound field is read-only with an explicit Override; a bound secret gets
Replace, never Override. The save payload carries references, not values.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 11: Connection drift banner

**Files:**
- Create: `frontend/src/features/designer/ConnectionDriftBanner/ConnectionDriftBanner.tsx`
- Create: `frontend/src/features/designer/ConnectionDriftBanner/detectDrift.ts`
- Create: `frontend/src/features/designer/ConnectionDriftBanner/detectDrift.test.ts`
- Create: `frontend/src/features/designer/ConnectionDriftBanner/ConnectionDriftBanner.test.tsx`
- Modify: `frontend/src/features/designer/DesignerPage/DesignerForm.tsx`

**Interfaces:**
- Consumes: Task 6's `ConnectionRow.revision`, Task 10's draft.
- Produces: `detectDrift(appliedRevisions, current): DriftEntry[]` where `DriftEntry = { connectionId, name, appliedRevision, currentRevision, affectedFields: string[] }`.

⚠️ **`UI-D29` gate.** The server-side effective-configuration fingerprint does not exist. This task ships the **client half** — revision comparison — which is sound on its own because the revision is the part that moves. The banner must **not** claim to detect value-level drift it cannot see. Its copy says what it knows: the connection changed.

- [ ] **Step 1: Write the failing detection tests**

```ts
import { describe, expect, it } from 'vitest';

import { detectDrift } from './detectDrift';

const APPLIED = { 'conn-7': 3 };
const CURRENT = [
  { id: 'conn-7', name: 'prod-broker-01', revision: 4 },
  { id: 'conn-8', name: 'stage-broker-01', revision: 1 },
];

describe('detectDrift', () => {
  it('reports a connection whose revision advanced since apply', () => {
    expect(detectDrift(APPLIED, CURRENT, { 'conn-7': ['solace.host', 'solace.vpn'] }))
      .toEqual([{ connectionId: 'conn-7', name: 'prod-broker-01', appliedRevision: 3,
                  currentRevision: 4, affectedFields: ['solace.host', 'solace.vpn'] }]);
  });

  it('reports nothing when revisions match', () => {
    expect(detectDrift({ 'conn-7': 4 }, CURRENT, { 'conn-7': ['solace.host'] })).toEqual([]);
  });

  it('ignores a connection this instance does not reference', () => {
    expect(detectDrift(APPLIED, CURRENT, {})).toEqual([]);
  });

  it('reports nothing when a connection has been deleted rather than guessing', () => {
    // A missing connection is CONNECTION_UNRESOLVED's problem, not drift's.
    expect(detectDrift({ 'conn-99': 1 }, CURRENT, { 'conn-99': ['solace.host'] })).toEqual([]);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npm test -- src/features/designer/ConnectionDriftBanner/detectDrift.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement detection**

```ts
/**
 * `UI-D29` — client-side drift detection by connection REVISION.
 *
 * Revision, not value, is deliberate. A hash over the stored draft is
 * invariant under exactly the change we must detect: the draft holds
 * `{"$from": "conn-7#host"}`, which does not move when conn-7's host
 * moves. The revision does.
 *
 * ⚠️ This is the CLIENT half. The server-side effective-configuration
 * fingerprint (`UI-D29`, spec §6.1) does not exist yet, so this reports
 * "the connection changed" and never "these values changed" — it cannot
 * see values it was never sent.
 */
export interface DriftEntry {
  readonly connectionId: string;
  readonly name: string;
  readonly appliedRevision: number;
  readonly currentRevision: number;
  readonly affectedFields: readonly string[];
}

export function detectDrift(
  appliedRevisions: Readonly<Record<string, number>>,
  current: readonly { id: string; name: string; revision: number }[],
  fieldsByConnection: Readonly<Record<string, readonly string[]>>,
): DriftEntry[] {
  const entries: DriftEntry[] = [];
  for (const [connectionId, appliedRevision] of Object.entries(appliedRevisions)) {
    const fields = fieldsByConnection[connectionId];
    if (fields === undefined || fields.length === 0) continue;
    const row = current.find((c) => c.id === connectionId);
    // A connection that has vanished is CONNECTION_UNRESOLVED's diagnostic,
    // not drift. Reporting it here would give the operator a remediation
    // ("review and save") that cannot resolve it.
    if (row === undefined) continue;
    if (row.revision === appliedRevision) continue;
    entries.push({ connectionId, name: row.name, appliedRevision,
                   currentRevision: row.revision, affectedFields: fields });
  }
  return entries;
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd frontend && npm test -- src/features/designer/ConnectionDriftBanner/detectDrift.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Write the failing banner test**

```tsx
it('names the connection, the fields and the one remediation', () => {
  render(<ConnectionDriftBanner entries={[{ connectionId: 'conn-7', name: 'prod-broker-01',
    appliedRevision: 3, currentRevision: 4, affectedFields: ['solace.host', 'solace.vpn'] }]} />);
  expect(screen.getByText(/prod-broker-01 changed/)).toBeInTheDocument();
  expect(screen.getByText(/Host, Message VPN/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Review and save' })).toBeInTheDocument();
});

it('offers no restart, rollback or restage - those are DivergedBanner\'s', () => {
  render(<ConnectionDriftBanner entries={[/* as above */]} />);
  for (const name of ['Restart', 'Roll back', 'Re-stage']) {
    expect(screen.queryByRole('button', { name: new RegExp(name, 'i') })).not.toBeInTheDocument();
  }
});

it('renders nothing when there is no drift', () => {
  const { container } = render(<ConnectionDriftBanner entries={[]} />);
  expect(container).toBeEmptyDOMElement();
});
```

- [ ] **Step 6: Run to verify it fails, then implement**

Run: `cd frontend && npm test -- src/features/designer/ConnectionDriftBanner/`
Expected: FAIL.

Implement using `Alert` from `@shared/ui` with `severity="warn"` (not `err` — nothing is broken; a value is stale). Copy: *"prod-broker-01 changed (rev 3 → 4). Host, Message VPN differ from what was last applied."* One action: **Review and save**. Follow `BlockedBanner`'s bare-callback split — no repository wiring in the banner.

- [ ] **Step 7: Mount it in the Designer**

In `DesignerForm.tsx`, render `<ConnectionDriftBanner>` in the existing banner stack, above the form pane and as a **sibling** to `DivergedBanner` — never replacing it. Both can be true at once.

- [ ] **Step 8: Full verification**

```bash
cd frontend && npm test && npm run lint && npm run build
cd .. && ./contracts/run-checks.sh --strict
```

Expected: all PASS, 0 skipped.

- [ ] **Step 9: Commit** (ask first)

```bash
git add frontend/src/features/designer/
git commit -m "feat(frontend): connection drift banner (UI-D29)

Detects drift by connection revision, because a reference hash is
invariant under the change it must detect. Client half only; the
server-side fingerprint is still outstanding.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Deliberately out of scope

Each of these is blocked on something outside this plan. **Do not implement them; do not stub them in a way that implies they work.**

| Not built | Blocked on |
|---|---|
| Server-side effective-configuration fingerprint | Backend. Task 11 ships the client half and says so. |
| Test Connection execution | Backend + `I6` (must run server-side). Task 8 ships it disabled with a reason. |
| Real secret storage | The secret backend does not exist (spec §9.4). Fixtures carry `secretStatus` only. |
| `connection.*` permission enforcement | LOCKED `D18` has no such verbs (spec §9.1). Ships as `proposed`. |
| Visual-regression baselines for the new screens | The reference has no Connections screen; the §24 gate-2 waiver is unsigned (spec §9.5). |
| Inherited/shared connection scope | Spec §9.6 — exact triple only for v1. |
| Cross-component `valueFrom.ref` | Spec §9.7 — gate 12 enforces same-component. |

---

## Self-review

**Spec coverage:** §2.1 descriptors → Task 1. §2.2 stored object → Task 6. §2.3 scope → Tasks 6, 9. §3.1–3.4 contract → Task 2. §3.5 gates → Task 3. §4 precedence → Tasks 4, 5. §5 data flow → Tasks 4, 5, 10. §5.1 declare-applies-nothing → Task 9. §6.1 fingerprint → Task 11 (client half; server half listed out of scope). §6.2 connection edited → Tasks 8, 11. §6.3 field overridden → Task 10. §7 test connection → Task 8. §8.1 list → Task 7. §8.2 drawer → Task 8. §8.3 declare → Task 9. §9 decisions → Global Constraints and the out-of-scope table. No gaps.

**Type consistency:** `CatalogEntry` is defined in Task 4 and consumed identically in Tasks 5, 6 and 11. `FieldValue.source`'s four members are fixed in Task 4 and used unchanged in Tasks 10 and 11. `ConnectionRow` is defined once in Task 6 and consumed by Tasks 7–9. `deriveColumns`, `visibleAttributes`, `validateConnection`, `promotedFields` and `detectDrift` each appear with one signature. The draft-vs-payload shape split is called out explicitly in Task 4's Interfaces block, because the first draft of this plan carried a phantom `BOUND_PREFIX` constant that no task used — absence in the draft is the binding; `{ $from: … }` is only the wire form.

**Placeholder scan:** no TBD/TODO. Every code step carries real code. Three steps deliberately say "find it with grep" rather than naming a line number (Task 7 Step 10, Task 10 Step 3's `enabled` expression, Task 3 Step 4's negative loop) — those files were not read line-by-line during planning, and a guessed line number is worse than an instruction to locate it.
