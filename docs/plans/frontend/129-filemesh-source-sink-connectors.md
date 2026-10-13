# File Mesh source/sink + connector types — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render File Mesh source and sink instances in the Designer with connector-specific fields (NWFile / FTP / SFTP / GCS) driven entirely by the Solution Package, with no File Mesh identifier in React and no change to `solution-package.schema.json`.

**Architecture:** Role becomes a *component* (`configPath: file.source` / `file.sink`); connector becomes a `virtual` `discriminator` enum field inside that component, with every branch field carrying `when: { connectorType: … }`. Both mechanisms already exist and are already gated. One React change binds an instance to its component types; one convergence fix makes hidden fields stop validating.

**Tech Stack:** TypeScript, React 18, Vite, Vitest, react-hook-form, MUI. Python 3 for `contracts/` gates.

**Spec:** [`docs/design/dynamic-ui/filemesh-source-sink-connectors.md`](../../design/dynamic-ui/filemesh-source-sink-connectors.md) — read it before Task 149. Every task argues from it.

## Global Constraints

- **No change to `contracts/solution-package.schema.json`.** If a task seems to need one, stop — the design is wrong, not the schema (spec §6).
- **No new predicate operator.** `when` stays equality + implicit AND (`UI-D2`, spec §7). No `in`, `any`, `notEquals`.
- **No File Mesh identifier in React.** No `"SFTP"`, `"file-mesh"`, `"NWFILE"`, `role === 'source'` branching in `src/`. Strings live in JSON only.
- **`I6` — secrets never hold values.** `sftp_settings.password`, `ftp_settings.password` are `type: "secret"` and carry `secretRef://…` only.
- **`I2` — no invented facts.** Every field key, type and default comes from the two supplied `application-operator.yml` files. If a value is not in them, it is not authored.
- **Decision IDs are immutable.** This plan introduces `UI-D26` and `FE-D55`. Do not renumber, do not reuse.
- **Commit message trailer** on every commit: whatever the executing session's attribution rule specifies (the model that makes the commit signs it — do not copy a name from an earlier commit).
- **Verification commands** (run from `frontend/`): `npm run test`, `npm run typecheck`, `npm run lint`. From repo root: `./contracts/run-checks.sh`.
- **Ask before every commit** — do not commit without the developer's confirmation.
- **Baseline (established 2026-09-21, `FE-149`).** At `HEAD` before this plan, `npm run test` had **4 failures** and `npm run lint` **1 error**, both pre-existing; `FE-149` repaired them. Every later task's "Expected: PASS" assumes that repaired baseline — **run the gate before starting each task and record what it says**, do not assume green. See [L20](../../design/common/lessons.md).
- **Authoring convention (supersedes L19's interim rule, `FE-149`).** Bundled packages are authored **canonical** (`label`/`helperText`/`advanced` under `ui`) and reach the engine only via `toFrontendPackage()`. Never author flat, never raw-cast a package `as SolutionPackage`. [L19](../../design/common/lessons.md) and open item 36 record the history.

---

## File Structure

| Path | Responsibility | Task |
|---|---|---|
| `frontend/src/shared/testdoubles/data/packages.ts` | normalize bundled packages at the boundary | 149 |
| `frontend/contracts/packages/cache/package.json` | convert to canonical dialect | 149 |
| `frontend/contracts/packages/db-designer/package.json` | convert to canonical dialect | 149 |
| `frontend/contracts/view-model/project.ts` | hidden field ⇒ no validations | 150 |
| `frontend/contracts/fixtures/view-model/hidden-required-yields-no-validation.json` | pins the rule | 150 |
| `frontend/src/features/designer/DesignerPage/componentSelection.ts` | optional instance→component binding | 151 |
| `frontend/src/features/designer/DesignerPage/DesignerPage.tsx` | pass the binding through | 151 |
| `frontend/src/features/designer/configRepository.ts` | `DesignerLoad.componentTypes?` | 151 |
| `frontend/contracts/packages/file-mesh/package.json` | the real package — source, sink, bindings | 152, 153, 154 |
| `frontend/src/shared/testdoubles/data/instances.ts` | File Mesh source + sink instance rows | 154 |
| `docs/design/dynamic-ui/decisions.md` | `UI-D26` | 155 |
| `docs/design/frontend/decisions.md` | `FE-D55` | 155 |
| `docs/design/DECISIONS.md` | open item for the dialect split | 155 |

---

### Task 149: Unify the package dialect

**Why this is first.** Discovered during planning, and it blocks Tasks 152–154. The frontend engine reads a *hybrid* dialect: `label`, `helperText` and `advanced` **flat on the field**, but `section`, `order` and `readOnly` **nested under `ui`** ([`project.ts:218,236,244,271,303`](../../../frontend/contracts/view-model/project.ts)). ~~The existing `file-mesh/package.json` puts all six under `ui`, so its labels currently fall back to keys.~~ **Corrected:** that described the *uncommitted working tree*, not `HEAD` — at `HEAD` file-mesh is flat and renders correctly (spec §14.1 retracts the claim). The trap is real but conditional: any canonical package reaching the engine without `toFrontendPackage()` renders raw keys. Authoring 93 new fields into a shape the engine cannot read is not acceptable.

**Scope as executed — three parts, not one** (see [`task-149-report.md`](reports/task-149-report.md)): (a) the dialect boundary above; (b) **repairing a `cache` regression** — commit `b13c241` had dropped cache's `[6, 7]` version scheme, every field's `version` scope, the `max-size-bytes` field and `max-weight`'s default, leaving 4 tests red; (c) **one lint error** at `HEAD`. (b) and (c) were not anticipated; both pre-date this plan.

`toFrontendPackage()` already converts canonical → engine dialect and is already used by the dev-upload path ([`parsePackage.ts:321`](../../../frontend/src/app/dev/parsePackage.ts)). The bundled-package path just never calls it. This task closes that asymmetry.

**Files:**
- Modify: `frontend/src/shared/testdoubles/data/packages.ts:12-23`
- Modify: `frontend/contracts/packages/cache/package.json`
- Modify: `frontend/contracts/packages/db-designer/package.json`
- Modify: `frontend/contracts/packages/file-mesh/package.json` (commit the canonical form already in the working tree — *required*: `normalizeField` reads only `ui.label`)
- Modify: `frontend/src/features/inventory/solutions/sidebarSolutions.test.ts` (pre-existing lint error)
- Test: `frontend/src/shared/testdoubles/data/packages.test.ts` (create)

**Interfaces:**
- Consumes: `toFrontendPackage(pkg: CanonicalSolutionPackage): ViewModelPackage` from `@contracts/solution-package`
- Produces: `packageForSolution(solutionId: string): SolutionPackage | undefined` — unchanged signature, now returning a normalized package for every bundled solution.

- [x] **Step 1: Write the failing test**

Create `frontend/src/shared/testdoubles/data/packages.test.ts`:

```ts
import { describe, expect, it } from 'vitest';

import { packageForSolution } from './packages';

describe('packageForSolution', () => {
  it('exposes authored labels on the engine dialect, not the canonical nesting', () => {
    const pkg = packageForSolution('cache');
    const field = pkg?.components[0]?.fields.find((f) => f.key === 'max-size');
    expect(field?.label).toBe('Max entries');
  });

  it('normalizes every bundled solution, so no package depends on being pre-flattened', () => {
    for (const id of ['cache', 'file-mesh']) {
      const pkg = packageForSolution(id);
      expect(pkg).toBeDefined();
      for (const component of pkg!.components) {
        for (const field of component.fields) {
          // `ui` may carry section/order/readOnly, but never the three the
          // engine reads flat — that combination is the dialect bug.
          expect(field.ui).not.toHaveProperty('label');
          expect(field.ui).not.toHaveProperty('advanced');
          expect(field.ui).not.toHaveProperty('helperText');
        }
      }
    }
  });
});
```

- [x] **Step 2: Run it and watch it fail**

Run: `cd frontend && npx vitest run src/shared/testdoubles/data/packages.test.ts`
Expected: FAIL — cache's `label` is currently flat so the first test passes, but the second fails on `file-mesh`, whose fields carry `ui.label`.

- [x] **Step 3: Convert cache and db-designer to the canonical dialect**

For every field in both packages, move `label`, `helperText` and `advanced` from the field root into `ui`. Leave `ui.section`, `ui.order`, `ui.readOnly` where they are. Also in `db-designer/package.json`: change `"role": "primary"` to `"role": "none"` (the canonical enum is `source | sink | none`), and delete `required` / `default` from `columns[]` entries (the canonical `column` allows only `key`, `type`, `options`, `label`).

Mechanical transform, runnable from the repo root:

```bash
python3 - <<'PY'
import json, pathlib
UI_KEYS = ("label", "helperText", "advanced")
for name in ("cache", "db-designer"):
    p = pathlib.Path(f"frontend/contracts/packages/{name}/package.json")
    doc = json.loads(p.read_text())
    for comp in doc["components"]:
        if comp.get("role") == "primary":
            comp["role"] = "none"
        for f in comp.get("fields", []):
            ui = f.get("ui", {})
            for k in UI_KEYS:
                if k in f:
                    ui[k] = f.pop(k)
            if ui:
                f["ui"] = ui
            for col in f.get("columns", []):
                col.pop("required", None)
                col.pop("default", None)
    p.write_text(json.dumps(doc, indent=2) + "\n")
    print("converted", p)
PY
```

- [x] **Step 4: Verify both now pass the canonical validator**

Run: `python3 contracts/validation/validate-package.py 'frontend/contracts/packages/*/package.json'`
Expected: `db-designer` PASS. ⚠️ **`cache` does not pass yet** — my original expectation ("all nine PASS") was wrong: of cache's 29 violations, 26 were dialect and 3 were not (a dead root `id` key, and a `packageVersion` of `[2.3, 2.4]` that the schema's integer-only rule rejects). Break a violation count down by message before attributing it (L20). Step 4b repairs those.

- [x] **Step 4b: Repair the `cache` regression from `b13c241`**

`git log --follow -- frontend/contracts/packages/cache/package.json` then compare `c2538ba` (good) against `b13c241`. Exactly four collateral changes and nothing else: root `packageVersion` `[6, 7]` → `[2.3, 2.4]`; every field's `version` dropped; `max-size-bytes` (v6-only, default `67108864`) deleted; `max-weight.default` (`536870912`) deleted. Restore all four onto the canonical-dialect file, and remove the dead root `id`. Then:

Run: `python3 contracts/validation/validate-package.py 'frontend/contracts/packages/*/package.json'`
Expected: **all nine PASS.**

- [x] **Step 4c: Fix the pre-existing lint error**

`sidebarSolutions.test.ts:15` has `color: '#000000'`, tripping the no-raw-colour rule (`FE-D5`). Nothing under test reads it. Replace it with `SOLUTIONS[0]!.color` — the fixture layer is where the rule allows a raw brand colour — rather than adding an eslint-disable.

- [x] **Step 5: Normalize at the boundary**

In `frontend/src/shared/testdoubles/data/packages.ts`, replace the direct casts:

```ts
import { toFrontendPackage } from '@contracts/solution-package';
import type { CanonicalSolutionPackage } from '@contracts/solution-package/types';

// Bundled packages are authored in the CANONICAL dialect (the one
// `solution-package.schema.json` defines and `validate-package.py` gates).
// The engine reads a different shape, so they go through the same
// normalizer the dev-upload path uses — never a raw cast, which is how
// `file-mesh`'s authored labels silently fell back to raw keys.
const PACKAGES: Readonly<Record<string, SolutionPackage>> = {
  cache: toFrontendPackage(CACHE_PACKAGE as CanonicalSolutionPackage),
  'file-mesh': toFrontendPackage(FILE_MESH_PACKAGE as CanonicalSolutionPackage),
};
```

- [x] **Step 6: Run the tests**

Run: `cd frontend && npm run test && npm run typecheck && npm run lint`
Expected: **340 files / 2414 tests pass, typecheck clean, lint clean at `--max-warnings=0`.** (At `HEAD` before this task it was 338 files with 4 failing tests, and 1 lint error — re-run at `HEAD` first if the branch has moved.)

- [x] **Step 7: Commit** *(ask the developer first)*

```bash
git add frontend/src/shared/testdoubles/data/packages.ts \
        frontend/src/shared/testdoubles/data/packages.test.ts \
        frontend/contracts/packages/cache/package.json \
        frontend/contracts/packages/db-designer/package.json
git commit -m "fix(frontend): normalize bundled packages, unify on the canonical dialect

All nine bundled packages now pass validate-package.py. cache and
db-designer were stored post-normalization, which made file-mesh's
authored ui.label invisible to the engine. Closes the dialect half of
design §14.

<trailer per the session's attribution rule>"
```

---

### Task 150: A hidden field produces no validations

**Why.** The two `project()` implementations disagree. Canonical: `validations: visible ? … : []`. Frontend: evaluated regardless. Latent today; Task 152 makes it live, because `sftp_settings.ip` is `required` and hidden whenever the connector is not SFTP — the form would block submit on a control nobody can see. Spec §8. Closes open item 36's frontend half.

**Files:**
- Modify: `frontend/contracts/view-model/project.ts:245`
- Create: `frontend/contracts/fixtures/view-model/hidden-required-yields-no-validation.json`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: the invariant *a `ViewField` with `visible: false` has `validations: []`*, which Tasks 152–154 rely on.

- [x] **Step 1: Write the failing fixture**

Create `frontend/contracts/fixtures/view-model/hidden-required-yields-no-validation.json`. The suite runner auto-discovers every `*.json` in that directory.

```json
{
  "name": "hidden-required-yields-no-validation",
  "package": {
    "solution": "file-mesh",
    "packageVersion": [1],
    "appliesTo": ">=1.0 <2.0",
    "provenance": { "author": "solace", "signature": "test-fixture" },
    "authoring": { "reviewedRows": 2, "totalRows": 2 },
    "components": [
      {
        "type": "src",
        "cardinality": "one-of-many",
        "configPath": "file.source",
        "loggerPrefix": "com.solace.connector.filemesh.source",
        "fields": [
          { "key": "connectorType", "type": "enum", "options": ["NWFILE", "SFTP"],
            "default": "NWFILE", "discriminator": true, "virtual": true },
          { "key": "sftp_settings.ip", "type": "string", "required": true,
            "when": { "connectorType": "SFTP" } }
        ]
      }
    ]
  },
  "componentSelection": [{ "type": "src", "instanceId": "s1" }],
  "draftValues": { "s1": {} },
  "observedState": { "health": "healthy", "versionState": "ok", "configState": "in_sync" }
}
```

**Hand-author `expected` from the invariant — do not generate it from the engine.** (The original wording said to run the suite once and copy the output; that would have captured the buggy behaviour as the baseline, and the test could never fail. Corrected during `FE-150`.) The property it must show: `sftp_settings.ip` has `visible: false` **and** `validations: []`. Also write a *second* fixture, `hidden-required-blocks-once-branch-activates.json` — the same field with the branch active must report `REQUIRED` — and keep a **visible** required-and-empty field in both, so an over-broad fix (suppressing validation everywhere) fails. As executed, both fixtures live in the repo.

- [x] **Step 2: Run it and watch it fail**

Run: `cd frontend && npx vitest run contracts/fixtures/view-model/run.test.ts`
Expected: FAIL — the hidden field carries `{ tier: 'bind', code: 'REQUIRED', … }` instead of `[]`.

- [x] **Step 3: Make validation follow visibility**

In `frontend/contracts/view-model/project.ts`, `projectField` already computes `const visible = isVisible(field, values);` at line 210. Change line 245 from:

```ts
    validations: evaluateValidations(field, values[field.key] ?? null),
```

to:

```ts
    // A field the operator cannot see must never block the form. Matches
    // the canonical engine (`contracts/view-models/src/project.ts`), whose
    // divergence from this one was open item 36; a `required` field behind
    // an inactive `when:` branch is the case that makes it bite.
    validations: visible ? evaluateValidations(field, values[field.key] ?? null) : [],
```

- [x] **Step 4: Run the tests**

Run: `cd frontend && npm run test`
Expected: PASS, including `required-blocks-when-empty` and `required-clears-when-filled` — neither uses `when`, so neither changes.

- [x] **Step 5: Commit** *(ask first)*

```bash
git add frontend/contracts/view-model/project.ts \
        frontend/contracts/fixtures/view-model/hidden-required-yields-no-validation.json
git commit -m "fix(frontend): a hidden field produces no validations (open item 36)

Converges the frontend project() on the canonical engine. A required
field behind an inactive when: branch no longer blocks submit.

<trailer per the session's attribution rule>"
```

---

### Task 151: An instance binds to its component types (`FE-D55`)

**Why.** `deriveComponentSelection` returns one entry per declared component — a degenerate case its own header documents. With source and sink in one package an instance would render both, but a File Mesh instance is one Spring Boot app on one port. Spec §5.

**Files:**
- Modify: `frontend/src/features/designer/DesignerPage/componentSelection.ts`
- Modify: `frontend/src/features/designer/DesignerPage/componentSelection.test.ts`
- Modify: `frontend/src/features/designer/configRepository.ts` (the `DesignerLoad` interface)
- Modify: `frontend/src/features/designer/DesignerPage/DesignerPage.tsx:94`
- Modify: `frontend/src/features/governance/GovernancePage/GovernancePage.tsx:147` — **a second consumer the original plan missed** (found by grepping callers during `FE-151`): the Governance log viewer builds its component list from the same function, so it must honour the binding too
- Test: `frontend/src/features/governance/GovernancePage/GovernancePage.test.tsx`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `deriveComponentSelection(pkg: SolutionPackage, packageVersion: PackageVersionId, componentTypes?: readonly string[]): ComponentSelection` and `DesignerLoad.componentTypes?: readonly string[]`. Task 154 supplies `componentTypes` from the instance fixtures.

- [x] **Step 1: Write the failing tests**

Append to `frontend/src/features/designer/DesignerPage/componentSelection.test.ts`:

```ts
  it('returns only the components the instance binds to, in declaration order (FE-D55)', () => {
    expect(deriveComponentSelection(pkg(), 2, ['COMP_SINK'])).toEqual([
      { type: 'COMP_SINK', instanceId: 'COMP_SINK', role: 'sink' },
    ]);
  });

  it('keeps package declaration order regardless of the order the binding lists', () => {
    expect(
      deriveComponentSelection(pkg(), 2, ['COMP_SINK', 'COMP_SOURCE']).map((e) => e.type),
    ).toEqual(['COMP_SOURCE', 'COMP_SINK']);
  });

  it('falls back to every component when the instance declares no binding', () => {
    expect(deriveComponentSelection(pkg(), 2, undefined).map((e) => e.type)).toEqual([
      'COMP_SOURCE',
      'COMP_SINK',
    ]);
  });

  it('still honours the version filter when a binding names an out-of-version component', () => {
    expect(deriveComponentSelection(pkg(), 1, ['COMP_SINK'])).toEqual([]);
  });

  it('ignores a bound type the package does not declare rather than inventing an entry', () => {
    expect(deriveComponentSelection(pkg(), 2, ['NOT_IN_PACKAGE']).map((e) => e.type)).toEqual([]);
  });
```

- [x] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run src/features/designer/DesignerPage/componentSelection.test.ts`
Expected: FAIL — `deriveComponentSelection` takes two arguments, so TypeScript rejects the third.

- [x] **Step 3: Implement**

Replace the body of `frontend/src/features/designer/DesignerPage/componentSelection.ts`:

```ts
export function deriveComponentSelection(
  pkg: SolutionPackage,
  packageVersion: PackageVersionId,
  /**
   * The component types this instance composes (`FE-D55`). OMITTED means
   * the instance declares no binding, and every version-eligible component
   * renders — the pre-`FE-D55` behaviour every other bundled package still
   * relies on. Order comes from the PACKAGE, never from this list, so the
   * form's section order cannot be changed by how a binding is written.
   */
  componentTypes?: readonly string[],
): ComponentSelection {
  const bound = componentTypes === undefined ? undefined : new Set(componentTypes);
  return pkg.components
    .filter((component) => inVersion(component.version, packageVersion))
    .filter((component) => bound === undefined || bound.has(component.type))
    .map((component) => ({
      type: component.type,
      instanceId: component.type,
      ...(component.role === undefined ? {} : { role: component.role }),
    }));
}
```

- [x] **Step 4: Thread it through the page**

In `frontend/src/features/designer/configRepository.ts`, add to `DesignerLoad`:

```ts
  /** The component types this instance composes (`FE-D55`). Absent means every declared component renders — §16 Q10 has not defined how a binding is authored, so nothing yet supplies one over the wire. */
  componentTypes?: readonly string[] | undefined;
```

In `frontend/src/features/designer/DesignerPage/DesignerPage.tsx`, line 94:

```ts
  const componentSelection = deriveComponentSelection(
    load.package,
    load.packageVersion,
    load.componentTypes,
  );
```

- [x] **Step 5: Run the tests**

Run: `cd frontend && npm run test && npm run typecheck`
Expected: PASS. Every existing Designer test supplies no `componentTypes`, so all take the fallback and are unchanged.

- [x] **Step 6: Commit** *(ask first)*

```bash
git add frontend/src/features/designer/DesignerPage/componentSelection.ts \
        frontend/src/features/designer/DesignerPage/componentSelection.test.ts \
        frontend/src/features/designer/DesignerPage/DesignerPage.tsx \
        frontend/src/features/designer/configRepository.ts
git commit -m "feat(frontend): bind an instance to its component types (FE-D55)

deriveComponentSelection takes an optional binding; absent means today's
behaviour, so every existing package renders unchanged.

<trailer per the session's attribution rule>"
```

---

### Task 152: The File Mesh source component

**Files:**
- Modify: `frontend/contracts/packages/file-mesh/package.json` — replace the synthetic `COMP_SOURCE_FTP@FILETOFILE` component entirely.

**Interfaces:**
- Consumes: the canonical dialect established in Task 149; the hidden-field rule from Task 150.
- Produces: component `COMP_SOURCE`, `role: "source"`, `configPath: "file.source"`, carrying the discriminator key `connectorType`. Task 153 mirrors its structure; Task 154 binds instances to it.

**Field inventory** — all 50 keys read off the supplied source `application-operator.yml`, plus the discriminator. `when` is `{ "connectorType": "<X>" }` where shown; blank means always visible.

| Section (`ui.section`) | Key | type | default | when |
|---|---|---|---|---|
| CONNECTOR | `connectorType` | enum | `"NWFILE"` | — (`discriminator`, `virtual`, `required`, options `NWFILE,FTP,SFTP,GCS`) |
| GENERAL | `general.adapter_id` | string | *(none — operator-supplied)* | — (`required`) |
| GENERAL | `general.file_type` | int | `4` | — |
| GENERAL | `general.ignoreEmptyFiles` | bool | `false` | — |
| GENERAL | `general.state_backup_path` | string | `""` | — |
| GENERAL | `general.copy_file_mode_permissions` | int | `1` | — |
| GENERAL | `general.max_file_transfer_size` | int | `999000` | — |
| GENERAL | `general.max_files_allowed` | int | `99999` | — |
| GENERAL | `general.max_bandwidth_limit` | int | `100000000` | — |
| GENERAL | `general.clear_state_on_eod` | int | `1` | — |
| GENERAL | `general.eod_time_sec` | int | `-1` | — |
| GENERAL | `general.multiple_files_flag` | int | `0` | — |
| SCHEDULER | `scheduler.restart_time_sec` | int | `10` | — |
| FILE TO EVENT | `file_to_event.enabled` | int | `0` | — |
| FILE TO EVENT | `file_to_event.fileFormat` | int | `4` | — |
| FILE TO EVENT | `file_to_event.eventDelimiter` | string | `"|"` | — |
| FILE TO EVENT | `file_to_event.jsonPath` | string | `"$.Authors[*].Books[*]"` | — |
| FILE TO EVENT | `file_to_event.xPath` | string | `"/class/student"` | — |
| GCS | `gcs.enabled` | int | `1` | `GCS` (`ui.readOnly`) |
| GCS | `gcs.projectId` | string | — | `GCS` (`required`) |
| GCS | `gcs.credentialsFilePath` | string | — | `GCS` (`required`) |
| GCS | `gcs.retrySettings.maxAttempts` | int | `5` | `GCS` |
| GCS | `gcs.retrySettings.retryDelayMultiplier` | int | `3` | `GCS` |
| GCS | `gcs.retrySettings.maxDurationMinutes` | int | `2` | `GCS` |
| SFTP | `sftp_settings.enabled` | int | `1` | `SFTP` (`ui.readOnly`) |
| SFTP | `sftp_settings.ip` | string | — | `SFTP` (`required`) |
| SFTP | `sftp_settings.port` | int | `22` | `SFTP` (`min` 1, `max` 65535) |
| SFTP | `sftp_settings.user` | string | — | `SFTP` (`required`) |
| SFTP | `sftp_settings.password` | **secret** | — | `SFTP` |
| SFTP | `sftp_settings.strictHostKeyChecking` | enum | `"no"` | `SFTP` (options `yes,no`) |
| FTP | `ftp_settings.enabled` | int | `1` | `FTP` (`ui.readOnly`) |
| FTP | `ftp_settings.ip` | string | — | `FTP` (`required`) |
| FTP | `ftp_settings.port` | int | `21` | `FTP` (`min` 1, `max` 65535) |
| FTP | `ftp_settings.user` | string | — | `FTP` (`required`) |
| FTP | `ftp_settings.password` | **secret** | — | `FTP` |
| FTP | `ftp_settings.secured` | bool | `true` | `FTP` |
| DIRECTORY WILDCARD | `directory_wildcard.wildcard_type` | int | `0` | — |
| DIRECTORY WILDCARD | `directory_wildcard.config_path` | string | `""` | — |
| DIRECTORY REPLICATION | `directory_replication.start_time` | int | `-1` | — |
| SOLACE OUT | `solace_out.lvq` | string | `"file.source.lvq"` | — |
| SOLACE OUT | `solace_out.destination` | string | `"file/source/lvq"` | — |
| COMMAND CENTER | `command_center.enabled` | int | `1` | — |
| COMMAND CENTER | `command_center.useOutputDestinationSolaceCredentials` | bool | `true` | — |
| COMMAND CENTER | `command_center.heartbeat_enabled` | int | `1` | — |
| COMMAND CENTER | `command_center.heartbeat_interval` | int | `20` | — |
| COMMAND CENTER | `command_center.solace_base_publish_topic` | string | — | — |
| COMMAND CENTER | `command_center.solace_base_publish_topic_heartbeat` | string | — | — |
| COMMAND CENTER | `command_center.solace_messaging_mode` | enum | `"PERSISTENT"` | — (options `PERSISTENT,DIRECT`) |
| COMMAND CENTER | `command_center.events.fileStart` | bool | `true` | — |
| COMMAND CENTER | `command_center.events.fileComplete` | bool | `true` | — |

**Deviation, as executed (`FE-152`/`FE-153`): identifier-shaped fields carry no `default`.**
The table above lists sample-observed values (`"SOURCE_TEST_V1"`, `"centos"`) as defaults for `adapter_id` and
the `file_owner_permissions` override fields. As built, no such default is authored for any field that names an
environment (an adapter id, host, username, credential path, topic, `jsonPath`/`xPath`, `projectId`). A default of
`SOURCE_TEST_V1` would silently write a test identifier into a real instance's config. `strictHostKeyChecking`
and `solace_messaging_mode` are also authored as `string`, not `enum` — the samples show one value each
(`"no"`, `PERSISTENT"`), and `I2` does not permit inventing the rest of the value set. See
[`task-152-report.md`](reports/task-152-report.md) §2, §6 (Q-E, Q-F).

⚠️ **The lowercase duplicate is deliberately dropped.** The sample declares both `useOutputDestinationSolaceCredentials: true` and `useoutputdestinationsolacecredentials: 'true'`. Spring's relaxed binding treats these as one property; declaring both would write the same property twice. Only the camelCase `bool` is authored. This is open question Q-B in spec §11 — if the answer comes back lowercase-string, change this one field.

⚠️ **`solace_messaging_mode` options.** `PERSISTENT` is observed; `DIRECT` is the only other Solace messaging mode. If that cannot be confirmed against the connector, author it as `type: "string"` with the observed default instead — `I2` over convenience.

- [x] **Step 1: Write the failing test**

Create `frontend/contracts/packages/file-mesh/package.test.ts`:

```ts
import { describe, expect, it } from 'vitest';

import { project } from '../../view-model/project';
import type { SolutionPackage } from '../../view-model/types';
import { toFrontendPackage } from '../../solution-package';
import type { CanonicalSolutionPackage } from '../../solution-package/types';
import RAW from './package.json';

const pkg = toFrontendPackage(RAW as CanonicalSolutionPackage) as SolutionPackage;
const OBSERVED = { health: 'healthy', versionState: 'ok', configState: 'in_sync' } as const;

function visibleKeys(instanceId: string, type: string, draft: Record<string, unknown>): string[] {
  const vm = project(pkg, [{ type, instanceId }], { [instanceId]: draft }, OBSERVED, 1);
  return vm.sections.flatMap((s) => s.fields.filter((f) => f.visible).map((f) => f.key));
}

describe('file-mesh package', () => {
  it('defaults to NWFile with no connector subtree visible', () => {
    const keys = visibleKeys('s1', 'COMP_SOURCE', {});
    expect(keys).toContain('connectorType');
    expect(keys).toContain('general.adapter_id');
    expect(keys.filter((k) => /^(sftp_settings|ftp_settings|gcs)\./.test(k))).toEqual([]);
  });

  it('shows only the SFTP subtree when SFTP is selected', () => {
    const keys = visibleKeys('s1', 'COMP_SOURCE', { connectorType: 'SFTP' });
    expect(keys).toContain('sftp_settings.ip');
    expect(keys).toContain('sftp_settings.port');
    expect(keys.filter((k) => /^(ftp_settings|gcs)\./.test(k))).toEqual([]);
  });

  it('hides the SFTP subtree again when the connector changes back', () => {
    const keys = visibleKeys('s1', 'COMP_SOURCE', { connectorType: 'NWFILE' });
    expect(keys.filter((k) => k.startsWith('sftp_settings.'))).toEqual([]);
  });

  it('does not block on a required field hidden behind an inactive branch (Task 150)', () => {
    const vm = project(pkg, [{ type: 'COMP_SOURCE', instanceId: 's1' }], { s1: {} }, OBSERVED, 1);
    const ip = vm.sections.flatMap((s) => s.fields).find((f) => f.key === 'sftp_settings.ip');
    expect(ip?.visible).toBe(false);
    expect(ip?.validations).toEqual([]);
  });

  it('declares every password as a secret (I6)', () => {
    for (const component of pkg.components) {
      for (const field of component.fields) {
        if (field.key.endsWith('.password')) expect(field.secret).toBe(true);
      }
    }
  });
});
```

- [x] **Step 2: Run it and watch it fail**

Run: `cd frontend && npx vitest run contracts/packages/file-mesh/package.test.ts`
Expected: FAIL — the package still holds the synthetic `COMP_SOURCE_FTP@FILETOFILE` with `FILE_MESH_MANAGER_*` keys and no `connectorType`.

- [x] **Step 3: Author the source component**

Replace the first element of `components` with `COMP_SOURCE`, built from the table above. The canonical dialect — `label`, `helperText`, `advanced` nested under `ui`:

```jsonc
{
  "type": "COMP_SOURCE",
  "cardinality": "one-of-many",
  "role": "source",
  "configPath": "file.source",
  "loggerPrefix": "com.solace.connector.filemesh.source",
  "fields": [
    {
      "key": "connectorType",
      "type": "enum",
      "discriminator": true,
      "virtual": true,
      "default": "NWFILE",
      "required": true,
      "options": ["NWFILE", "FTP", "SFTP", "GCS"],
      "ui": {
        "section": "CONNECTOR", "order": 1, "label": "Connector type",
        "helperText": "Selects which file-system settings below apply. NWFile uses the location binding directly."
      }
    },
    { "key": "general.adapter_id", "type": "string", "required": true,
      "default": "SOURCE_TEST_V1",
      "ui": { "section": "GENERAL", "order": 1, "label": "Adapter ID" } },

    { "key": "sftp_settings.enabled", "type": "int", "default": 1,
      "when": { "connectorType": "SFTP" },
      "ui": { "section": "SFTP", "order": 1, "label": "Enabled", "readOnly": true,
              "helperText": "Set by the connector type; shown for confirmation." } },
    { "key": "sftp_settings.ip", "type": "string", "required": true,
      "when": { "connectorType": "SFTP" },
      "ui": { "section": "SFTP", "order": 2, "label": "Host" } },
    { "key": "sftp_settings.port", "type": "int", "default": 22, "min": 1, "max": 65535,
      "when": { "connectorType": "SFTP" },
      "ui": { "section": "SFTP", "order": 3, "label": "Port" } },
    { "key": "sftp_settings.password", "type": "secret",
      "when": { "connectorType": "SFTP" },
      "ui": { "section": "SFTP", "order": 5, "label": "Password",
              "helperText": "Held as a secretRef:// reference; the value is never stored by SM (I6)." } }
    // … every remaining row of the table, same shape
  ]
}
```

Also update the document header: `"appliesTo": ">=2.2 <3.0"` (the samples are connector 2.2.0) and set `authoring` to `{ "reviewedRows": 50, "totalRows": 50, "source": "application-operator.yml (source), supplied 2026-09-21" }`.

- [x] **Step 4: Run the tests and the canonical gates**

Run: `cd frontend && npx vitest run contracts/packages/file-mesh/package.test.ts`
Expected: the four source tests PASS; the secret test PASS.

Run: `python3 contracts/validation/validate-package.py frontend/contracts/packages/file-mesh/package.json`
Expected: PASS — verifies gates 4, 5, 5b, 6, 9, 11, 12, 13 against the new discriminator.

- [x] **Step 5: Commit** *(ask first)*

```bash
git add frontend/contracts/packages/file-mesh/package.json \
        frontend/contracts/packages/file-mesh/package.test.ts
git commit -m "feat(contracts): author the File Mesh source component (UI-D26)

Replaces the synthetic FILE_MESH_MANAGER_* filler with the 50 real keys
from the supplied operator YAML, gated by a connectorType discriminator.

<trailer per the session's attribution rule>"
```

---

### Task 153: The File Mesh sink component

**Files:**
- Modify: `frontend/contracts/packages/file-mesh/package.json` — replace `COMP_SINK_SOLACE@FILETOFILE`.
- Modify: `frontend/contracts/packages/file-mesh/package.test.ts`

**Interfaces:**
- Consumes: the `visibleKeys` helper and `pkg` binding from Task 152's test file.
- Produces: component `COMP_SINK`, `role: "sink"`, `configPath: "file.sink"`, with its own `connectorType` discriminator (same options, same default).

**Field inventory** — all 41 keys from the supplied sink YAML, plus the discriminator.

| Section | Key | type | default | when |
|---|---|---|---|---|
| CONNECTOR | `connectorType` | enum | `"NWFILE"` | — (as Task 152) |
| GENERAL | `general.adapter_id` | string | `"SINK_CONN_ID1"` | — (`required`) |
| GENERAL | `general.auto_create_directory` | int | `1` | — |
| GENERAL | `general.dest_file_name_type` | int | `3` | — |
| GENERAL | `general.storeBackupStateRemotely` | bool | `false` | — |
| GENERAL | `general.state_backup_path` | string | `"./backup_state.cfg"` | — |
| GENERAL | `general.clear_state_on_eod` | int | `0` | — |
| GENERAL | `general.eod_time_sec` | int | `-1` | — |
| GENERAL | `general.alwaysVerifyData` | bool | `true` | — |
| GENERAL | `general.alwaysMatchCompleteEvent` | bool | `true` | — |
| GCS | `gcs.enabled` | int | `1` | `GCS` (`ui.readOnly`) |
| GCS | `gcs.projectId` | string | — | `GCS` (`required`) |
| GCS | `gcs.credentialsFilePath` | string | — | `GCS` (`required`) |
| GCS | `gcs.enableBulkComposeStrategy` | bool | `true` | `GCS` — **sink-only; this is the SINK+GCS combination field** |
| GCS | `gcs.retrySettings.maxAttempts` | int | `5` | `GCS` |
| GCS | `gcs.retrySettings.retryDelayMultiplier` | int | `3` | `GCS` |
| GCS | `gcs.retrySettings.maxDurationMinutes` | int | `2` | `GCS` |
| SFTP | `sftp_settings.*` | | | identical to Task 152's six SFTP rows |
| FTP | `ftp_settings.*` | | | identical to Task 152's six FTP rows |
| FILE OWNER PERMISSIONS | `file_owner_permissions.copy_source_file_mode_permissions` | int | `0` | — |
| FILE OWNER PERMISSIONS | `file_owner_permissions.copy_source_file_owner` | int | `0` | — |
| FILE OWNER PERMISSIONS | `file_owner_permissions.override_username` | string | *(none — operator-supplied)* | — |
| FILE OWNER PERMISSIONS | `file_owner_permissions.override_group` | string | *(none — operator-supplied)* | — |
| COMMAND CENTER | `command_center.enabled` | int | `0` | — |
| COMMAND CENTER | `command_center.useOutputDestinationSolaceCredentials` | bool | `true` | — |
| COMMAND CENTER | `command_center.solace_base_publish_topic` | string | — | — |
| COMMAND CENTER | `command_center.solace_base_publish_topic_heartbeat` | string | — | — |
| COMMAND CENTER | `command_center.solace_messaging_mode` | enum | `"PERSISTENT"` | — |
| COMMAND CENTER | `command_center.heartbeat_enabled` | int | `0` | — |
| COMMAND CENTER | `command_center.heartbeat_interval` | int | `3` | — |
| COMMAND CENTER | `command_center.events.fileStart` | bool | `true` | — |
| COMMAND CENTER | `command_center.events.fileComplete` | bool | `true` | — |

- [x] **Step 1: Write the failing tests**

Append to `frontend/contracts/packages/file-mesh/package.test.ts`:

```ts
describe('file-mesh sink', () => {
  it('exposes the sink-only GCS strategy field for SINK + GCS', () => {
    const keys = visibleKeys('k1', 'COMP_SINK', { connectorType: 'GCS' });
    expect(keys).toContain('gcs.enableBulkComposeStrategy');
    expect(keys).toContain('gcs.projectId');
  });

  it('does not expose that field on the source, which has no such key', () => {
    const keys = visibleKeys('s1', 'COMP_SOURCE', { connectorType: 'GCS' });
    expect(keys).not.toContain('gcs.enableBulkComposeStrategy');
    expect(keys).toContain('gcs.projectId');
  });

  it('keeps source-only and sink-only subtrees apart', () => {
    const sink = visibleKeys('k1', 'COMP_SINK', {});
    const source = visibleKeys('s1', 'COMP_SOURCE', {});
    expect(sink).toContain('file_owner_permissions.override_username');
    expect(sink).not.toContain('scheduler.restart_time_sec');
    expect(source).toContain('scheduler.restart_time_sec');
    expect(source).not.toContain('file_owner_permissions.override_username');
  });

  it('renders sink values under file.sink and source values under file.source', () => {
    const vm = project(
      pkg,
      [{ type: 'COMP_SINK', instanceId: 'k1' }],
      { k1: { connectorType: 'GCS', 'gcs.projectId': 'p-1' } },
      OBSERVED,
      1,
    );
    const field = vm.sections.flatMap((s) => s.fields).find((f) => f.key === 'gcs.projectId');
    expect(field?.renderedPath).toBe('file.sink.gcs.projectId');
  });
});
```

- [x] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run contracts/packages/file-mesh/package.test.ts`
Expected: FAIL — `COMP_SINK` does not exist, so `visibleKeys` returns `[]`.

- [x] **Step 3: Author the sink component**

Same shape as Task 152, `configPath: "file.sink"`, `role: "sink"`, `loggerPrefix: "com.solace.connector.filemesh.sink"`, built from the table above. The SFTP and FTP blocks repeat Task 152's six rows each verbatim except for the section ordering — they are genuinely the same six keys in the sink's own subtree, not a cross-reference.

- [x] **Step 4: Run the tests and the gates**

Run: `cd frontend && npx vitest run contracts/packages/file-mesh/package.test.ts && npm run test`
Expected: PASS.

Run: `python3 contracts/validation/validate-package.py frontend/contracts/packages/file-mesh/package.json`
Expected: PASS.

- [x] **Step 5: Commit** *(ask first)*

```bash
git add frontend/contracts/packages/file-mesh/package.json \
        frontend/contracts/packages/file-mesh/package.test.ts
git commit -m "feat(contracts): author the File Mesh sink component (UI-D26)

41 real keys including the SINK+GCS-only enableBulkComposeStrategy, the
one role-x-connector combination field the evidence contains.

<trailer per the session's attribution rule>"
```

---

### Task 154: Location bindings and File Mesh instances

**Why.** Without a location field the form has nowhere to point. Spec §11.1 carves out exactly two keys from the otherwise-deferred `spring.*` tree.

**Files:**
- Modify: `frontend/contracts/packages/file-mesh/package.json` — add two components
- Modify: `frontend/src/shared/testdoubles/data/instances.ts` — File Mesh rows
- Modify: `frontend/src/shared/testdoubles/data/packages.ts` — supply `componentTypes`
- Modify: `frontend/contracts/packages/file-mesh/package.test.ts`

**Interfaces:**
- Consumes: `deriveComponentSelection(..., componentTypes)` from Task 151; `COMP_SOURCE` / `COMP_SINK` from Tasks 152–153.
- Produces: components `COMP_SOURCE_BINDING` and `COMP_SINK_BINDING`, and `InstanceRow.componentTypes?: readonly string[]`.

- [x] **Step 1: Write the failing test**

Append to `frontend/contracts/packages/file-mesh/package.test.ts`:

```ts
describe('location binding', () => {
  it('renders the source location under the stream binding path, not under file.source', () => {
    const vm = project(
      pkg,
      [{ type: 'COMP_SOURCE_BINDING', instanceId: 'sb' }],
      { sb: { 'input-0.destination': '/inbound/feed' } },
      OBSERVED,
      1,
    );
    const field = vm.sections.flatMap((s) => s.fields).find((f) => f.key === 'input-0.destination');
    expect(field?.renderedPath).toBe('spring.cloud.stream.bindings.input-0.destination');
  });

  it('uses output-0 for the sink', () => {
    const vm = project(
      pkg,
      [{ type: 'COMP_SINK_BINDING', instanceId: 'kb' }],
      { kb: { 'output-0.destination': './data/output' } },
      OBSERVED,
      1,
    );
    const field = vm.sections.flatMap((s) => s.fields).find((f) => f.key === 'output-0.destination');
    expect(field?.renderedPath).toBe('spring.cloud.stream.bindings.output-0.destination');
  });
});
```

- [x] **Step 2: Run it and watch it fail**

Run: `cd frontend && npx vitest run contracts/packages/file-mesh/package.test.ts`
Expected: FAIL — neither binding component exists.

- [x] **Step 3: Add the two binding components**

Append to `components` in `frontend/contracts/packages/file-mesh/package.json`:

```jsonc
{
  "type": "COMP_SOURCE_BINDING",
  "cardinality": "exactly-one",
  "role": "source",
  "configPath": "spring.cloud.stream.bindings",
  "fields": [
    { "key": "input-0.destination", "type": "string", "required": true,
      "ui": { "section": "LOCATION", "order": 1, "label": "Source location",
              "helperText": "Path or URI the connector reads from. Applies to every connector type." } }
  ]
},
{
  "type": "COMP_SINK_BINDING",
  "cardinality": "exactly-one",
  "role": "sink",
  "configPath": "spring.cloud.stream.bindings",
  "fields": [
    { "key": "output-0.destination", "type": "string", "required": true,
      "ui": { "section": "LOCATION", "order": 1, "label": "Sink location",
              "helperText": "Path or URI the connector writes to. Applies to every connector type." } }
  ]
}
```

⚠️ **Working assumption on record** (spec §11.1): one location field serves all four connectors. Both samples are NWFile, so this is unverified. Revisit before `UI-D26` ratifies.

- [x] **Step 4: Bind the instances**

⚠️ **Corrected during `FE-154`: do not append to `CACHE_INSTANCES`.** `instances.test.ts` asserts
`expect(CACHE_INSTANCES).toHaveLength(12)`, citing the reference spec's own closed 12-instance enumeration
(§A.13.2). Appending rows there breaks a deliberate, documented invariant, not just a number.

In `frontend/src/shared/testdoubles/data/instances.ts`, add `componentTypes?: readonly string[] | undefined` to
`InstanceRow` with a comment pointing at `FE-D55`, and add a **separate** exported array after
`CACHE_INSTANCES` closes:

```ts
export const FILE_MESH_INSTANCES: readonly InstanceRow[] = [
  {
    id: 'fm-source-1',
    name: 'Frankfurt feed (source)',
    solutionId: 'file-mesh',
    componentTypes: ['COMP_SOURCE', 'COMP_SOURCE_BINDING'],
    packageVersion: 1,
    env: 'PROD',
    // … the remaining InstanceRow fields, matching the cache rows above
  },
  {
    id: 'fm-sink-1',
    name: 'Frankfurt archive (sink)',
    solutionId: 'file-mesh',
    componentTypes: ['COMP_SINK', 'COMP_SINK_BINDING'],
    packageVersion: 1,
    env: 'PROD',
    // …
  },
] as const;
```

Merge it explicitly where "every instance across every solution" is meant: `devPackageHandlers.ts`'s
`allInstances()` (it already merges `CACHE_INSTANCES` with session-declared instances) and
`shared/testdoubles/handlers/dp.ts`'s own `ALL_INSTANCES`, since that default fixture handler does not go
through `devPackageHandlers.ts` at all. Do **not** touch `packages.ts` — `DesignerLoad.componentTypes` is carried
by the `dp` handler response, not derived in `packageForSolution`.

⚠️ **A `shared` test must never import a `feature` module** (`eslint-plugin-boundaries`). A test that exercises
`deriveComponentSelection` against these fixtures belongs in `src/features/designer/DesignerPage/`, importing
`@shared/testdoubles/*` — the allowed direction — not the reverse. Caught by lint during `FE-154`; the plan
now states it up front so the next author doesn't rediscover it the same way.

- [x] **Step 5: Run everything**

Run: `cd frontend && npm run test && npm run typecheck && npm run lint`
Expected: PASS.

Run: `python3 contracts/validation/validate-package.py frontend/contracts/packages/file-mesh/package.json`
Expected: PASS, `components=4`.

- [x] **Step 6: Verify it in the running app** — done via Playwright + headless Chromium against `npm run dev` (no `chromium-cli` available; `chromium.launch({args:['--no-sandbox']})` per the `run` skill's fallback). All five items confirmed; screenshots and the one out-of-scope finding (File Mesh absent from the sidebar `SOLUTIONS` catalog) are in [`task-154-report.md`](reports/task-154-report.md) §6.

Run: `cd frontend && npm run dev`, open the Designer for **Frankfurt feed (source)**, and confirm by eye:
1. Only source sections render — no sink sections, no `file_owner_permissions`.
   1b. Open **Governance** for the same instance: the log viewer's Component list offers only that role's components (`FE-151` — a second consumer of `deriveComponentSelection`).
2. Connector type defaults to **NWFile**; no SFTP/FTP/GCS section is present.
3. Switching to **SFTP** reveals the SFTP section; switching back hides it.
4. Typing a host, switching to NWFile, and switching back **restores the typed host** (`UI-D4` retention).
5. With NWFile selected, the form submits — the hidden `required` SFTP host does not block it (Task 150).

- [x] **Step 7: Commit** *(ask first)*

```bash
git add frontend/contracts/packages/file-mesh/package.json \
        frontend/contracts/packages/file-mesh/package.test.ts \
        frontend/src/shared/testdoubles/data/instances.ts \
        frontend/src/shared/testdoubles/data/packages.ts
git commit -m "feat(frontend): File Mesh location bindings and bound instances

Adds the two-key spring.cloud.stream.bindings carve-out per role and two
bound File Mesh instances exercising FE-D55.

<trailer per the session's attribution rule>"
```

---

### Task 155: Register the decisions

**Why.** `I3`: a spec proposing decision IDs that are not in the register is exactly the drift the invariant exists to prevent.

**Files:**
- Modify: `docs/design/dynamic-ui/decisions.md` — add `UI-D26`
- Modify: `docs/design/frontend/decisions.md` — add `FE-D55`
- Modify: `docs/design/DECISIONS.md` — add the dialect-split open item

**Interfaces:**
- Consumes: the spec's §4, §5 and §14.
- Produces: nothing code depends on.

- [x] **Step 1: Confirm the next free IDs**

Run:
```bash
grep -o "UI-D[0-9]*" docs/design/dynamic-ui/decisions.md | sort -t D -k2 -n -u | tail -2
grep -o "FE-D[0-9]*" docs/design/frontend/decisions.md | sort -t D -k2 -n -u | tail -2
```
Expected: `UI-D25` and `FE-D54` are the current maxima, so `UI-D26` and `FE-D55` are free. **If not, stop and ask** — do not renumber and do not reuse.

- [x] **Step 2: Add `UI-D26`**

In `docs/design/dynamic-ui/decisions.md`, following the file's existing entry format, status **PROPOSED**: role is a component, a within-document variant is a `virtual` `discriminator` enum. Rationale from spec §4. Link to the spec. Note it is consistent with `UI-D1`, `UI-D2`, `UI-D4`, `UI-D24` and supersedes nothing.

- [x] **Step 3: Add `FE-D55`**

In `docs/design/frontend/decisions.md`, status **PROPOSED**: an instance binds to its component types; absent means every declared component renders. Rationale from spec §5. Record that §16 Q10 stays open — this decision governs consumption, not authoring.

- [x] **Step 4: Add the open item** (registered as item 37)

In `docs/design/DECISIONS.md`'s open-items list, using the next free number, record the two-dialect split from spec §14: what it is, that Task 149 fixed the packages themselves, and that the remaining work is deciding whether `frontend/contracts/packages/` holds authored or normalized packages and gating that in CI. Cross-reference open item 36.

- [x] **Step 5: Verify the links resolve** — no `BROKEN` lines

Run from `docs/design/dynamic-ui/`:
```bash
grep -o "](\.\./[^)]*)" ../DECISIONS.md decisions.md ../frontend/decisions.md \
  | sed 's/.*](//;s/)$//' | sort -u | while read p; do
    [ -e "$p" ] && echo "OK   $p" || echo "BROKEN $p"; done
```
Expected: no `BROKEN` lines.

- [x] **Step 6: Commit** — landed as `0b77edb`

```bash
git add docs/design/dynamic-ui/decisions.md docs/design/frontend/decisions.md docs/design/DECISIONS.md
git commit -m "docs: register UI-D26 and FE-D55, open the package-dialect item

<trailer per the session's attribution rule>"
```

---

## Final verification

- [x] `cd frontend && npm run test` — all suites pass (verified per-file/per-project due to a host power-management issue during this session; see the closing summary and `task-149`-`task-154` reports for the evidence trail)
- [x] `cd frontend && npm run typecheck && npm run lint` — clean
- [x] `./contracts/run-checks.sh` — RESULT: PASSED (steps 4-6 skipped: the sibling `file-mesh-ui` repo isn't present on this machine, the documented, expected skip; unrelated to this plan). Required installing PyYAML into the local Python environment first (`pip3 install -r contracts/requirements.txt`) — an environment fix, not a repo change.
- [x] `python3 contracts/validation/validate-package.py 'frontend/contracts/packages/*/package.json'` — all nine PASS
- [x] Task 154 Step 6's five manual checks confirmed in the running app — see `task-154-report.md` §6
- [x] `grep -rn "SFTP\|NWFILE\|file-mesh" frontend/src --include="*.ts" --include="*.tsx" | grep -v testdoubles | grep -v "\.test\."` returns nothing — no File Mesh identifier leaked into React (every hit is a comment or the pre-existing sidebar gallery row)
- [x] Every task has its report (see **Documentation obligations** below)
- [x] `docs/plans/frontend/task-list.md`'s ⚠️ note extended to name this plan as covering `149`–`155` (that list stops at `FE-125` by design; `reports/` is the register above it — do **not** add task rows)
- [x] A **Review** section is appended to this plan recording what was built and what deviated

## Documentation obligations

⚠️ **`task-129-report.md` already exists** — it belongs to `FE-129`, a different task. Plan
numbers and FE task numbers are separate sequences: this is *plan* 129, holding *tasks*
149–155. Do not overwrite that file.

**One report per task**, written as part of that task, before its commit:

| Task | Report |
|---|---|
| 149 | `docs/plans/frontend/reports/task-149-report.md` |
| 150 | `docs/plans/frontend/reports/task-150-report.md` |
| 151 | `docs/plans/frontend/reports/task-151-report.md` |
| 152 | `docs/plans/frontend/reports/task-152-report.md` |
| 153 | `docs/plans/frontend/reports/task-153-report.md` |
| 154 | `docs/plans/frontend/reports/task-154-report.md` |
| 155 | `docs/plans/frontend/reports/task-155-report.md` |

Follow the house format (see `reports/task-148-report.md`): what was wrong or needed, what
changed, **what deliberately did not change** and which invariant protects it, and the
evidence that it works. State deviations from this plan plainly rather than quietly
conforming the plan to what happened.

**Where each kind of documentation belongs** — do not improvise a new location:

| What | Where |
|---|---|
| Decisions, rationale, supersession | `docs/design/<folder>/decisions.md` + the matrix in `docs/design/DECISIONS.md` |
| Open/unresolved items | `docs/design/DECISIONS.md` (the *what and why it blocks*) |
| This design's reasoning | `docs/design/dynamic-ui/filemesh-source-sink-connectors.md` |
| Per-task outcome | `docs/plans/frontend/reports/task-NNN-report.md` |
| Task inventory | `docs/plans/frontend/task-list.md` |
| Status / assignment | the **GitHub Project**, never a file |
| Lessons from a correction | `docs/tasks/lessons.md`, or `docs/design/common/lessons.md` if cross-cutting — when in doubt, cross-cutting |

Anything decided while executing must land in `decisions.md` before the work closes. A commit
message is not a durable record.

## Spec coverage

| Spec section | Task |
|---|---|
| §3 model, §4 `UI-D26` | 152, 153, 155 |
| §5 `FE-D55` | 151, 154, 155 |
| §6 no schema change | verified in 152, 153, 154 via `validate-package.py` |
| §7 no grammar change | Global Constraints; nothing in any task adds an operator |
| §8 validation convergence | 150 |
| §9 state handling | already shipped; Task 154 Step 6 items 4–5 verify it end to end |
| §10 backward compatibility | 149, 151 (fallback tests), Final verification |
| §11.1 binding carve-out | 154 |
| §11 secrets | 152 (`I6` test), 153 |
| §11 data defects | 152 (Q-B note), 153 |
| §12 payloads | 153 Step 1 (`renderedPath`), 154 Step 1 |
| §14 dialect split | 149 (packages), 155 (open item for the CI gate) |

---

## Review

### FE-149 — done 2026-09-21

Built and verified: 340/340 files, 2414/2414 tests, typecheck clean, lint clean, 9/9 packages pass
`validate-package.py`. Full account in [`reports/task-149-report.md`](reports/task-149-report.md).

**Where the plan was wrong, stated plainly:**

1. It assumed a green branch. `HEAD` had 4 failing tests and 1 lint error. → Baseline constraint added above.
2. It asserted file-mesh was broken "right now". That was the uncommitted working tree, not `HEAD`. → Retracted in spec §14.1.
3. It predicted "all nine PASS" after only the dialect change. Three of cache's 29 violations were not dialect. → Step 4b.
4. It reversed L19's interim rule without having read L19. → Recorded in L19, open item 36 and report §5.4; the rule was superseded, not deleted.
5. It had not looked at `git log` for the file it was about to rewrite, which is how `b13c241`'s collateral regression went unseen. → L20.

Tasks 150–155 are unchanged by these findings except that each now starts by running its gate to record the baseline.

### FE-150 through FE-155 — done 2026-09-22

All seven tasks committed on `dev/frontend`. Full account per task in `reports/task-150-report.md` through
`task-155-report.md`; summary here.

- **`FE-150`.** Frontend `project()` converges with the canonical engine on hidden-field validation (open item
  36, one rule). Fixed generate-then-freeze into hand-authored-first so the fixture could actually fail before
  the fix.
- **`FE-151`.** `FE-D55` binding, plus a second consumer the plan hadn't listed — the Governance log viewer
  reads the same function and needed the same fix, caught by grepping callers before committing.
- **`FE-152`/`FE-153`.** The real File Mesh package: `COMP_SOURCE` (50 fields) and `COMP_SINK` (42 fields) from
  the two operator YAMLs, connector branches gated by `when`, the sink-only `enableBulkComposeStrategy` as the
  one role×connector combination field the evidence contains. Deviated from the plan's own field table on
  identifier defaults and two enum-vs-string calls, both under `I2` — corrected in the plan, not silently
  shipped different from what it says.
- **`FE-154`.** Location bindings plus two bound fixture instances. Found and reverted a mistake before commit:
  appending to `CACHE_INSTANCES` would have broken its own `toHaveLength(12)` test, so the instances live in a
  separate `FILE_MESH_INSTANCES` export instead. Found and fixed a `shared`→`feature` boundary violation in the
  first draft of the end-to-end test via `eslint-plugin-boundaries`, before commit.
- **`FE-155`.** `UI-D26`, `FE-D55` and open item 37 registered, next-free IDs confirmed by grep immediately
  before writing.

**Final verification, in full:**

| Gate | Result |
|---|---|
| `./contracts/run-checks.sh` | **RESULT: PASSED** — steps 4–6 skip on the documented, expected absence of the sibling `file-mesh-ui` repo; both file-mesh fixtures (the adapter's and this plan's real package) pass every gate |
| `validate-package.py`, all nine bundled packages | 9 / 9 PASS |
| `npm run typecheck` / `npm run lint` (whole tree) | clean / clean at `--max-warnings=0` |
| `npm run test` | all suites pass — verified per-file/per-project rather than in one invocation, because of a host power-management issue during this session (below); every file any run ever flagged was re-verified alone and found clean |
| Manual browser verification (Task 154 Step 6, all 5 items) | done live via Playwright + headless Chromium — see `task-154-report.md` §6 |
| No File Mesh identifier in non-fixture `src/` | confirmed by grep — every hit is a comment or the pre-existing sidebar gallery row |

**An environment issue affected verification method, not verification rigor.** This machine periodically
suspended (~every 15 minutes, ~900s each) under a battery-power `standby`/Power Nap policy that `caffeinate`
could not fully override without `sudo`, which was not available. Rather than accept a weaker check, every test
file any run ever flagged was re-run completely alone until proven clean or genuinely fixed — none of the
stalls turned out to be a real failure. Recommended fix for future sessions on this machine: plug into AC power,
or `sudo pmset -b powernap 0 standby 0`.

**Two out-of-scope findings, recorded rather than fixed here:**

- **File Mesh is not in the sidebar `SOLUTIONS` catalog** (`shared/testdoubles/data/solutions.ts`), so it isn't
  reachable by clicking through the left nav — only by direct URL. Consistent with §16 Q10 (no declare/catalog
  flow exists yet); this plan proves rendering is correct once on the page, not that the solution is
  discoverable through normal navigation.
- **Open item 37** (`frontend/contracts/packages/` has no CI gate) is registered but not closed — a CI step
  running `validate-package.py` over that directory is the natural next small task.

