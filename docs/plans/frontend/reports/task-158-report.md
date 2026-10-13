# FE-158 · `declareForm.ts` — role state, validation, and `toDeclareInput` — Report

Third task of [plan 130](../130-declare-instance-role-binding.md).

---

## 1 · What was needed

The pure form logic needs a `role` field with the same "unchosen is `''`, never silently defaulted" discipline
`packageVersion` already follows, a validation rule that activates only when the fetched package actually
declares two or more roles, and `toDeclareInput` needs to fold the chosen role into `componentTypes` before the
value reaches the wire.

## 2 · What changed

| Change | File |
|---|---|
| `DeclareFormState.role: string`; `setPackageVersion()` resets it on version change | `features/inventory/instances/declareForm.ts` |
| `DeclareFormErrors.role?`; `DeclareFormMessages.role?` (optional, `'Required.'` fallback); `validateDeclareForm(..., packageVersions, pkg?)` gains the check | same |
| `toDeclareInput(state, packageVersions, pkg?)` resolves `componentTypes = [...sharedComponentTypes(...), ...chosenGroup.componentTypes]` | same |
| 2 new tests | `declareForm.test.ts` |
| `instancesDeclareRole`, `instancesDeclareRoleHelp`, `instancesDeclareErrRole`, `instancesDeclareRoleOptionsLoading` | `shared/i18n/messages.en.ts` |

## 3 · What deliberately did not change

- **Every existing caller of `validateDeclareForm`/`toDeclareInput`.** Both new parameters (`pkg?`, and `role` on
  `DeclareFormMessages`) are optional/defaulted, so no pre-existing test in this file needed to change — confirmed
  by running the full pre-existing suite alongside the 2 new tests.
- **`role`'s own submit gating.** Still `''` = unchosen, same as `packageVersion` — no silent default to the first
  group.

## 4 · Evidence

| Check | Result |
|---|---|
| `declareForm.test.ts`'s 2 new tests before implementation | FAIL — extra state/args ignored at runtime (Vitest does not type-check), asserting wrong values |
| after | 36 / 36 (whole file) |
| `typecheck` / `lint` | clean / clean at `--max-warnings=0` |

## 5 · Deviations from the plan

None functionally. Used the file's existing `DeclareFormMessages` named interface (adding an optional `role?`
field to it) rather than inlining an anonymous object type for `validateDeclareForm`'s `messages` parameter, since
the file already names that type and every other caller already imports it.
