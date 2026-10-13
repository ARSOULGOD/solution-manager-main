# FE-065 · Configuration-state ladder — Report

## What I implemented

Reference: `docs/design/frontend/reference/app/governance.jsx`'s `LADDER` constant and the ladder `Paper`
block (lines 15-21, 134-145). Task 065 depended only on 064; `ConfigStepper` (task 060, `shared/ui`) already
existed and its own header comment stated the intent explicitly: *"task 065's governance configuration-state
ladder renders the same five rungs from the same source... `shared/vocab/configLadder.ts`'s step data and
this component are that shared source."* Task 060's own report additionally flagged the one gap left open
for this task: `governanceRepository.ts`'s `ConfigLadder` (task 033 scaffold) is a per-step boolean array,
not the `ConfigLadderReachedKey` cursor `ConfigStepper` requires — "task 065 will need to either adapt its
repository fixture to a cursor or add a translation at that boundary." This task is mostly that translation
plus wiring; no new render component was needed.

| File | Summary |
|---|---|
| `frontend/src/features/governance/configLadderCursor.ts` (new) + `.test.ts` | `deriveReachedRung(steps)` — the one translation boundary. Walks the ladder from `desired` and stops at the first un-reached rung or at `restart-observed`, so the result always fits `ConfigLadderReachedKey`; a malformed wire response claiming `effective-verified: true` is structurally clamped away, not just discouraged by convention. |
| `frontend/src/features/governance/GovernancePage/GovernancePage.tsx` | Added a second query (`repository.configLadder`), gated together with the health query (matching the reference's own single `demo` state governing the whole page rather than per-section loading/error). Reordered sections to match the reference: hero → non-goal alert → replicas → **Configuration state** (`SectionHead` + `Paper` wrapping `ConfigStepper`). `ConfigStepper`'s own built-in caption already states "a completed restart reaches restart-observed... Outcome is `APPLY_INDETERMINATE`" — the spec's "closing alert" requirement, satisfied without a second, duplicate banner. |
| `frontend/src/shared/i18n/messages.en.ts` | Two new keys: `governanceConfigStateSectionTitle`, `governanceConfigStateCaption` ("five states, never collapsed"). |
| `frontend/src/features/governance/GovernancePage/GovernancePage.test.tsx`, `.a11y.test.tsx` | Stub `configLadder` now resolves by default (previously rejected, which the new second query would have surfaced as a page-level error on every existing test). Added cases: all five rungs render, the fifth rung stays "pending" even when a stubbed response claims it reached, and a config-ladder-specific fetch failure renders the same error/retry path as a health failure. |

## Design decisions, and why

1. **No new ladder-rendering component.** `ConfigStepper` was purpose-built at task 060 to be this task's
   shared source, down to the exact "restart-observed, never convergence" copy this task's own spec asks
   for. Building a second component here would have duplicated both the render logic and the type-level
   guarantee (`ConfigLadderReachedKey` excludes `'effective-verified'`) that makes the fifth rung
   structurally unreachable. `GovernancePage` only supplies the `Paper` wrapper and section chrome — the
   same shape `Replicas` already uses.
2. **`deriveReachedRung` stops at the first gap, not the highest reached key.** A well-formed ladder is
   sequential (rendered implies desired, persisted implies rendered, ...), so a response claiming
   `persisted: true` while `rendered: false` is malformed. Rather than pick the highest `true` regardless of
   order (which could report a rung the process hasn't actually passed through), the function walks forward
   and stops at the first `false`/missing entry — defensive against a malformed fixture or future backend
   response, not just the happy path.
3. **Both queries gated together, not independently.** The reference prototype's `body()` renders one
   loading/error state for the whole page from one `demo` variable — there's no partial-page precedent to
   diverge from, and Governance's own objective ("answer one question... refuse to answer any other") argues
   against a page that can show health while silently omitting configuration state on a config-ladder-only
   failure. `onRetry` refetches both queries together for the same reason.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run typecheck                                          # clean
npm run lint                                                 # clean (project-wide, --max-warnings=0)
npm run test -- configLadderCursor governance InstanceShell  # 9 files, 44/44
npm run test                                                  # full suite: 235 files, 1346/1346 green
npx prettier --write <every file this task touched or added>     # applied, then re-verified typecheck/lint/tests
npm run build                                                  # production build succeeds
```

Task-specific acceptance items:

- **Five rungs, never collapsed** — `GovernancePage.test.tsx` asserts all five labels render; visually
  confirmed (screenshot below) — `Desired`/`Rendered`/`Persisted` done, `Restart-observed` current,
  `Effective-verified` permanently pending in neutral grey.
- **Fifth rung honest** — `configLadderCursor.test.ts` proves the cursor never returns
  `'effective-verified'` even when every prior rung (including a stubbed `effective-verified: true`) is
  reached; `GovernancePage.test.tsx` proves the same at the page level via the rendered `aria-label`.
- **Shares the state source with task 060** — both `ConfigStepper` and `shared/vocab/configLadder.ts` are
  imported unmodified from their task-060 location; no second copy of the five-rung data or the "never
  claims convergence" copy exists.

### Browser verification

Reused the FE-064 dev-server + Playwright setup. Navigated to `cache-prod-01/governance` and confirmed,
with a screenshot and zero console errors: the "Configuration state" section renders below Replicas, three
rungs done (green), `Restart-observed` current (blue), `Effective-verified` pending (grey) with its info
tip, and the closing note reading "A completed restart reaches restart-observed, not effective-verified...
Outcome is APPLY_INDETERMINATE." Screenshot sent to the user; verification script was a scratch file, not
committed.

## Status

DONE — implemented, tested (44/44 on the touched suite; full suite green, 235/235 files), lint-clean,
typecheck-clean, prettier-clean, production build succeeds, browser-verified. Not yet marked done in
`task-list.md` or committed — pending user confirmation. GitHub issue #81 (`FE-065 · Configuration-state
ladder`) already exists (created alongside the rest of Phase 5 during task 064's session) — to be closed on
commit.
