# FE-058 · Package-declared actions — Report

## What I implemented

Reference: `designer.jsx`'s own "Actions" card
(`docs/design/frontend/reference/app/designer.jsx:260-270`) and
`designer_data.jsx`'s `TEST_BROKER_CONNECTION` fixture (Dec `UI-D22` `I6` `FE-D23`, Design §10, UI
§A.8 package actions).

| File | Summary |
|---|---|
| `frontend/contracts/view-model/types.ts` | `ViewAction` gains `inputs: readonly string[]` — a genuine gap in task 046's own output, flagged by its `project.ts` comment as "unexercised by any of task 046's 9 named obligations." The package already declares `ActionDeclaration.inputs` (design.md §3.5); the projected `ViewAction` just never carried it through. |
| `frontend/contracts/view-model/project.ts` | `projectAction` passes `action.inputs ?? []` through. No other obligation touched. |
| `frontend/contracts/fixtures/view-model/multi-component-selection.json` | The one existing fixture that exercises `actions` (`TEST_CONNECTION`) — its `expected.actions` entries updated with the `inputs` the package declaration already specified, so the shared Java/TS fixture suite stays the single source of truth rather than drifting out of sync with the type change. |
| `frontend/src/shared/i18n/messages.en.ts` | 7 new `packageActions*` keys: section title, the inputs-description template, one label per closed `ActionKind` member, a pending label, and a generic error detail. |
| `frontend/src/features/designer/PackageActions/` (new: `.tsx`, `.types.ts`, `.test.tsx`, `.a11y.test.tsx`, `index.ts`) | New component. Takes `sections: readonly ViewSection[]` (same shape `ValidationSummary`/`SectionNav` already take) and flattens `section.actions` across all of them — `project()` buckets every action into one section today, but nothing pins that, so the component doesn't assume it. Each action renders as its own row inside one `Paper` card, each row owning its own `useMutation` against `ActionsRepository.run(instanceId, action.key)`. |
| `frontend/src/features/designer/actionsRepository.typecheck.test.ts` | New. Compile-only proof for the acceptance line "a test asserting no action request body can contain a resolved secret (type-level)": `@ts-expect-error` on a call passing a third argument, since `run`'s signature has no body parameter at all. |

`ActionsRepository`/`actionsRepository.test.ts` and the `*/dp/instances/:instanceId/actions/:actionKey`
MSW handler already existed (task 033 scaffolding) — this task did not touch either.

## Design decisions, and why

1. **`ViewAction.inputs` added to the pinned contract, not invented in the frontend layer.** The
   package schema (design.md §3.5) already declares `action.inputs` — `project()` simply dropped it
   on the floor. Carrying it through is a bug fix inside task 046's own stated scope, not new contract
   surface: no fixture asserted its absence deliberately (the one fixture that exercises actions,
   `multi-component-selection.json`, needed updating precisely because it never exercised this field).
2. **Display label keyed off `action.kind`, not `action.key` or a package-authored field.** The
   package schema has no action label at all (§3.5's own example: `key`, `kind`, `inputs`, `when` —
   nothing else). `action.kind` is closed at exactly three members (`UI-D22`), so a
   `Record<ActionKind, MessageKey>` in the component is the same "closed vocabulary → static lookup"
   shape `shared/vocab/health.ts` already uses for `HealthKey`, just without borrowing that module's
   `tone`/`icon` shape (an action button isn't a status chip, so `VocabEntry` doesn't fit).
3. **`sections: readonly ViewSection[]` prop, not a pre-flattened `actions: readonly ViewAction[]`.**
   First draft took the flat list directly; caught before commit that this breaks the codebase's own
   convention — `ValidationSummary` and `SectionNav` both take `ViewModel.sections` verbatim and derive
   what they need internally, so a future Designer page never has to know how `project()` buckets
   actions across sections. `PackageActions` now matches that shape and flattens with one `flatMap`,
   covered by a test asserting actions from two different sections both render (not just the first's).
4. **Each row owns its own `useMutation`, not one shared mutation keyed by action.** A shared mutation
   would show a stale pending/verdict state on every row while any one action runs. Splitting into a
   private `ActionRow` subcomponent — one `useMutation` call per row — is the same "own the hook,
   don't share it across a list" choice `SectionCard` already makes for its own per-field state.
5. **The response's `message` renders verbatim, never through `t()`.** It's server-composed data
   (the fixture backend already returns `"${actionKey} succeeded."`), exactly the same category as
   `OverrideReasonDialog` rendering a validation's own `message` directly (task 057) — `t()` is for
   client-authored copy only. A transport failure (the promise itself rejecting) falls back to the one
   catalogued `packageActionsErrorDetail`, since the failure-body shape is unspecified (`Ready: FIXTURE`
   — action invocation route is `API CONTRACT — TBD`).
6. **`disabled` (top-level, editing-blocked) and `action.enabled` (that action's own `when` gate) are
   independent and both checked** — `rowDisabled = disabled || !action.enabled || run.isPending`.
   `ViewAction` carries no separate `visible` the way `ViewField` does, so an inapplicable action
   renders disabled rather than hidden; nothing in `UI-D22`/design.md §3.5 specifies hiding it.

## Testing

Every command run from `frontend/`, output inspected:

```
npx vitest run contracts                                              # RED (1 fixture mismatch) then GREEN after fixture fix — 19/19
npx vitest run src/features/designer/PackageActions \
  src/features/designer/actionsRepository.typecheck.test.ts           # RED (component missing) then GREEN, TDD — 12/12
npm run typecheck                                                     # clean
npm run lint                                                          # clean (project-wide)
npx prettier --check <every file touched by this task>                # clean
npx vitest run contracts src/features/designer                        # 41 files, 193 tests — all passed
npm test                                                               # full suite: 182 files; 2 files/3 tests failed
npm run build                                                          # production build succeeds
```

The full-suite failures are confined to `src/features/inventory/instances/InstancesPage.test.tsx` and
`InstancesPage.a11y.test.tsx` — a pre-existing parallel-worker timeout flake this task's own change
never touches (same category already documented in tasks 044/046/048/050/051/052/053/055/057's own
reports, there against `DataTable`). Re-ran both files in isolation: 25/25 passed.

One incidental finding, reverted rather than fixed: running bare `prettier --write` on
`contracts/view-model/{types,project}.ts` reformatted ~20 pre-existing lines unrelated to this task
(collapsing hand-formatted multi-line unions to Prettier's own single-line style) — confirmed via
`git show HEAD:... | prettier --check` that this violation predates this task. Reverted both files to
`HEAD` and reapplied only the `inputs` edit by hand, matching the surrounding (non-canonical) style, to
keep this task's diff scoped to what it actually changed. `npm run format` will still flag those two
files on `dev/frontend` — pre-existing, not introduced here.

Task-specific acceptance items:

- **Server-side only** — `actionsRepository.ts` (unchanged, task 033) sends only `instanceId` +
  `actionKey`; `PackageActions.test.tsx`'s "runs the action by instanceId and key only" asserts the
  exact call args.
- **Verdict only** — `actionsRepository.typecheck.test.ts` proves at the type level that `run()` has no
  third parameter, so no field value (resolved secret or otherwise) can reach the request body; the
  existing `actionsRepository.test.ts` (task 033) already asserts the response never contains a
  password/secret substring.
- **Inputs named on screen** — `PackageActions.test.tsx`'s "names the action label and its inputs"
  asserts the rendered description lists every input key.
- **Component test of verdict rendering** — success (`ok: true`), negative verdict (`ok: false`), and
  transport-failure paths each have their own test, plus the a11y suite covers both an enabled and a
  `when`-disabled action in both colour modes.

## Status

DONE — implemented, tested (193/193 on the contracts+designer suite, full suite green modulo the
documented pre-existing `InstancesPage` flake), lint-clean, typecheck-clean, prettier-clean on every
file this task touched, production build succeeds. No Designer page exists yet to assemble
`PackageActions` alongside `SaveBar`/`ConflictDialog`/`OverrideReasonDialog` — the same gap tasks
055–057's own reports left open. Not yet marked done in `task-list.md` or closed on GitHub (issue
[#73](https://github.com/parikshit-coe/solution-manager/issues/73)) — pending commit approval.
