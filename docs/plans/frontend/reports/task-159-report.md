# FE-159 · `DeclareInstanceDialog` — the `Role` select — Report

Fourth task of [plan 130](../130-declare-instance-role-binding.md).

---

## 1 · What was needed

The operator-facing half: fetch the package once `Package version` is chosen, derive role groups client-side, and
— only when two or more exist — show a required `Role` select directly under `Package version`, gated the same
way the file's other required fields are (a Declare-press validation message, never a disabled control with no
stated reason).

## 2 · What changed

| Change | File |
|---|---|
| `solutionsRepository: Pick<SolutionsRepository, 'getPackage'>` prop; `useQuery` fetch keyed on `[solutions, package, solutionId, resolvedPackageVersion]`; `Role` `Select`, rendered only when `deriveComponentRoleGroups(...).length >= 2`; `handleSubmit` blocks on a still-fetching package | `features/inventory/instances/DeclareInstanceDialog.tsx` |
| Threads `solutionsRepository` through to the dialog | `features/inventory/instances/InstancesPage.tsx` |
| 3 new tests | `DeclareInstanceDialog.test.tsx` |
| Stub added to the other direct call site the plan's file list didn't mention | `DeclareInstanceDialog.a11y.test.tsx` |

## 3 · What deliberately did not change

- **`role` on the wire.** Never sent — only the resolved `componentTypes` (computed by `toDeclareInput`, Task 158)
  crosses the boundary.
- **Fewer-than-two-role behaviour.** No `Role` field renders, `componentTypes` stays `undefined`, byte-identical
  to before this task — pinned by the "shows no Role field" test.

## 4 · Evidence

| Check | Result |
|---|---|
| `DeclareInstanceDialog.test.tsx`'s 3 new tests before implementation | FAIL — `solutionsRepository` not a recognized prop (TS); no `Role` field rendered |
| after (`DeclareInstanceDialog.test.tsx` + `InstancesPage.test.tsx` + `DeclareInstanceDialog.a11y.test.tsx`) | 54 / 54 |
| `typecheck` / `lint` | clean / clean at `--max-warnings=0` |

## 5 · Deviations from the plan

**Role option labels are the plain role string, not title-cased.** The plan's own Step 3 code snippet capitalized
the label (`group.role.charAt(0).toUpperCase() + group.role.slice(1)` → `'Sink'`), but this contradicts both
design §10.3 ("options are the distinct role strings") and the plan's own Step 1 test, which asserts
`choose(/Role/i, 'sink')` (lowercase, exact match — `findByRole`'s string matching is case-sensitive). Implemented
`label: group.role` verbatim instead, which satisfies both the spec text and the test as written; the snippet's
capitalization looks like a copy-paste slip in the plan.

**`DeclareInstanceDialog.a11y.test.tsx`** renders `DeclareInstanceDialog` directly and was not in the plan's File
Structure table for this task, but making `solutionsRepository` a required prop broke it the same way it would
have broken any other direct call site. Added the same not-used-in-this-test stub there; confirmed via
`grep -rn "<DeclareInstanceDialog" frontend/src` (the plan's own instruction) that no other call site exists.
