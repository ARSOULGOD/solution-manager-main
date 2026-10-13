# FE-059 · Version states, blocked banner, accept/restore — Report

## What I implemented

Reference: `designer.jsx`'s own `VersionPill`/`BlockedBanner`/`AcceptRuntimeModal`
(`docs/design/frontend/reference/app/designer.jsx:139-168,215,238,260,302,314-319`) and
`architecture.md` §7.2's correction paths (Dec `UI-D12` `SM-D14` superseded-on-this-point `FE-D22`,
Design §10/§15, UI §A.8).

| File | Summary |
|---|---|
| `frontend/contracts/view-model/types.ts` | `ObservedState`/`ViewStatus` gain optional `declaredVersion`/`observedVersion`. The task's own acceptance line ("`VersionPill`... each with `decl`/`obs`") has no source anywhere in the pinned contract — `ObservedState` carried only the already-*resolved* `versionState`, never the two raw strings that produced it. Both optional, so `run.test.ts`'s `toEqual` tolerates every other fixture leaving them unset. |
| `frontend/contracts/view-model/project.ts` | Passes both through into `status`, conditionally (matching `projectField`'s existing `helperText` pattern — the key is omitted, not set to `undefined`, when absent). |
| `frontend/contracts/fixtures/view-model/version-state-{drift,unknown,unverified}.json` | The three fixtures that already exercise each version state — extended with realistic `declaredVersion`/`observedVersion` values matching `dynamic-ui/design.md` §6.1's own table examples (drift: declared 2.4.0/observed 2.3.0; unknown: declared 9.9.0, nothing observed; unverified: declared 2.4.0, nothing observed). |
| `frontend/src/features/designer/overlayRbac.ts` (+ test) | New. `overlayRbac(viewModel, permissions): ViewModel` — the `FE-D22` seam, applied strictly after `project()`. |
| `frontend/src/features/designer/VersionPill/` (new: `.tsx`, `.types.ts`, `.test.tsx`, `.a11y.test.tsx`, `index.ts`) | New. State chip (reusing `versionVocab`, task 013) + a mono "decl X · obs Y" caption. |
| `frontend/src/features/designer/BlockedBanner/` (new, same file set) | New. Renders only for `drift`/`unknown`; returns `null` for `ok`/`unverified`. |
| `frontend/src/features/designer/AcceptRuntimeDialog/` (new, same file set) | New. Renders the real `DiffView` (task 022) over caller-supplied rows, classified 4 ways; blocks confirmation on any unmapped `rename` row. |
| `frontend/src/features/designer/RestoreRuntimeDialog/` (new, same file set) | New. Wraps `HighConsequenceDialog` (task 016), `consequence="outage"`. |
| `frontend/src/shared/i18n/messages.en.ts` | 22 new keys backing the four components above. |

`ConfigRepository.acceptRuntime`/`restoreRuntime` and the `VersionDiff`/`RenameMap` types already
existed (task 033 scaffolding) — this task did not touch `configRepository.ts`.

## Design decisions, and why

1. **`ObservedState`/`ViewStatus` extended with `declaredVersion`/`observedVersion`, both optional.**
   Same reasoning as task 058's `ViewAction.inputs` fix: the task's own acceptance criterion needs a
   field the pinned contract doesn't carry, and the field has a clear, single source (the
   already-established `SM-D13` declared-first pattern, §6.1). Optional rather than required — a
   required field would force every one of the other 16 fixtures in the shared suite to supply a value
   they have no reason to care about; `toEqual` already tolerates an absent optional key on both sides
   (proven before touching any fixture — ran the suite with the type change alone, 19/19 green), so only
   the three fixtures that actually exercise version-state display needed updating.

2. **`overlayRbac` is a documented no-op in v1, not a fabricated gate.** `FE-D22`'s own pseudocode names
   the seam; no code anywhere calls it yet. The catalog (`shared/authz/catalog.ts`) marks
   `config.edit`/`config.apply`/`config.rollback` `absent`, and `permissions.ts`'s own C12 rule forbids
   gating a v1 feature on a non-`grantable` verb — doing so would deny every principal, since no identity
   can hold a verb the catalog doesn't grant yet. Rather than invent a gate the catalog doesn't back
   (which would either lock out every operator or require a second, undocumented always-true condition),
   this returns the view model unchanged, with a `TODO(verb)` marker matching FE-032's own prescribed
   pattern for ungated v1 features. Tested for identity output across granted/ungranted permission sets
   and for non-mutation of a frozen input.

3. **`VersionPill`/`BlockedBanner` correct two bugs the reference has, flagged rather than copied.**
   The reference's own `VersionPill` `map` has no `VERSION_UNKNOWN` entry and silently falls back to
   `map.OK` — an `unknown` instance would render as a green "Version OK" chip, the exact fault-hiding
   `UI-D12` exists to prevent. Its `BlockedBanner` hardcodes the "version drift" title regardless of the
   actual state passed, so an `unknown` instance would show a drift-titled banner. Both are fixed here:
   `VersionPill` reuses `versionVocab` (task 013), which already has a correct, distinct `unknown` entry;
   `BlockedBanner` titles and reasons each state from the actual `status.versionState`. Tested explicitly
   (`'renders unknown as its own distinct state, never falling back to OK'`,
   `'titles and reasons unknown correctly, distinct from drift'`).

4. **`AcceptRuntimeDialog.rows` is caller-supplied, not fetched inside the dialog** — the same split
   `ConflictDialog.rows` already uses (task 056). The accept-runtime response shape is itself
   `API CONTRACT — TBD`, and `ConfigRepository.acceptRuntime` returns `VersionDiff.rows:
   readonly DiffRowFixture[]` (the testdoubles-typed shape), not `DiffView`'s own `readonly DiffRow[]` —
   the same unreconciled testdoubles-vs-contracts seam every FE-05x report from 055 onward has left open,
   since no Designer page exists yet to assemble any of these. Rather than invent a
   `DiffRowFixture → DiffRow` mapper to paper over a gap nothing yet needs bridged, the dialog takes
   `rows: readonly DiffRow[]` directly; its own confirm action calls `repository.acceptRuntime(instanceId,
   toVersion, renames)` to actually commit the correction, discarding that call's own returned
   `VersionDiff.rows` exactly as `ConflictDialog`'s `refetch` mutation already ignores its own result's
   shape in favour of the caller-supplied `rows`.

5. **Deliberate expansion beyond the reference's `AcceptRuntimeModal`.** The reference shows four static
   category counts and a confirm button with no real mapping step — a non-functional mockup. This task's
   own Objective line names the diff as "the load-bearing UX," and the acceptance line is explicit
   ("an unmapped rename must block completion"), so this renders the real `DiffView` against the real
   rows and genuinely disables confirmation while any `rename` row's mapping is empty or whitespace-only,
   rather than reproducing the reference's cosmetic count list.

6. **Rename mapping is free-text input, not a picker.** Nothing in the contract enumerates candidate
   "new path" names for a rename to choose from — `architecture.md` §7.2 only says "point the old field
   at its new name." A `TextField` per unmapped rename row, keyed by `row.path`, matches that literally
   and avoids inventing a candidate list the contract doesn't supply.

7. **`RestoreRuntimeDialog` uses `HighConsequenceDialog`; `AcceptRuntimeDialog` does not.** Only "Restore
   the runtime" redeploys and causes an outage per the task's own implementation line — "Accept the
   runtime" is a declaration-schema correction with no restart implied, matching the reference's own
   choice of a plain `Modal` (not styled as high-consequence) for `AcceptRuntimeModal`.

## Testing

Every command run from `frontend/`, output inspected:

```
npx vitest run contracts                                                          # 19/19, after fixture updates
npx vitest run src/features/designer/overlayRbac.test.ts                          # 3/3
npx vitest run src/features/designer/VersionPill                                  # 7/7
npx vitest run src/features/designer/BlockedBanner                                # 8/8
npx vitest run src/features/designer/RestoreRuntimeDialog                         # 7/7
npx vitest run src/features/designer/AcceptRuntimeDialog                          # 9/9
npm run typecheck                                                                 # clean
npm run lint                                                                      # clean (project-wide)
npx prettier --check <every file touched by this task>                            # clean except 3 fixtures + types.ts/project.ts
npx vitest run contracts src/features/designer                                    # 50 files, 227 tests — all passed
npm test                                                                          # full suite: 191 files; 1 file/2 tests failed
npm run build                                                                     # production build succeeds
```

The full-suite failures are confined to `src/features/inventory/instances/InstancesPage.test.tsx` — the
same pre-existing parallel-worker timeout flake already documented in every prior FE-05x report (044
onward, most recently 058, there against a different pair of tests in the same file). Re-ran in
isolation: 19/19 passed.

**Prettier — same pre-existing-content caveat as task 058's report, verified the same way (diffed
`prettier`'s own output against the file rather than trusting `--check` alone).** `types.ts`/`project.ts`
fail `--check` only on lines this task never touched (pre-existing, confirmed via `git show HEAD:...`
before this task started). The three `version-state-*.json` fixtures fail `--check` for exactly one
pre-existing line each — a `"fields": [{ ... }]` array from before this task that prettier would reflow
to one-object-per-line — never on the `observedState`/`status` blocks this task added or edited (diffed
`prettier <file>` against the working file directly to confirm). Left all five untouched rather than
letting a bare `--write` bleed ~20+ lines of unrelated reformatting into the diff, the same call task 058
made and documented.

Task-specific acceptance items:

- **Four states** — `VersionPill.test.tsx`/`.a11y.test.tsx` exercise all four (`ok`/`drift`/`unknown`/
  `unverified`) explicitly, including the reference's own `unknown`-falls-back-to-`ok` bug fixed here.
- **Blocked ≠ hidden** — `BlockedBanner.test.tsx`'s `'does not hide sibling content'` renders the banner
  next to a sibling "config" node and asserts both remain in the document.
- **Unmapped rename cannot complete** — `AcceptRuntimeDialog.test.tsx`'s `'confirm is disabled while a
  rename row has no mapping'` and `'a whitespace-only mapping does not count as mapped'`.
- **RBAC overlays, never mutates, the view model** — `overlayRbac.test.ts`'s three cases: identity output
  with nothing granted, identity output with every grantable verb granted, and no throw / no observable
  change against a frozen input.

## Status

DONE — implemented, tested (227/227 on the contracts+designer suite, full suite green modulo the
documented pre-existing `InstancesPage` flake), lint-clean, typecheck-clean, prettier-clean on every line
this task added or edited, production build succeeds. No Designer page exists yet to assemble
`VersionPill`/`BlockedBanner`/`AcceptRuntimeDialog`/`RestoreRuntimeDialog` alongside `SaveBar`/
`ConflictDialog`/`OverrideReasonDialog`/`PackageActions` — the same gap every report since 055 has left
open. Not yet marked done in `task-list.md` or closed on GitHub — pending commit approval.
