# FE-156 · Pure role-grouping derivation — Report

First task of [plan 130](../130-declare-instance-role-binding.md) (design:
[`design.md` §10.3](../../../design/frontend/design.md#103-role-selection-at-declare-time--authoring-half-of-fe-d55-fe-d56),
[`FE-D56`](../../../design/frontend/decisions.md#fe-d56--role-select-at-declare-time-resolves-to-a-componenttypes-list-before-it-reaches-the-wire)).

---

## 1 · What was needed

`FE-D55` defines how the Designer *consumes* a `componentTypes` binding but left open how one is *authored* — the
declare dialog has no way to turn a package's `role`-tagged components into a binding. This task supplies the pure
derivation the rest of the plan builds on: grouping a package's version-eligible components by `role`, and
separating out the always-included "shared" components (`role: undefined` or `'none'`).

## 2 · What changed

| Change | File |
|---|---|
| `ComponentRoleGroup`, `deriveComponentRoleGroups(pkg, packageVersion)`, `sharedComponentTypes(pkg, packageVersion)` | `features/inventory/instances/componentRoles.ts` (new) |
| 5 tests | `componentRoles.test.ts` (new) |

Both functions filter to `inVersion(component.version, packageVersion)` first (reusing `@contracts/view-model/project`'s
`inVersion`, the same helper `componentSelection.ts` already uses — no restated filtering rule, `FE-D37`'s
discipline extended to a second consumer), then partition on `role`. Nothing in the module names a solution, a
role string or a component type (`REQ-001`) — it is generic over whatever distinct role strings a package
declares.

## 3 · What deliberately did not change

- **`componentSelection.ts` / `deriveComponentSelection`.** Unchanged and untouched — it still takes an explicit
  `componentTypes` list and never sees `role`. This module only produces the list; `FE-D55`'s consumer is
  downstream and out of scope here.
- **The wire.** `role` never reaches `DeclareInstanceInput` — this task is pure, in-memory grouping only.

## 4 · Evidence

| Check | Result |
|---|---|
| `componentRoles.test.ts` before implementation | FAIL — `Cannot find module './componentRoles'` |
| after | 5 / 5 pass |
| `typecheck` / `lint` | clean / clean at `--max-warnings=0` |

## 5 · Deviations from the plan

None. Implemented exactly as specified in the plan's Step 3 code block.
