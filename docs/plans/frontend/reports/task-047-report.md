# FE-047 · Control registry — Report

## What I implemented

The `controlKind -> component` lookup (`UI-D14`, `UI-D22`, `UI-D23`, `FE-D23`) that lets a later `FieldRow`
(FE-048, not yet built) render any field of any solution's Designer form with no per-solution React code
(task spec: `.superpowers/sdd/implementation/task-047-brief.md`).

| File | Summary |
|---|---|
| `frontend/src/features/designer/controls/registry.ts` | The registry itself. `type ControlRegistry = { [K in ControlKind]: ComponentType<never> }`, with `controlRegistry` assigned as a `const` object literal against that type — an omitted or misspelled `ControlKind` member fails `tsc`, not just lint. |
| `frontend/src/features/designer/controls/{Duration,List,Map,Table,Yaml}Control/` | The five kinds with no earlier equivalent — each its own folder (`Component.tsx` + `.types.ts` + `index.ts` + `.test.tsx` + `.a11y.test.tsx`), matching the `shared/ui/*` convention. |
| `frontend/src/features/designer/controls/registry.test.ts` | Runtime sanity: all ten `ControlKind` members resolve to a defined component, no extras. |
| `frontend/src/features/designer/controls/registry.typecheck.test.ts` | Compile-only assertions (task 011's `Button.typecheck.test.tsx` pattern): `@ts-expect-error` on an object missing a member, and on one with an unknown key — both must fail to compile for the test file itself to pass `tsc`. |
| `frontend/src/shared/i18n/messages.en.ts` | 13 new keys under a "Control registry (task 047)" block — row/cell labels, add/remove button text, the duration canonical-example string, the yaml-disabled message, the table no-columns placeholder. |

## Rulings

1. **Five of the ten registry entries are the existing `shared/ui` components, reused directly — not
   wrapped a second time.** `text -> TextField`, `number -> NumberField`, `toggle -> Switch`,
   `select -> Select` (all task 012) and `secret -> SecretField` (task 023, the brief's own instruction)
   already have exactly the props shape and behaviour a registry entry needs; a thin pass-through wrapper
   folder for each would have been pure duplication with no behavioural difference, working against
   "Simplicity First" / "Minimal Impact". Only `duration`/`list`/`map`/`table`/`yaml` — which have no
   earlier component — get new folders under `controls/`.
2. **`ComponentType<never>` (not `<any>`, forbidden everywhere by `G2`) is the registry's value type**, per
   the brief's own resolved ambiguity #2 — `never` is a valid parameter type for every component's props by
   contravariance, so assigning the concrete literal to the mapped type is what makes exhaustiveness a
   compile-time property.
3. **`duration`/`list`/`map`/`table`/`yaml` each got their own minimal prop interface**, not a shared
   `ViewField`-shaped prop, per the brief's resolved ambiguity #3 — adapting a `ViewField` into whatever a
   specific control needs is FE-048's job. Each new component is independently mountable with hand-built
   props in its own tests.
4. **`table`'s columns/cells reuse the same per-scalar-type controls the registry's other entries use**
   (`TextField`/`NumberField`/`Switch`/`Select`) rather than a second control set — `FieldDeclaration.type`
   drives the choice per column (`string`→text, `int`→number, `bool`→toggle, `enum`→select; anything else
   falls back to a safe text-cell rendering, never `String()` on an object/array — caught by
   `@typescript-eslint/no-base-to-string` during lint and fixed by narrowing to `string | number | boolean`
   before coercing). `columns` empty/undefined renders the "declared by the Solution Package" placeholder
   (`controlTableNoColumns`), matching FE-046's own fixture gap (no `table`-typed field example yet).
   Row add/remove is included for parity with `list`/`map`, seeding a blank row from the declared columns
   (`bool` columns default `false`, everything else `null`).
5. **`yaml` renders through the existing `Alert` component** (severity `neutral`, `title={label}`, body =
   the disabled-message string, optionally appended with a package `helperText`) — verified by a dedicated
   test that queries the rendered container for `textarea`, `input`, and `[contenteditable]` and asserts
   zero matches, plus `screen.queryByRole('textbox')` is `null`. `Alert` itself renders no form control, so
   this guarantee doesn't depend on how the message text is composed.
6. **No `.gallery.tsx` files and no `Gallery.tsx` registration for the five new components.** `Gallery.tsx`'s
   own header comment scopes it to "each Phase 1 task (011-025)" registering its `shared/ui` primitive —
   these are `features/designer` components from a later task, not `shared/ui`, and the brief's file layout
   doesn't ask for gallery entries.
7. **`FieldProps` (label/required/helperText/error/disabled) was not imported from
   `shared/ui/internal/FieldProps`.** It isn't re-exported through `shared/ui`'s public barrel, and the one
   precedent for reaching into `shared/ui/internal/*` from outside (`ToneIcon`) was explicitly barrelled
   first rather than imported directly — so the five new components declare their own local prop shape
   instead of importing an internal-only type across that boundary.

## Verification discrepancy

The brief's Testing section names `npm run test:a11y -- src/features/designer/controls`; that script does
not exist in `frontend/package.json` (only `test`, `lint`, `typecheck`, `build`, `e2e`, `format` do). Every
`*.a11y.test.tsx` file matches `vitest.config.ts`'s `unit` project include glob (`src/**/*.test.{ts,tsx}`)
the same as any other test, so `npm test -- src/features/designer/controls` already runs all six a11y
suites (Duration/List/Map/Table/Yaml, each in light and dark mode) alongside the functional tests — 12 test
files, 47 tests, all passing. Flagging this as a repo-config gap rather than silently working around it;
no `package.json` change made since the brief didn't ask for one and it's outside this task's Files list.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run lint                                          # clean
npm run typecheck                                     # clean (includes the two @ts-expect-error assertions
                                                        # in registry.typecheck.test.ts actually firing)
npm test -- src/features/designer/controls             # 12 files, 47 tests, all passed
npm test                                               # full suite: 157 files, 739 tests, all passed
npm run build                                          # production build succeeds
```

`npm run lint` caught one real defect during development: `TableControl`'s text-cell fallback originally
did `String(raw)` unconditionally, which `@typescript-eslint/no-base-to-string` flagged because `raw` is
typed as `FieldValue` (includes arrays/objects) — fixed by narrowing to `string | number | boolean` before
coercing, falling back to `''` otherwise (Ruling 4).

## Status

DONE — implemented, tested (739/739 on a full clean run), lint-clean, typecheck-clean (exhaustiveness proven
at compile time, not just asserted), production build succeeds. One documented discrepancy (missing
`test:a11y` script) noted above, not blocking. Not yet closed on GitHub — pending review.
