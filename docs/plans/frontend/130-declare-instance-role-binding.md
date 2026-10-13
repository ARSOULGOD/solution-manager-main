# Declare-instance role binding — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a package declares two or more distinct non-`none` component roles (`UI-D26`), `DeclareInstanceDialog` offers a required `Role` select and resolves the choice to a `componentTypes` binding before the declare request is built — closing the fixture-layer authoring half of `FE-D55` that task 115 deliberately left open (§16 Q10).

**Architecture:** One new pure module (`componentRoles.ts`) groups a package's version-eligible components by `role`. The dialog fetches the raw package once a package version is chosen (`FE-D33`: the browser projects, the dev handler stays a passive `GET`), derives groups client-side, and — only when there are two or more — shows the select and folds the choice into `componentTypes` inside `toDeclareInput`. `role` itself never reaches the wire; only the resolved `componentTypes` list does, the field `FE-D55` already sanctioned. A second, smaller fix rides along: the `/designer` dev handler never read a session-declared instance's `componentTypes` at all, so it starts doing so.

**Tech Stack:** TypeScript, React 19, Vite, Vitest, Testing Library, React Hook Form is NOT used on this form (plain `useState`, per the existing `DeclareInstanceDialog`/`declareForm.ts` pattern), MUI 9, TanStack Query 5, MSW (dev handlers).

**Spec:** [`docs/design/frontend/design.md` §10.3](../../design/frontend/design.md#103-role-selection-at-declare-time--authoring-half-of-fe-d55-fe-d56) and [`docs/design/frontend/decisions.md` `FE-D56`](../../design/frontend/decisions.md#fe-d56--role-select-at-declare-time-resolves-to-a-componenttypes-list-before-it-reaches-the-wire) — read both before Task 156. Every task argues from them.

## Global Constraints

- **`role` is never sent, never stored.** Only the resolved `componentTypes` list crosses `toDeclareInput`. Do not add a `role` field to `DeclareInstanceInput`, `InstanceRow`, or any stored shape.
- **Fewer than two non-`none` role groups ⇒ nothing changes.** No `Role` field renders, `componentTypes` stays `undefined`, behaviour is byte-identical to today. This is the same backward-compatibility guarantee `FE-D55` states for the consumption side — do not special-case a single-role package.
- **No solution identifier in `src/`.** No `'file-mesh'`, `'source'`, `'sink'`, `'COMP_SOURCE'` literals in any file under `frontend/src/` outside test fixtures (`REQ-001`). Role grouping is generic over whatever distinct strings the package declares.
- **No silent default for `Role`.** Same rule as `Package version` (`declareForm.ts`'s existing discipline): starts unchosen, is a validation error until chosen, never defaults to the first option.
- **The submit button is never disabled with no stated reason** (the dialog's own existing rule, in its file header). A still-loading role-options fetch is surfaced as a Declare-press validation message, not a disabled control.
- **Commit message trailer** on every commit: whatever the executing session's attribution rule specifies.
- **Ask before every commit** — do not commit without the developer's confirmation.
- **Verification commands** (run from `frontend/`): `npm run test`, `npm run typecheck`, `npm run lint`. From repo root: `./contracts/run-checks.sh` (unaffected by this plan — no `contracts/` file changes — but run it once at the end to confirm).
- **Decision IDs are immutable.** This plan implements `FE-D56`, already registered in `docs/design/frontend/decisions.md`. Do not renumber, do not reuse.

---

## File Structure

| Path | Responsibility | Task |
|---|---|---|
| `frontend/src/features/inventory/instances/componentRoles.ts` | pure role-grouping derivation (create) | 156 |
| `frontend/src/shared/api/contracts/instances.ts` | `DeclareInstanceInput.componentTypes?` | 157 |
| `frontend/src/features/inventory/solutions/solutionsRepository.ts` | `getPackage()` | 157 |
| `frontend/src/app/dev/devPackageHandlers.ts` | new `GET .../package` route; `/designer` `componentTypes` fix | 157 |
| `frontend/src/app/dev/devPackageFlow.test.ts` | end-to-end coverage of both handler changes | 157 |
| `frontend/src/features/inventory/instances/declareForm.ts` | `role` state, validation, `toDeclareInput` | 158 |
| `frontend/src/shared/i18n/messages.en.ts` | new strings | 158, 159 |
| `frontend/src/features/inventory/instances/DeclareInstanceDialog.tsx` | `Role` select, package fetch | 159 |
| `frontend/src/features/inventory/instances/InstancesPage.tsx` | thread `solutionsRepository` into the dialog | 159 |
| `frontend/src/shared/testdoubles/data/declaredInstance.ts` | `componentTypes` through `readDeclareInput`/`buildDeclaredInstance` | 160 |

---

### Task 156: Pure role-grouping derivation

**Files:**
- Create: `frontend/src/features/inventory/instances/componentRoles.ts`
- Test: `frontend/src/features/inventory/instances/componentRoles.test.ts`

**Interfaces:**
- Consumes: `inVersion(declared, selected)` from `@contracts/view-model/project`; `ComponentDeclaration`, `PackageVersionId`, `SolutionPackage` from `@contracts/view-model/types`.
- Produces: `ComponentRoleGroup { role: string; componentTypes: readonly string[] }`, `deriveComponentRoleGroups(pkg, packageVersion): readonly ComponentRoleGroup[]`, `sharedComponentTypes(pkg, packageVersion): readonly string[]` — both consumed by Task 159's dialog.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/features/inventory/instances/componentRoles.test.ts`:

```ts
import { describe, expect, it } from 'vitest';

import type { ComponentDeclaration, SolutionPackage } from '@contracts/view-model/types';

import { deriveComponentRoleGroups, sharedComponentTypes } from './componentRoles';

function pkg(): SolutionPackage {
  const components: ComponentDeclaration[] = [
    { type: 'a-source', cardinality: 'one-of-many', role: 'source', configPath: 'a.source', loggerPrefix: 'x', fields: [] },
    { type: 'a-sink', cardinality: 'one-of-many', role: 'sink', configPath: 'a.sink', loggerPrefix: 'y', fields: [] },
    { type: 'a-sink-v2-only', cardinality: 'one-of-many', role: 'sink', version: [2], configPath: 'a.sink2', loggerPrefix: 'z', fields: [] },
    { type: 'logging', cardinality: 'zero-or-more', role: 'none', configPath: 'logging', loggerPrefix: 'l', fields: [] },
    { type: 'server', cardinality: 'zero-or-more', configPath: 'server', loggerPrefix: 's', fields: [] },
  ];
  return {
    solution: 'test',
    packageVersion: [1, 2],
    appliesTo: '>=1.0 <3.0',
    provenance: { author: 'acme', signature: 'test-fixture' },
    authoring: { reviewedRows: 5, totalRows: 5 },
    components,
  };
}

describe('deriveComponentRoleGroups', () => {
  it('groups version-eligible components by role, excluding role undefined/"none"', () => {
    expect(deriveComponentRoleGroups(pkg(), 1)).toEqual([
      { role: 'source', componentTypes: ['a-source'] },
      { role: 'sink', componentTypes: ['a-sink'] },
    ]);
  });

  it('a role with two components at one version returns both types, one group', () => {
    expect(deriveComponentRoleGroups(pkg(), 2)).toEqual([
      { role: 'source', componentTypes: ['a-source'] },
      { role: 'sink', componentTypes: ['a-sink', 'a-sink-v2-only'] },
    ]);
  });

  it('a package with zero or one non-none role returns that many groups, never a synthesized second one', () => {
    const single: SolutionPackage = {
      ...pkg(),
      components: pkg().components.filter((c) => c.role !== 'sink'),
    };
    expect(deriveComponentRoleGroups(single, 1)).toEqual([{ role: 'source', componentTypes: ['a-source'] }]);
  });
});

describe('sharedComponentTypes', () => {
  it('returns version-eligible components whose role is undefined or "none"', () => {
    expect(sharedComponentTypes(pkg(), 1)).toEqual(['logging', 'server']);
  });

  it('excludes a shared component declared only for another version', () => {
    const versioned: SolutionPackage = {
      ...pkg(),
      components: [...pkg().components, { type: 'v2-only-shared', cardinality: 'zero-or-more', role: 'none', version: [2], configPath: 'v2', loggerPrefix: 'v', fields: [] }],
    };
    expect(sharedComponentTypes(versioned, 1)).toEqual(['logging', 'server']);
    expect(sharedComponentTypes(versioned, 2)).toEqual(['logging', 'server', 'v2-only-shared']);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/inventory/instances/componentRoles.test.ts`
Expected: FAIL — `Cannot find module './componentRoles'` (or similar), since the file does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `frontend/src/features/inventory/instances/componentRoles.ts`:

```ts
/**
 * The authoring half of `FE-D55` (`FE-D56`, design §10.3). `role` is
 * already a closed-vocabulary field per component (`UI-D26`); this groups
 * a package's version-eligible components by role so the declare dialog
 * can offer a choice when — and only when — more than one distinct
 * non-`none` role exists. Nothing here names a solution, a role string, or
 * a component type (`REQ-001`); a future package under the same schema
 * needs no change here.
 *
 * `deriveComponentSelection` (`componentSelection.ts`) is this function's
 * downstream consumer, unchanged by this module: it still takes an
 * explicit `componentTypes` list and never sees `role`.
 */
import { inVersion } from '@contracts/view-model/project';
import type { ComponentDeclaration, PackageVersionId, SolutionPackage } from '@contracts/view-model/types';

export interface ComponentRoleGroup {
  role: string;
  componentTypes: readonly string[];
}

function eligible(pkg: SolutionPackage, packageVersion: PackageVersionId): readonly ComponentDeclaration[] {
  return pkg.components.filter((component) => inVersion(component.version, packageVersion));
}

/** One group per distinct non-`none` role among this version's components, in first-seen order. */
export function deriveComponentRoleGroups(
  pkg: SolutionPackage,
  packageVersion: PackageVersionId,
): readonly ComponentRoleGroup[] {
  const byRole = new Map<string, string[]>();
  for (const component of eligible(pkg, packageVersion)) {
    if (component.role === undefined || component.role === 'none') continue;
    const types = byRole.get(component.role) ?? [];
    types.push(component.type);
    byRole.set(component.role, types);
  }
  return [...byRole.entries()].map(([role, componentTypes]) => ({ role, componentTypes }));
}

/** Version-eligible component types with no role, or `role: 'none'` — always part of any binding. */
export function sharedComponentTypes(pkg: SolutionPackage, packageVersion: PackageVersionId): readonly string[] {
  return eligible(pkg, packageVersion)
    .filter((component) => component.role === undefined || component.role === 'none')
    .map((component) => component.type);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/features/inventory/instances/componentRoles.test.ts`
Expected: PASS, 5 tests.

- [ ] **Step 5: Typecheck and lint**

Run: `cd frontend && npm run typecheck && npm run lint`
Expected: clean.

- [ ] **Step 6: Commit**

Ask the developer to confirm first.

```bash
git add frontend/src/features/inventory/instances/componentRoles.ts frontend/src/features/inventory/instances/componentRoles.test.ts
git commit -m "feat(frontend): role-grouping derivation for declare-instance binding (FE-156)"
```

---

### Task 157: Wire contract — `getPackage()`, `componentTypes`, and the two dev-handler fixes

**Files:**
- Modify: `frontend/src/shared/api/contracts/instances.ts`
- Modify: `frontend/src/features/inventory/solutions/solutionsRepository.ts`
- Modify: `frontend/src/app/dev/devPackageHandlers.ts`
- Modify: `frontend/src/app/dev/devPackageFlow.test.ts`

**Interfaces:**
- Consumes: `PlaneClient.get<T>(path, options?)` from `@shared/api`; `packageForSolution`, `devPackageStore.get()`, `projectable()` (all already in scope in `devPackageHandlers.ts`).
- Produces: `SolutionsRepository.getPackage(solutionId, packageVersion, signal?): Promise<SolutionPackage>` — consumed by Task 159's dialog. `DeclareInstanceInput.componentTypes?: readonly string[]` — consumed by Task 158/160.

- [ ] **Step 1: Write the failing tests**

Append to `frontend/src/app/dev/devPackageFlow.test.ts` (inside the existing `describe` block, alongside the other `it()`s — read the file first to match its existing `beforeEach`/`afterEach` and `solutions`/`pkg` bindings before inserting):

```ts
it('serves the raw package by solutionId + packageVersion for a bundled solution', async () => {
  const cachePkg = await solutions.getPackage('cache', 6);
  expect(cachePkg.solution).toBe('cache');
  expect(cachePkg.components.length).toBeGreaterThan(0);
});

it('serves the raw package for a package the operator dropped this session', async () => {
  devPackageStore.commitPending();
  devPackageStore.holdPending(pkg);
  devPackageStore.commitPending();
  const loaded = await solutions.getPackage(pkg.solution, pkg.packageVersion[0] ?? 1);
  expect(loaded.solution).toBe(pkg.solution);
});
```

(The third scenario — a session-declared instance's `componentTypes` reaching `loadDesigner()` — needs
`devDeclaredInstances`/`readDeclareInput` to carry the field through, which is Task 160's change, not this task's.
Adding it here would fail for the wrong reason before Task 160 runs. It is added in Task 160 Step 4 instead, once
the field genuinely round-trips end to end.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/app/dev/devPackageFlow.test.ts`
Expected: FAIL — `solutions.getPackage is not a function` on both new tests (the method does not exist on
`SolutionsRepository` yet).

- [ ] **Step 3: Add `componentTypes` to the wire type**

Modify `frontend/src/shared/api/contracts/instances.ts` — add after `replicas`:

```ts
  /** At least one, unique. NAMES only — a replica's health is observed, never declared. */
  replicas: readonly string[];
  /**
   * The component types this instance composes (`FE-D55`/`FE-D56`).
   * Absent means no binding — every version-eligible component renders,
   * identical to the behaviour before role selection existed. Computed
   * client-side from a `Role` choice when the package declares two or
   * more roles (`declareForm.ts`'s `toDeclareInput`); `role` itself is
   * never part of this input.
   */
  componentTypes?: readonly string[];
  /** Omitted when the operator doesn't know it yet — never guessed. */
  declaredVersion?: string;
```

- [ ] **Step 4: Add `getPackage()` to `SolutionsRepository`**

Modify `frontend/src/features/inventory/solutions/solutionsRepository.ts` — add to the interface and implementation:

```ts
export interface SolutionsRepository {
  list(signal?: AbortSignal): Promise<SolutionPackageSummary[]>;
  /**
   * The raw Solution Package for one solution + version (`FE-D56`, design
   * §10.3) — used only to derive `Role` options at declare time. Not the
   * Designer's load path (`ConfigRepository.loadDesigner`), which is
   * per-instance; this is per-solution, for before an instance exists.
   */
  getPackage(
    solutionId: string,
    packageVersion: string | number,
    signal?: AbortSignal,
  ): Promise<SolutionPackage>;
  load(file: Pick<File, 'name' | 'size'>, signal?: AbortSignal): Promise<PackageLoadResult>;
  commitPending?(): void;
  discardPending?(): void;
}
```

Add the import at the top: `import type { SolutionPackage } from '@contracts/view-model/types';`

Add to `createSolutionsRepository`'s returned object, alongside `list`:

```ts
    getPackage(solutionId, packageVersion, signal) {
      return cpClient.get<SolutionPackage>(
        `/solutions/${encodeURIComponent(solutionId)}/package?packageVersion=${encodeURIComponent(String(packageVersion))}`,
        opts(signal),
      );
    },
```

- [ ] **Step 5: Add the dev route and fix the `/designer` handler**

Modify `frontend/src/app/dev/devPackageHandlers.ts` — add a new route inside the `devPackageHandlers` array, near the other `*/cp/solutions` route:

```ts
  /**
   * Raw package by solutionId + version (`FE-D56`, design §10.3) — the
   * declare dialog's `Role` options only. Same resolution order as the
   * `/designer` route below: a dropped package first, the bundled fixture
   * second. No grouping happens here — `FE-D33` keeps derivation in the
   * browser; this is a passive read.
   */
  http.get('*/cp/solutions/:solutionId/package', ({ params, request }) => {
    const solutionId = String(params['solutionId']);
    const packageVersionRaw = new URL(request.url).searchParams.get('packageVersion');
    const loadedPackage = devPackageStore.get(solutionId) ?? packageForSolution(solutionId);
    if (loadedPackage === undefined || packageVersionRaw === null) {
      return new HttpResponse(null, { status: 404 });
    }
    return HttpResponse.json(projectable(loadedPackage));
  }),
```

Then find the existing `/dp/instances/:instanceId/designer` handler's `HttpResponse.json({...})` body (it currently ends with `configGeneration: 'gen-0',`) and add the binding:

```ts
    return HttpResponse.json({
      package: projectable(loadedPackage),
      packageVersion: loadedInstance.packageVersion,
      values: {},
      observed: observedStateFor(loadedInstance),
      configGeneration: 'gen-0',
      // `FE-D55`/`FE-D56`: absent when the instance declared no binding —
      // identical to before this field was ever read here. Previously
      // dropped unconditionally; a session-declared instance now carries
      // whatever `componentTypes` it was declared with.
      ...(loadedInstance.componentTypes === undefined
        ? {}
        : { componentTypes: loadedInstance.componentTypes }),
    });
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/app/dev/devPackageFlow.test.ts`
Expected: PASS, both new tests plus every pre-existing test in the file.

- [ ] **Step 7: Typecheck and lint**

Run: `cd frontend && npm run typecheck && npm run lint`
Expected: clean.

- [ ] **Step 8: Commit**

Ask the developer to confirm first.

```bash
git add frontend/src/shared/api/contracts/instances.ts frontend/src/features/inventory/solutions/solutionsRepository.ts frontend/src/app/dev/devPackageHandlers.ts frontend/src/app/dev/devPackageFlow.test.ts
git commit -m "feat(frontend): getPackage() + componentTypes wire field + designer fix (FE-157)"
```

---

### Task 158: `declareForm.ts` — role state, validation, and `toDeclareInput`

**Files:**
- Modify: `frontend/src/features/inventory/instances/declareForm.ts`
- Modify: `frontend/src/shared/i18n/messages.en.ts`
- Test: `frontend/src/features/inventory/instances/declareForm.test.ts`

**Interfaces:**
- Consumes: `ComponentRoleGroup`, `sharedComponentTypes` from `./componentRoles` (Task 156).
- Produces: `DeclareFormState.role: string`; `setRole(state, role): DeclareFormState`; `validateDeclareForm(...)` gains a `roleGroups` parameter and a `role` error key; `toDeclareInput(state, packageVersions, pkg?)` gains an optional fourth parameter carrying the fetched `SolutionPackage` — consumed by Task 159's dialog.

- [ ] **Step 1: Write the failing tests**

Add to `frontend/src/features/inventory/instances/declareForm.test.ts` (read the file first for its existing helpers/imports and match them; the block below assumes a `SolutionPackage` fixture helper the same shape as Task 156's `pkg()`):

```ts
import type { ComponentDeclaration, SolutionPackage } from '@contracts/view-model/types';

function twoRolePackage(): SolutionPackage {
  const components: ComponentDeclaration[] = [
    { type: 'a-source', cardinality: 'one-of-many', role: 'source', configPath: 'a.source', loggerPrefix: 'x', fields: [] },
    { type: 'a-sink', cardinality: 'one-of-many', role: 'sink', configPath: 'a.sink', loggerPrefix: 'y', fields: [] },
    { type: 'logging', cardinality: 'zero-or-more', role: 'none', configPath: 'logging', loggerPrefix: 'l', fields: [] },
  ];
  return {
    solution: 'test', packageVersion: [1], appliesTo: '>=1.0 <2.0',
    provenance: { author: 'acme', signature: 'test-fixture' },
    authoring: { reviewedRows: 3, totalRows: 3 }, components,
  };
}

describe('role validation and toDeclareInput binding', () => {
  it('requires role when the package declares two or more roles, not when it declares fewer', () => {
    const state = { ...initialDeclareForm('test', [], [1]), packageVersion: '1', role: '' };
    const withRoles = validateDeclareForm(state, [], ERRORS, [1], twoRolePackage());
    expect(withRoles.role).toBeDefined();

    const single: SolutionPackage = { ...twoRolePackage(), components: twoRolePackage().components.filter((c) => c.role !== 'sink') };
    const withoutChoice = validateDeclareForm(state, [], ERRORS, [1], single);
    expect(withoutChoice.role).toBeUndefined();
  });

  it('toDeclareInput resolves the chosen role to componentTypes = shared + chosen group, and omits it when no choice was needed', () => {
    const chosen = { ...initialDeclareForm('test', [], [1]), name: 'i1', packageVersion: '1', org: 'o', subOrg: 's', env: 'DEV' as const, role: 'sink' };
    const input = toDeclareInput(chosen, [1], twoRolePackage());
    expect(input.componentTypes).toEqual(['logging', 'a-sink']);

    const single: SolutionPackage = { ...twoRolePackage(), components: twoRolePackage().components.filter((c) => c.role !== 'sink') };
    const noChoiceNeeded = toDeclareInput({ ...chosen, role: '' }, [1], single);
    expect(noChoiceNeeded.componentTypes).toBeUndefined();
  });
});
```

(`ERRORS` is whatever message-map fixture the existing test file already builds for `validateDeclareForm` — reuse it
as-is. `role` is optional on that type with a `'Required.'` fallback, so `ERRORS` needs no new entry.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/inventory/instances/declareForm.test.ts`
Expected: FAIL — `validateDeclareForm`/`toDeclareInput` do not yet accept a fifth/fourth argument; `state.role` does not exist on `DeclareFormState`.

- [ ] **Step 3: Add `role` to the state and reset it on package-version change**

Modify `frontend/src/features/inventory/instances/declareForm.ts`:

```ts
export interface DeclareFormState {
  name: string;
  packageVersion: string;
  /**
   * `''` = unchosen — same rule as `packageVersion`. Meaningful only when
   * the fetched package declares two or more non-`none` roles (`FE-D56`);
   * otherwise ignored by `toDeclareInput`. Reset to `''` whenever
   * `packageVersion` changes, because role groups are per-version.
   */
  role: string;
  env: Env | '';
  // ... (unchanged fields below)
```

In `initialDeclareForm`'s returned object, add `role: '',` alongside `packageVersion`.

Add, near `setOrg`/`setSubOrg`:

```ts
/** `packageVersion` changed — role groups are per-version, so a stale choice must not survive it. */
export function setPackageVersion(state: DeclareFormState, packageVersion: string): DeclareFormState {
  return { ...state, packageVersion, role: '' };
}
```

- [ ] **Step 4: Extend `DeclareFormErrors` and `validateDeclareForm`**

```ts
export interface DeclareFormErrors {
  name?: string;
  org?: string;
  subOrg?: string;
  env?: string;
  packageVersion?: string;
  role?: string;
  replicas?: readonly (string | undefined)[];
}
```

Add the import at the top: `import { deriveComponentRoleGroups } from './componentRoles';` and `import type { SolutionPackage } from '@contracts/view-model/types';`.

Extend `validateDeclareForm`'s signature with a fifth, optional parameter and one new check (place it near the
`packageVersion` check — read the existing function body first and match its exact parameter names before
editing). **`role` is added to the `messages` type as OPTIONAL, with a fallback literal** — this is a pure addition
that must not force every existing caller (including this file's own pre-existing tests) to start passing a `role`
message it has no reason to know about yet:

```ts
export function validateDeclareForm(
  state: DeclareFormState,
  takenNames: readonly string[],
  messages: {
    required: string;
    nameFormat: string;
    nameTaken: string;
    replicaDuplicate: string;
    packageVersionUnknown: string;
    role?: string;
  },
  packageVersions: readonly (string | number)[],
  pkg?: SolutionPackage,
): DeclareFormErrors {
  // ...existing checks unchanged...
  const parsedVersion = packageVersions.find((v) => String(v) === state.packageVersion);
  const needsRole =
    pkg !== undefined &&
    parsedVersion !== undefined &&
    deriveComponentRoleGroups(pkg, parsedVersion).length >= 2;
  if (needsRole && state.role === '') {
    errors.role = messages.role ?? 'Required.';
  }
  return errors;
}
```

`DeclareInstanceDialog.tsx`'s call site is updated in Task 159 to pass a real, translated `role` message; Task 158's own new tests below pass no `role` message at all and rely on the `'Required.'` fallback, so no pre-existing test in this file needs to change.

- [ ] **Step 5: Extend `toDeclareInput`**

```ts
export function toDeclareInput(
  state: DeclareFormState,
  packageVersions: readonly (string | number)[] = [],
  pkg?: SolutionPackage,
): DeclareInstanceInput {
  const declaredVersion = state.declaredVersion.trim();
  const packageVersion =
    packageVersions.find((version) => String(version) === state.packageVersion) ?? state.packageVersion;
  const groups = pkg === undefined ? [] : deriveComponentRoleGroups(pkg, packageVersion);
  const chosen = groups.find((group) => group.role === state.role);
  const componentTypes =
    groups.length < 2 || chosen === undefined
      ? undefined
      : [...sharedComponentTypes(pkg as SolutionPackage, packageVersion), ...chosen.componentTypes];
  return {
    name: state.name.trim(),
    packageVersion,
    env: state.env as Env,
    org: state.org.trim(),
    subOrg: state.subOrg.trim(),
    driver: state.driver,
    locus: state.locus,
    replicas: state.replicas.map((replica) => replica.trim()),
    ...(declaredVersion === '' ? {} : { declaredVersion }),
    ...(componentTypes === undefined ? {} : { componentTypes }),
  };
}
```

Add the import: `import { deriveComponentRoleGroups, sharedComponentTypes } from './componentRoles';` (merge with Step 4's import line — one import statement, not two).

- [ ] **Step 6: Add the i18n strings**

Add to `frontend/src/shared/i18n/messages.en.ts`, near `instancesDeclarePackageVersionHelp`:

```ts
  instancesDeclareRole: 'Role',
  instancesDeclareRoleHelp:
    'This package declares more than one role. Choose which one this instance is — it decides which of the package’s fields the Designer shows.',
  instancesDeclareErrRole: 'Required.',
  instancesDeclareRoleOptionsLoading: 'Loading this solution’s roles…',
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/inventory/instances/declareForm.test.ts`
Expected: PASS, including the two new tests plus every pre-existing test in the file (a signature change to `validateDeclareForm`/`toDeclareInput` with new parameters optional/defaulted must not break an existing call that omits them).

- [ ] **Step 8: Typecheck and lint**

Run: `cd frontend && npm run typecheck && npm run lint`
Expected: clean.

- [ ] **Step 9: Commit**

Ask the developer to confirm first.

```bash
git add frontend/src/features/inventory/instances/declareForm.ts frontend/src/features/inventory/instances/declareForm.test.ts frontend/src/shared/i18n/messages.en.ts
git commit -m "feat(frontend): role validation and componentTypes resolution in declareForm (FE-158)"
```

---

### Task 159: `DeclareInstanceDialog` — the `Role` select

**Files:**
- Modify: `frontend/src/features/inventory/instances/DeclareInstanceDialog.tsx`
- Modify: `frontend/src/features/inventory/instances/InstancesPage.tsx`
- Modify: `frontend/src/shared/i18n/messages.en.ts`
- Test: `frontend/src/features/inventory/instances/DeclareInstanceDialog.test.tsx`

**Interfaces:**
- Consumes: `SolutionsRepository.getPackage` (Task 157), `deriveComponentRoleGroups` (Task 156), `setPackageVersion`/`validateDeclareForm`/`toDeclareInput` (Task 158).
- Produces: `DeclareInstanceDialogProps.solutionsRepository: Pick<SolutionsRepository, 'getPackage'>` — a new required prop; `InstancesPage` now passes its existing `solutionsRepository` prop through.

- [ ] **Step 1: Write the failing tests**

Add to `frontend/src/features/inventory/instances/DeclareInstanceDialog.test.tsx` — first extend `renderDialog`'s helper (read the existing file, this replaces its current body) to accept a stub `SolutionsRepository`:

```ts
import type { SolutionPackage, ComponentDeclaration } from '@contracts/view-model/types';
import type { SolutionsRepository } from '@features/inventory/solutions';

function twoRolePackage(): SolutionPackage {
  const components: ComponentDeclaration[] = [
    { type: 'a-source', cardinality: 'one-of-many', role: 'source', configPath: 'a.source', loggerPrefix: 'x', fields: [] },
    { type: 'a-sink', cardinality: 'one-of-many', role: 'sink', configPath: 'a.sink', loggerPrefix: 'y', fields: [] },
  ];
  return {
    solution: 'cache', packageVersion: [6, 7], appliesTo: '>=2.2 <2.5',
    provenance: { author: 'solace', signature: 'test-fixture' },
    authoring: { reviewedRows: 2, totalRows: 2 }, components,
  };
}

function stubSolutions(pkg?: SolutionPackage): Pick<SolutionsRepository, 'getPackage'> {
  return { getPackage: () => Promise.resolve(pkg ?? { ...twoRolePackage(), components: [] }) };
}

function renderDialog(props: Partial<Parameters<typeof DeclareInstanceDialog>[0]> = {}) {
  const onSubmit = vi.fn();
  const onClose = vi.fn();
  renderWithProviders(
    <DeclareInstanceDialog
      open
      solutionId="cache"
      existing={[]}
      packageVersions={VERSIONS}
      hierarchyRepository={stubHierarchy()}
      solutionsRepository={stubSolutions()}
      onClose={onClose}
      onSubmit={onSubmit}
      {...props}
    />,
  );
  return { onSubmit, onClose };
}
```

Then add new test cases:

```ts
it('shows no Role field when the package declares fewer than two roles', async () => {
  renderDialog({ solutionsRepository: stubSolutions({ ...twoRolePackage(), components: twoRolePackage().components.slice(0, 1) }) });
  await choose(/Package version/i, '6');
  await waitFor(() => expect(screen.queryByLabelText(/Role/i)).not.toBeInTheDocument());
});

it('shows a required Role field when the package declares two roles, and blocks submit until chosen', async () => {
  const { onSubmit } = renderDialog({ solutionsRepository: stubSolutions(twoRolePackage()) });
  await choose(/Package version/i, '6');
  await screen.findByLabelText(/Role/i);
  await walkCascade();
  fireEvent.click(screen.getByRole('button', { name: /Declare/i }));
  expect(await screen.findByText('Required.')).toBeInTheDocument();
  expect(onSubmit).not.toHaveBeenCalled();
});

it('resolves the chosen role to componentTypes on submit', async () => {
  const { onSubmit } = renderDialog({ solutionsRepository: stubSolutions(twoRolePackage()) });
  await choose(/Package version/i, '6');
  await screen.findByLabelText(/Role/i);
  await choose(/Role/i, 'sink');
  await walkCascade();
  fireEvent.click(screen.getByRole('button', { name: /Declare/i }));
  await waitFor(() => expect(onSubmit).toHaveBeenCalled());
  expect(onSubmit.mock.calls[0]?.[0].componentTypes).toEqual(['a-sink']);
});
```

(`walkCascade` and `choose` are the file's existing helpers — reuse them verbatim, do not redefine.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/inventory/instances/DeclareInstanceDialog.test.tsx`
Expected: FAIL — `solutionsRepository` is not a recognized prop (TypeScript), and no `Role` field exists yet.

- [ ] **Step 3: Add the prop, the fetch, and the field**

Modify `frontend/src/features/inventory/instances/DeclareInstanceDialog.tsx`. Add to the imports:

```ts
import type { SolutionPackage } from '@contracts/view-model/types';
import type { SolutionsRepository } from '@features/inventory/solutions';

import { deriveComponentRoleGroups } from './componentRoles';
import { setPackageVersion as applyPackageVersion } from './declareForm';
```

Add to `DeclareInstanceDialogProps`:

```ts
  /** Only `getPackage` is used — narrowed so a test stub need not implement `list`/`load`. */
  solutionsRepository: Pick<SolutionsRepository, 'getPackage'>;
```

Add the prop to the function signature's destructuring, then add the fetch right after the existing hierarchy queries:

```ts
  // `state.packageVersion` is a STRING — "that is what a <select> value
  // is" (the file's own existing comment on `DeclareFormState`). Every
  // consumer that needs the real `PackageVersionId` (number | string)
  // must resolve it through `packageVersions` first, the same threading
  // discipline §10.2 documents (FE-121 found six omissions of exactly
  // this). Passing the raw string into `deriveComponentRoleGroups` would
  // make `inVersion`'s `.includes()` compare "6" against the number 6 and
  // silently find nothing.
  const resolvedPackageVersion =
    packageVersions.find((version) => String(version) === state.packageVersion) ?? state.packageVersion;
  const packageQuery = useQuery({
    queryKey: ['solutions', 'package', solutionId, resolvedPackageVersion],
    queryFn: () => solutionsRepository.getPackage(solutionId, resolvedPackageVersion),
    enabled: open && state.packageVersion !== '',
  });
  const roleGroups =
    packageQuery.data === undefined
      ? []
      : deriveComponentRoleGroups(packageQuery.data, resolvedPackageVersion);
  const needsRole = roleGroups.length >= 2;
```

Update the `Package version` `Select`'s `onChange` to reset role via `applyPackageVersion` instead of the current inline object spread:

```ts
          <Select
            label={t('instancesDeclarePackageVersion')}
            required
            value={state.packageVersion}
            onChange={(packageVersion) => setState((prev) => applyPackageVersion(prev, packageVersion))}
            options={packageVersionOptions}
            {...(visible.packageVersion !== undefined
              ? { error: visible.packageVersion }
              : { helperText: t('instancesDeclarePackageVersionHelp') })}
          />
```

Update the `validateDeclareForm` call to pass the new arguments (`role` message + the fetched package):

```ts
  const errors = validateDeclareForm(
    state,
    takenNames,
    {
      required: t('instancesDeclareErrRequired'),
      nameFormat: t('instancesDeclareErrNameFormat'),
      nameTaken: t('instancesDeclareErrNameTaken'),
      replicaDuplicate: t('instancesDeclareErrReplicaDuplicate'),
      packageVersionUnknown: t('instancesDeclareErrPackageVersion'),
      role: t('instancesDeclareErrRole'),
    },
    packageVersions,
    packageQuery.data,
  );
```

Update `handleSubmit` to pass the package through and to block on a still-loading fetch when a role might be needed:

```ts
  function handleSubmit(): void {
    setShowErrors(true);
    if (state.packageVersion !== '' && packageQuery.isFetching) {
      return; // surfaced below via the Role field's own helper text
    }
    if (hasErrors(errors)) {
      return;
    }
    onSubmit(toDeclareInput(state, packageVersions, packageQuery.data));
  }
```

Add the `Role` field, directly after the `Package version` `Select` (before the Organization/Sub-org `Grid`):

```tsx
        {needsRole && (
          <Select
            label={t('instancesDeclareRole')}
            required
            value={state.role}
            onChange={(role) => setState((prev) => ({ ...prev, role }))}
            options={roleGroups.map((group) => ({
              value: group.role,
              label: group.role.charAt(0).toUpperCase() + group.role.slice(1),
            }))}
            {...(visible.role !== undefined
              ? { error: visible.role }
              : { helperText: t('instancesDeclareRoleHelp') })}
          />
        )}
```

- [ ] **Step 4: Thread the prop through `InstancesPage`**

Modify `frontend/src/features/inventory/instances/InstancesPage.tsx` — find the existing `<DeclareInstanceDialog ... packageVersions={solution?.packageVersion ?? []}` call (already reads `solutionsRepository` from its own props for `solutionsQuery`) and add:

```tsx
        <DeclareInstanceDialog
          ...
          packageVersions={solution?.packageVersion ?? []}
          solutionsRepository={solutionsRepository}
          ...
        />
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/inventory/instances/DeclareInstanceDialog.test.tsx src/features/inventory/instances/InstancesPage.test.tsx`
Expected: PASS. Every pre-existing test in both files must still pass unmodified — `solutionsRepository` is a new required prop, so confirm no other call site of `DeclareInstanceDialog` was missed (`grep -rn "<DeclareInstanceDialog" frontend/src`).

- [ ] **Step 6: Typecheck and lint**

Run: `cd frontend && npm run typecheck && npm run lint`
Expected: clean.

- [ ] **Step 7: Commit**

Ask the developer to confirm first.

```bash
git add frontend/src/features/inventory/instances/DeclareInstanceDialog.tsx frontend/src/features/inventory/instances/DeclareInstanceDialog.test.tsx frontend/src/features/inventory/instances/InstancesPage.tsx frontend/src/shared/i18n/messages.en.ts
git commit -m "feat(frontend): Role select in DeclareInstanceDialog (FE-159)"
```

---

### Task 160: Thread `componentTypes` through declared-instance storage; un-skip Task 157's third test

**Files:**
- Modify: `frontend/src/shared/testdoubles/data/declaredInstance.ts`
- Modify: `frontend/src/app/dev/devPackageFlow.test.ts` (remove the `it.todo` from Task 157 Step 6, if used)
- Test: `frontend/src/shared/testdoubles/data/declaredInstance.test.ts` (create, or extend if it already exists — check first)

**Interfaces:**
- Consumes: `DeclareInstanceInput.componentTypes` (Task 157).
- Produces: `readDeclareInput`'s return now carries `componentTypes` through when present; `buildDeclaredInstance` copies it onto the resulting `InstanceRow.componentTypes` (the field already exists on `InstanceRow` — no change needed there).

- [ ] **Step 1: Write the failing test**

Create (or extend, if the file already exists — run `ls frontend/src/shared/testdoubles/data/declaredInstance.test.ts` first) `frontend/src/shared/testdoubles/data/declaredInstance.test.ts`:

```ts
import { describe, expect, it } from 'vitest';

import { buildDeclaredInstance, readDeclareInput } from './declaredInstance';

describe('componentTypes round-trip', () => {
  const base = {
    name: 'role-bound-1',
    packageVersion: 6,
    env: 'DEV',
    org: 'Acme',
    subOrg: 'Platform',
    driver: 'docker',
    locus: 'host_local',
    replicas: ['role-bound-1'],
  };

  it('readDeclareInput carries componentTypes through when present', () => {
    const parsed = readDeclareInput({ ...base, componentTypes: ['cache-engine'] });
    expect(parsed?.componentTypes).toEqual(['cache-engine']);
  });

  it('readDeclareInput omits componentTypes when absent, never defaults it', () => {
    const parsed = readDeclareInput(base);
    expect(parsed?.componentTypes).toBeUndefined();
  });

  it('buildDeclaredInstance copies componentTypes onto the row', () => {
    const parsed = readDeclareInput({ ...base, componentTypes: ['cache-engine'] });
    expect(parsed).toBeDefined();
    const row = buildDeclaredInstance('cache', parsed!);
    expect(row.componentTypes).toEqual(['cache-engine']);
  });

  it('buildDeclaredInstance leaves componentTypes undefined when the input had none', () => {
    const parsed = readDeclareInput(base);
    expect(parsed).toBeDefined();
    const row = buildDeclaredInstance('cache', parsed!);
    expect(row.componentTypes).toBeUndefined();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/shared/testdoubles/data/declaredInstance.test.ts`
Expected: FAIL — `parsed?.componentTypes` and `row.componentTypes` are both `undefined` even when supplied, because neither function reads/copies the field yet.

- [ ] **Step 3: Implement**

Modify `frontend/src/shared/testdoubles/data/declaredInstance.ts`. In `readDeclareInput`, add after the `declaredVersion` destructure/check:

```ts
  const { name, packageVersion, env, org, subOrg, driver, locus, replicas, declaredVersion, componentTypes } =
    value;
  // ...existing checks unchanged...
  if (
    componentTypes !== undefined &&
    (!Array.isArray(componentTypes) || !componentTypes.every((t) => typeof t === 'string'))
  ) {
    return undefined;
  }

  return {
    name: name.trim(),
    packageVersion: typeof packageVersion === 'number' ? packageVersion : packageVersion.trim(),
    env: env as Env,
    org: org.trim(),
    subOrg: subOrg.trim(),
    driver: driver as DriverKey,
    locus: locus as LocusKey,
    replicas: replicas.map((replica) => replica.trim()),
    ...(nonEmptyString(declaredVersion) ? { declaredVersion: declaredVersion.trim() } : {}),
    ...(componentTypes === undefined ? {} : { componentTypes: componentTypes as readonly string[] }),
  };
```

In `buildDeclaredInstance`, add to the returned object (after `locus`, before `replicas` or anywhere convenient):

```ts
  return {
    id: input.name,
    name: input.name,
    solutionId,
    packageVersion: input.packageVersion,
    env: input.env,
    org: input.org,
    subOrg: input.subOrg,
    driver: input.driver,
    locus: input.locus,
    ...(input.componentTypes === undefined ? {} : { componentTypes: input.componentTypes }),
    replicas: input.replicas.map((name) => ({ name, health: UNOBSERVED_REPLICA_HEALTH })),
    ...unobservedRuntimeFacts(input.declaredVersion),
  };
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/shared/testdoubles/data/declaredInstance.test.ts`
Expected: PASS, 4 tests.

- [ ] **Step 5: Add the end-to-end test Task 157 deferred here**

Append to `frontend/src/app/dev/devPackageFlow.test.ts`, alongside the two tests Task 157 added:

```ts
it('a session-declared instance carries its componentTypes binding into the Designer load', async () => {
  const outcome = devDeclaredInstances.declare(
    'cache',
    {
      name: 'role-bound-1',
      packageVersion: 6,
      env: 'DEV',
      org: 'Acme',
      subOrg: 'Platform',
      driver: 'docker',
      locus: 'host_local',
      replicas: ['role-bound-1'],
      componentTypes: ['cache-engine'],
    },
    CACHE_INSTANCES.map((i) => i.name),
  );
  expect(outcome.ok).toBe(true);
  if (!outcome.ok) return;
  const load = await configs.loadDesigner(outcome.instance.id);
  expect(load.componentTypes).toEqual(['cache-engine']);
});

it('a session-declared instance with no componentTypes leaves it undefined on the Designer load', async () => {
  const outcome = devDeclaredInstances.declare(
    'cache',
    {
      name: 'no-binding-1',
      packageVersion: 6,
      env: 'DEV',
      org: 'Acme',
      subOrg: 'Platform',
      driver: 'docker',
      locus: 'host_local',
      replicas: ['no-binding-1'],
    },
    CACHE_INSTANCES.map((i) => i.name),
  );
  expect(outcome.ok).toBe(true);
  if (!outcome.ok) return;
  const load = await configs.loadDesigner(outcome.instance.id);
  expect(load.componentTypes).toBeUndefined();
});
```

Run: `cd frontend && npx vitest run src/app/dev/devPackageFlow.test.ts`
Expected: PASS, all tests including these two.

- [ ] **Step 6: Full suite, typecheck, lint**

Run: `cd frontend && npm run test && npm run typecheck && npm run lint`
Expected: all clean — this is the last task, so this is the plan's overall gate.

- [ ] **Step 7: `contracts/run-checks.sh` (unaffected, confirm it)**

Run (from repo root): `./contracts/run-checks.sh`
Expected: unchanged result from before this plan — no file under `contracts/` was touched.

- [ ] **Step 8: Commit**

Ask the developer to confirm first.

```bash
git add frontend/src/shared/testdoubles/data/declaredInstance.ts frontend/src/shared/testdoubles/data/declaredInstance.test.ts frontend/src/app/dev/devPackageFlow.test.ts
git commit -m "feat(frontend): thread componentTypes through declared-instance storage (FE-160)"
```

---

## Review section

All five tasks (`FE-156`–`FE-160`) complete. Per-task detail in
[`reports/task-156-report.md`](reports/task-156-report.md) through
[`reports/task-160-report.md`](reports/task-160-report.md); summary below.

| Task | Status | Notable deviation |
|---|---|---|
| `FE-156` — role-grouping derivation | Done, 5/5 tests | None |
| `FE-157` — `getPackage()` + wire field + designer fix | Done, 37/37 | Making `getPackage` a required interface method required fixing 6 pre-existing mocks outside the plan's file list (mechanical, no behaviour change) |
| `FE-158` — `declareForm.ts` role validation | Done, 36/36 | None functional |
| `FE-159` — `Role` select in the dialog | Done, 54/54 | Role option labels kept as the plain role string, not title-cased — the plan's own snippet contradicted design §10.3 and its own test; also fixed one direct call site (`DeclareInstanceDialog.a11y.test.tsx`) the plan's file list omitted |
| `FE-160` — thread `componentTypes` through storage | Done, 4/4 + 39/39 | One lint-driven cast removal |

**Final verification.** `npm run typecheck` and `npm run lint` clean throughout. `./contracts/run-checks.sh`
PASSED (unaffected — no `contracts/` file changed). Full suite: 2448/2474 tests passing — every test this plan
touched is green. The 26 remaining failures (`componentSelection.fileMesh.test.ts`) are a **pre-existing, unrelated
blocker**: an uncommitted change to `frontend/contracts/packages/file-mesh/package.json`, present before this
session started, that this plan does not touch. Per the developer's explicit decision (2026-09-22), left as a
known follow-up rather than folded into this plan — see [`task-160-report.md` §6](reports/task-160-report.md).
