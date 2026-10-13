# FE-160 · Thread `componentTypes` through declared-instance storage — Report

Fifth and final task of [plan 130](../130-declare-instance-role-binding.md).

---

## 1 · What was needed

`readDeclareInput` and `buildDeclaredInstance` are the fixture-layer boundary between the wire-shaped
`DeclareInstanceInput` (now carrying `componentTypes`, since Task 157) and the stored `InstanceRow` the rest of the
app reads. Neither read nor copied the new field, so a role picked at declare time never actually reached a
session-declared instance's row — and, transitively, never reached the Designer load Task 157's fix wired up.

## 2 · What changed

| Change | File |
|---|---|
| `readDeclareInput` validates and carries `componentTypes` through when present, omits it when absent | `shared/testdoubles/data/declaredInstance.ts` |
| `buildDeclaredInstance` copies `input.componentTypes` onto the row when present | same |
| 4 tests | `declaredInstance.test.ts` (new) |
| The end-to-end test deferred from Task 157 (declare → `loadDesigner()` carries the binding, and the no-binding case) | `app/dev/devPackageFlow.test.ts` |

## 3 · What deliberately did not change

- **`InstanceRow.componentTypes`.** Already existed (added for the File Mesh fixture rows) — no schema change
  needed, just population from a session declaration.
- **Every other declared instance / existing test.** Absent `componentTypes` stays `undefined`, never defaulted —
  pinned by the "leaves it undefined" tests at both layers.

## 4 · Evidence

| Check | Result |
|---|---|
| `declaredInstance.test.ts` before implementation | FAIL — 2 of 4 (both "carries through" cases; the "omits/leaves undefined" cases passed vacuously) |
| after | 4 / 4 |
| `devPackageFlow.test.ts` (all, including Task 157's deferred pair) | 39 / 39 |
| `typecheck` / `lint` | clean / clean at `--max-warnings=0` |
| `./contracts/run-checks.sh` | PASSED (3 sibling-repo checks skipped, as before this plan — unaffected, no `contracts/` file touched) |

## 5 · Deviations from the plan

One lint fix: the plan's Step 3 snippet cast `componentTypes as readonly string[]` when building the return value,
but the preceding guard (`Array.isArray(componentTypes) && componentTypes.every(t => typeof t === 'string')`)
already narrows the type, making the assertion a `@typescript-eslint/no-unnecessary-type-assertion` error. Removed
the cast; behavior unchanged.

## 6 · Final full-suite gate — known, pre-existing blocker (not fixed here)

`npm run test` at repo `HEAD` for this plan: **342 files / 344, 2448 tests / 2474 passing** — every test this plan
added or touched is green, and the total pass count is up from the 2437/2463 baseline recorded at Task 157 (this
plan added 15 new tests net: 5 + 2 + 2 + 3 + 2 + 1 extra `devPackageFlow` case; some were additive to existing
`describe` blocks).

The 26 remaining failures (2 files, `componentSelection.fileMesh.test.ts`) are **not caused by this plan**. They
were present in the working tree before this session began, in an uncommitted, unrelated change to
`frontend/contracts/packages/file-mesh/package.json` that rewrote File Mesh's component types (e.g. `COMP_SOURCE` →
`file-source`) without updating that test file's expectations. Confirmed by temporarily stashing just that one
file: the 26 failures disappear and the suite is fully green. First flagged in the [Task 157
report](task-157-report.md) §6.

**Decision, per the developer (2026-09-22):** leave `file-mesh/package.json` untouched and record this as a known,
out-of-scope blocker rather than fixing, reverting, or absorbing that other in-progress work into this plan. Plan
130 itself (Tasks 156–160, `FE-D56`) is complete and its own scoped verification is fully green; resolving the
File Mesh fixture/test mismatch is separate follow-up work for whoever owns that in-progress change.
