# FE-060 · Rollout card: config stepper and replica table — Report

## What I implemented

Reference: `designer_panels.jsx`'s own `ConfigStepper`/`ReplicaTable`/`RolloutCard`
(`docs/design/frontend/reference/app/designer_panels.jsx:6-82`) and its data fixtures
`CONFIG_STATES`/`REPLICA_HEALTH`/`ROLLOUT_REPLICAS`/`DIVERGED_REPLICAS`
(`docs/design/frontend/reference/app/designer_data.jsx:154-178`), which task 033 already ported into
`frontend/src/shared/testdoubles/data/designer.ts` as `ReplicaObservationFixture`/`ROLLOUT_REPLICAS`/
`DIVERGED_REPLICAS`.

| File | Summary |
|---|---|
| `frontend/src/shared/vocab/configLadder.ts` (new) + `index.ts` | The five-rung ladder as data — `CONFIG_LADDER_STEP_KEYS`, `ConfigLadderStepKey`, and `ConfigLadderReachedKey = Exclude<ConfigLadderStepKey, 'effective-verified'>`. Same data/render split as every other vocab (`health.ts` etc. vs. `StateChip`) — this is the "shared source" task 065's own acceptance line asks for. |
| `frontend/src/shared/ui/ConfigStepper/` (new: `.tsx`, `.types.ts`, `.test.tsx`, `.a11y.test.tsx`, `.typecheck.test.ts`, `.gallery.tsx`, `index.ts`) | New. Renders the five rungs from `configLadderSteps`, `reached: ConfigLadderReachedKey` as its only prop. Registered in `Gallery.tsx`/`shared/ui/index.ts` alongside every other Phase-1-style primitive. |
| `frontend/src/features/designer/ReplicaTable/` (new: `.tsx`, `.types.ts`, `majorityGeneration.ts` + `.test.ts`, `.test.tsx`, `.a11y.test.tsx`, `index.ts`) | New. Replica · rendered config version · restart observed · health, on `DataTable` (task 020) rather than the reference's raw `<table>`. |
| `frontend/src/features/designer/RolloutCard/` (new: `.tsx`, `.types.ts`, `.test.tsx`, `.a11y.test.tsx`, `index.ts`) | New. Collapsible wrapper pairing the two, matching `SectionCard`'s own disclosure pattern (task 049) rather than introducing MUI `Accordion` as a second idiom. |
| `frontend/src/shared/i18n/messages.en.ts` | 20 new keys backing the three components above. |
| `frontend/src/app/dev/Gallery.tsx` | Registers `ConfigStepper`'s gallery section. |

`ReplicaTable`/`RolloutCard` are feature-local (Designer only, no gallery entry) — same placement as
`VersionPill`/`BlockedBanner` (task 059). `ConfigStepper` is the one component placed under `shared/ui`
rather than `features/designer`, specifically because task 065 (a different feature, governance) needs the
same component and the ESLint architecture boundary (task 009) only allows cross-feature reuse through
`shared/*` or a feature's own public barrel — `designer` has no barrel, so `shared/ui` is the only place
that doesn't require inventing one.

## Design decisions, and why

1. **`reached` is a single cursor of type `ConfigLadderReachedKey`, not a per-step boolean array.**
   The task's own acceptance line is explicit: *"The stepper must be incapable of marking
   effective-verified reached without an explicit verified signal."* A per-step `{key, reached: boolean}[]`
   (the shape `governanceRepository.ConfigLadderStep` already has, from task 033's scaffold) can't make that
   guarantee — nothing stops a caller or a malformed fixture from setting the fifth entry's `reached: true`
   directly. A single "highest rung reached" cursor typed to exclude `'effective-verified'` makes it a
   compile error to even attempt that, from any fixture in the suite — `ConfigStepper.typecheck.test.ts`
   proves it (`@ts-expect-error` on `{ reached: 'effective-verified' }`), and `ConfigStepper.test.tsx`
   proves the runtime consequence: for every one of the four valid `reached` values, the fifth rung's
   `aria-label` is always `"Effective-verified — pending"`, never done or current. **Known gap, left open
   deliberately:** `governanceRepository.ts`'s `ConfigLadderStep` (task 033 scaffold, `FIXTURE`-ready, not a
   pinned contract) still uses the per-step-boolean shape. Task 065 will need to either adapt its repository
   fixture to a cursor or add a translation at that boundary — the same kind of testdoubles-vs-contracts
   seam every FE-05x report since 055 has left open for its own repository, not resolved retroactively here
   since no governance UI exists yet to need it reconciled.

2. **Colour is never the only channel, extended to the stepper.** Task 013's own rule ("no screen defines
   its own status string... every state carries text and icon") doesn't literally cover a *sequence* of
   states, but the same failure mode applies: the reference's own `ConfigStepper` distinguishes done/
   current/pending only by CSS class and icon shape — no text. Screen-reader users get nothing. Each rung
   here carries an `aria-label` stating its word (`"Rendered — done"`, `"Persisted — current"`, etc.) via new
   `configStepperState{Done,Current,Pending}` keys, verified by `ConfigStepper.a11y.test.tsx` across every
   reachable value in both themes.

3. **`ReplicaTable` on `DataTable`, not a raw `<table>`.** The reference hand-rolls `<table className="rep-tbl">`.
   Task 020's own objective line names "replicas" as one of the five row-kinds `DataTable` exists to unify,
   and FE-060 depends on task 020 specifically (not just 013) — a strong signal this table was meant to go
   through it rather than duplicate a table implementation. `ColumnDef.info` (already built for `driver`/
   `locus` being data, `columns.tsx`) is reused verbatim for the two invented headers.

4. **Health translated into `healthVocab`, not given a second copy.** `ReplicaObservationFixture.health`
   uses `'GREEN'|'RED'|'RESTART_EXPECTED'|'INACTIVE_REPLICA'` — task 033's own shape, predating task 013's
   vocabulary and never reconciled with it (`governanceRepository.ts`'s `HealthRollupState` has the same
   uppercase shape, same gap). Rather than repeat the reference's own separate `REPLICA_HEALTH` label/tone/
   tip map, `REPLICA_HEALTH_TO_VOCAB` translates the four keys into `HealthKey` and renders through the real
   `healthVocab`/`StateChip` — one definition of what "Healthy"/"Restart expected"/etc. mean and look like,
   not two.

5. **"Odd replica" highlighting is a pure function (`majorityGeneration.ts`), independent of `diverged`.**
   The reference computes `mode()` inline per-cell. Extracted and unit-tested (including the even-split
   tie-break, first-seen-wins, matching the reference's own reduce order) because it's exactly the kind of
   small algorithmic rule a future refactor could silently break without a table-free way to catch it.
   Deliberately **not** the same signal as `RolloutCard.diverged`: a replica lagging behind mid-rollout also
   has "more than one generation present," but that's expected, in-progress behaviour, not the halted,
   `CONFIG_DIVERGED` state task 062 owns. `diverged` stays a caller-supplied prop for that reason — this
   function only marks visually which replica is the minority, in any multi-generation snapshot.

6. **`RolloutCard` uses a real `<button aria-expanded>`, not MUI `Accordion`.** `SectionCard` (task 049)
   already established this exact disclosure pattern in the same feature folder; matching it over
   introducing MUI's `Accordion` avoids a second collapsible idiom for the same behaviour, and keeps the
   expand icon on the same "not `@mui/icons-material`, that package isn't a dependency" convention already
   in force there.

## Testing

Every command run from `frontend/`, output inspected:

```
npx vitest run src/shared/vocab src/shared/ui/ConfigStepper \
  src/features/designer/ReplicaTable src/features/designer/RolloutCard src/app/dev   # 11 files, 64/64
npm run typecheck                                                                    # clean
npm run lint                                                                         # clean (project-wide, --max-warnings=0)
npx prettier --check <every file this task touched or added>                         # clean
npm run build                                                                        # production build succeeds
npm test                                                                             # full suite: 199 files, 3 failed — see note below
```

**The 3 full-suite failures are not from this task.** They're in
`src/features/inventory/solutionsRepository.test.ts` and `SolutionsPage.test.tsx`, caused by an unrelated,
already-modified-on-disk `frontend/src/shared/testdoubles/data/solutions.ts` (an extra `db-connector`
fixture entry, 18 lines, present before this task started and untouched by it — confirmed by
`git stash push` on just that one file and re-running the two failing files in isolation: 10/10 green with
the stray change removed, then restored via `git stash pop`). Flagged to the user separately; not fixed
here since it isn't this task's file and may be another session's in-progress work.

Task-specific acceptance items:

- **Convergence never claimed** — `ConfigStepper.typecheck.test.ts` (compile-time: `'effective-verified'`
  is not a valid `reached` value) and `ConfigStepper.test.tsx`'s `it.each` over all four reachable keys,
  asserting the fifth rung's `aria-label` is always `"Effective-verified — pending"`.
- **Per-replica detail, not a spinner** — `ReplicaTable.test.tsx`'s first case renders one row per replica
  by id, for both the 4-replica normal fixture and the diverged one.
- **Invented column names flagged in the UI** — `ReplicaTable.test.tsx` asserts both header info triggers
  carry their "invented column name" tip text (queried by accessible name, not a placeholder attribute).
- **Divergent-generation fixture highlights the odd replica** — `majorityGeneration.test.ts` (pure-function
  unit coverage, including the even-split tie-break) plus `ReplicaTable.test.tsx`'s computed-style
  comparison between a majority- and minority-generation cell.

## Status

DONE — implemented, tested (64/64 on the touched suite; full suite green except the pre-existing,
unrelated `solutions.ts` stray change noted above), lint-clean, typecheck-clean, prettier-clean, production
build succeeds. No Designer page exists yet to place `RolloutCard` on an instance's Designer screen — the
same gap every report since 055 has left open (no page assembling `SaveBar`/`ConflictDialog`/
`VersionPill`/etc. either). Not yet marked done in `task-list.md`/`execution.md` or closed on GitHub —
pending commit approval.
