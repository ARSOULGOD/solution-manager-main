# FE-157 · Wire contract — `getPackage()`, `componentTypes`, and the two dev-handler fixes — Report

Second task of [plan 130](../130-declare-instance-role-binding.md).

---

## 1 · What was needed

The declare dialog needs a way to fetch a solution's raw package before an instance exists (to derive `Role`
options), `DeclareInstanceInput` needs a place to carry the resolved binding, and the `/designer` dev handler had a
pre-existing gap: it never read a session-declared instance's `componentTypes` at all, so any instance declared
through the dialog rendered every component regardless of role.

## 2 · What changed

| Change | File |
|---|---|
| `DeclareInstanceInput.componentTypes?: readonly string[]` | `shared/api/contracts/instances.ts` |
| `SolutionsRepository.getPackage(solutionId, packageVersion, signal?)` | `features/inventory/solutions/solutionsRepository.ts` |
| New `GET */cp/solutions/:solutionId/package` dev route; `/designer` handler now includes `componentTypes` when the instance has one | `app/dev/devPackageHandlers.ts` |
| 2 new tests + the deferred third (Task 160's own) | `app/dev/devPackageFlow.test.ts` |

`getPackage()` resolves through the same `devPackageStore.get(solutionId) ?? packageForSolution(solutionId)`
lookup the `/designer` route already uses, and does no grouping server-side (`FE-D33`: the browser projects).

## 3 · What deliberately did not change

- **The Designer's load path.** `getPackage()` is per-solution, for before an instance exists — not
  `ConfigRepository.loadDesigner`, which is per-instance.
- **The wire format for `load()`.** Untouched; only a new field and a new read route were added.

## 4 · Evidence

| Check | Result |
|---|---|
| `devPackageFlow.test.ts`'s 2 new tests before implementation | FAIL — `solutions.getPackage is not a function` |
| after | 37 / 37 (whole file) |
| `typecheck` / `lint` | clean / clean at `--max-warnings=0` |

## 5 · Deviations from the plan

**Making `getPackage` a required `SolutionsRepository` method (as the plan specifies, no `?`) broke typecheck on
six files the plan's own File Structure table did not list**, because `SolutionsRepository` is used as a full,
non-narrowed prop type in several other components (`SolutionsPage`, `LoadPackageDialog`) whose tests build a
literal mock repository. Fixed by adding a `getPackage: () => Promise.reject(new Error('not used in this test'))`
stub (no behavior change) to each: `InstancesPage.test.tsx`, `LoadPackageDialog.test.tsx`,
`LoadPackageDialog.a11y.test.tsx`, `SolutionsPage.test.tsx`, `SolutionsPage.a11y.test.tsx`, plus one more inline
mock inside `devPackageFlow.test.ts` itself. Also added a pass-through `getPackage` to the `withDevPackages`
decorator in `app/dev/devSolutionsRepository.ts`, which wraps the real repository and would otherwise silently
drop the new method. None of this changes any test's assertions — it is the mechanical fallout of the interface
gaining a required member.

## 6 · Pre-existing, unrelated issue found (not fixed here)

A full-suite run (beyond this task's own scoped verification) surfaced 26 failing tests across 2 files
(`componentSelection.fileMesh.test.ts` and its instance-shape assumptions), caused by an **uncommitted,
pre-existing change to `frontend/contracts/packages/file-mesh/package.json`** already present in the working tree
before this session started — it rewrote File Mesh's component types (e.g. `COMP_SOURCE` → `file-source`) without
updating the fixture test's expectations. Confirmed by temporarily stashing just that file: the 26 failures
disappear. Left untouched (unrelated to this plan, not discarded); flagged for resolution before Task 160's final
full-suite gate.
