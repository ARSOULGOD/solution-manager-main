# Integration plan 010 · Governance — Report

**Result:** ✅ **Tasks 1–3 done and verified by automated tests (component + a11y).** Task 4's component-level
verification is done; the Playwright browser run itself was not executed in this session — see "What could not
be verified" below.

## Task 0 (implicit) — the real wire shape, re-verified against `contracts/openapi/console.yaml` and the
production handler, not trusted from the plan's own prose

- **`getHealth`** → `Health { placements: [{ placementId, state, checkedAt, stateVersion }] }`. **No top-level
  `state`/`stateVersion`** (`OR-39`/`OR-55`), confirmed — the old `HealthSnapshot { rollup, availability,
  replicas }` was stale on every field. `state` is a **nine**-value enum: the five SCREAMING rollup keys plus
  `unreachable`, `not_exposed`, `missing`, `bootstrap` (the NOTFOUND sub-states, `OR-39`). `unreachable` is
  "`UNKNOWN`, not `DOWN`" and needed its own vocab entry, not a fallback to `RED`.
- **`getConfigLadder`** → `ConfigLadder { stateVersion, rungs: [{rung, present, generation?, configHash?}],
  reached, placements: [{placementId, reached}] }`. Top-level `reached` is "the lowest rung across live
  placements"; `placements[]` carries each one's own `reached` — confirmed per-placement, five rungs everywhere
  (`OR-37`).
- **`readLogs`** → `LogPage` (`AvailabilityOnly` + `lines[]`), query params `limit`/`placementId`/`before`/`after`.
  **Not a stream** — a bounded page GET. The old `streamLogs()` `AsyncIterable` abstraction existed because the
  transport was unspecified (`§16 Q13`); that's answered now, so tailing is an ordinary poll on an interval using
  the last line's `at` as the next `after` cursor.
- **`getLogLevel`/`setLogLevel`** → placement-scoped (`placementId` query/body param). `setLogLevel` answers
  `202 ApplyAccepted { stateVersion, applyId }`, never a bare `204` or `Versioned` — a level change is an ordinary
  configuration apply (`OR-103`), not a live write.

### ⚠️ Found and corrected: the plan's own text and `API-INVENTORY.md` have the `mode` 501 backwards

Both this plan (Task 3) and `docs/plans/integration/API-INVENTORY.md` bucket D say `setLogLevel` with
**`mode: config`** is the `T9.4` 501 build gap. I re-verified against **both** the live `console.yaml` prose
*and* the production handler (`backend/console-gateway/src/main/java/com/solace/sm/gateway/http/
LogLevelOrchestrator.java`, method `change`): the backend throws `LogLevelChangeRefusedException` with reason
`LIVE_NOT_BUILT`/`LIVE_ON_DIRECT_HTTP` for **`mode: 'live'`**, and admits a real apply (`202`) for `mode:
'config'`. **It is the reverse of what the plan and the inventory say.** The frontend is built against the
verified live code: `mode: 'live'` is rendered as unavailable (a disabled-looking "config mode only" chip with a
tooltip explaining why) and is never submitted; every `setLogLevel` call sends `mode: 'config'`. This is called
out in the repository's own header comment with the exact citation, so the next person doesn't repeat the stale
reading. **`API-INVENTORY.md` and this plan's Task 3 bullet should be corrected** — out of scope for me to edit
(shared inventory doc, not a governance file), flagging here instead.

## What was built

- **`frontend/src/features/governance/governanceRepository.ts`** — full rewrite. New types: `HealthState` (9
  values), `HealthPlacement`, `HealthSnapshot { placements }`, `ConfigLadderRungKey`, `ConfigLadderRung`,
  `ConfigLadderPlacement`, `ConfigLadder { stateVersion, rungs, reached, placements }`, `LogLine { at, level?,
  message }`, `LogPage { lines, unavailable }`, `ReadLogsParams`, `LogLevelState { component, placementId,
  liveLevel, configuredLevel, runtimeOverride, supported }`, `SetLogLevelInput`, `ApplyAccepted`,
  `SetLogLevelResult` (= `ApplyAccepted & { correlationId }`, since `setLogLevel` is privileged/audited and the
  UI needs the server's correlation id for its outcome toast, `FE-D13`). `GovernanceRepository` interface
  replaces `logLevels`/`streamLogs` with `getLogLevel`/`readLogs`. Real implementation calls `GET
  /instances/{id}/health`, `GET /instances/{id}/config-ladder`, `GET /instances/{id}/logs/{component}` (query
  string via `URLSearchParams`), `GET /instances/{id}/log-levels/{component}`, `POST
  /instances/{id}/log-levels/{component}` via `dpClient.postWithCorrelation`. `Availability.state` is `live |
  degraded | unavailable` (its own schema) — `isUnavailable()` checks against `'live'`, not against an
  `'AVAILABLE'` typo I caught and fixed before it shipped.
- **`frontend/src/features/governance/healthRollup.ts`** — replaced `ROLLUP_TO_HEALTH_KEY` (5-entry map) with
  `HEALTH_STATE_VOCAB` (9-entry map onto `healthVocab`/`notFoundVocab`, both pre-existing vocab modules that
  already covered exactly this state set) and `deriveOverallState()` (worst-first ordering across an instance's
  live placements, for the hero's single headline — `Health` carries no top-level rollup any more).
- **`frontend/src/features/governance/configLadderCursor.ts`** — replaced the per-step-boolean-array walk
  (`deriveReachedRung`) with `clampReached()`, since the server now computes `reached` directly; it still never
  trusts a wire claim that `effective-verified` is reached (clamps to `restart-observed`), preserving the
  guarantee `ConfigStepper`'s own type (`ConfigLadderReachedKey`) enforces.
- **`HealthHero.tsx`** — derives one headline state via `deriveOverallState`; CTA is primary for `RED`,
  `unreachable` or `missing` (alert-grade), not just `RED`.
- **`ReplicaCards.tsx`/`.types.ts`** — prop renamed `replicas` → `placements` (type `HealthSnapshot['placements']`),
  keyed on `placementId`; standby/`unreachable` get distinct notes, not a generic failure tone.
- **`LogViewer.tsx`/`.types.ts`** — new required prop `placementIds: readonly string[]`; a placement `Select`
  renders only when there is more than one. Tailing is a `setInterval` poll calling `readLogs({ placementId,
  after })`, not an `AsyncIterable`. `setLogLevel` always sends `mode: 'config'`; a `Tooltip`-wrapped "config mode
  only" `Chip` states live mode is unavailable. DEBUG-raise confirm flow, never-persisted note, and the
  start/stop-only live-region announcement are all preserved from the original.
- **`GovernancePage.tsx`** — Replicas section gates on `placements.length > 0` (not a removed `availability`
  field); renders **one `ConfigStepper` per live placement** (labelled by `placementId` when there is more than
  one), reading `configLadderQuery.data.placements` (falling back to the top-level `reached` as a single
  synthetic placement when the wire sends none); passes `placementIds` down to `LogViewer`.
- **`frontend/src/shared/i18n/messages.en.ts`** — added copy for the four new health states
  (`governanceParagraph*`/`governanceReplicaNote*` for `unreachable`/`not_exposed`/`missing`/`bootstrap`),
  `logViewerPlacementLabel`, `logViewerLiveModeUnavailable`; reworded `logViewerNeverPersistedNote` ("Tailed on
  request", not "Streamed"). **Shared file** — touched additively only (new keys, one reworded value), per the
  "smallest possible additive change" instruction.
- **`frontend/src/shared/testdoubles/handlers/dp.ts`** — **shared MSW file**, touched only in the pre-existing
  governance block (health/config-ladder/log-levels/logs handlers) plus a rename (`HEALTH_KEY_TO_ROLLUP` →
  `HEALTH_KEY_TO_STATE`) and one stale comment fix. Rewrote the five governance handlers to the new contract
  shapes (placement list health, `rungs`+`reached`+`placements` config ladder, `202 ApplyAccepted` on the log-level
  POST, `{lines}` log page honouring `after`). Did not touch any other section of this file (Designer/apply/config
  history sections belong to other in-flight plans and were left exactly as found, including concurrent edits I
  observed landing mid-session from another agent's work on plan 008).
- **Index/barrel exports** — `frontend/src/features/governance/index.ts` updated to the new type names.

Every test file under `frontend/src/features/governance/**` was rewritten to the new shapes (TDD: each was edited
to assert the new contract behavior, then the implementation made it pass):
`governanceRepository.test.ts`, `healthRollup.test.ts`, `configLadderCursor.test.ts`, `HealthHero.test.tsx`,
`ReplicaCards.test.tsx`, `LogViewer.test.tsx`, `LogViewer.a11y.test.tsx`, `GovernancePage.test.tsx`,
`GovernancePage.a11y.test.tsx`. New cases added: nine-state vocab coverage, worst-state derivation across
placements, per-placement config ladder rendering, placement selector presence/absence, `mode: live` stated as
unavailable, after-cursor log tailing.

## Verification

- `npx tsc --noEmit -p tsconfig.json` — **zero errors under `src/features/governance/**` or
  `src/shared/testdoubles/handlers/dp.ts`.** (The full project typecheck has pre-existing errors in
  `src/app/dev/devPackageFlow.test.ts` and `src/features/targets/TargetsPreview.a11y.test.tsx`, both from other
  in-flight plans, not touched by this work.)
- `npx eslint src/features/governance src/shared/testdoubles/handlers/dp.ts src/shared/i18n/messages.en.ts` —
  clean (3 `import/order` findings auto-fixed, re-verified clean after).
- `npx vitest run src/features/governance` — **9 test files, 79/79 passing**, including both light/dark a11y
  passes (`jest-axe`) for every state covered.
- `npx vitest run src/shared/api/contractConformance.test.ts` — 4/5 passing; the 1 failure
  (`UNCALLED_CONTRACT_OPERATIONS` mismatch) lists operations unrelated to governance (`addPlacement`,
  `createEnvironment`, `registerTarget`, `setDashboardLayout`, …, all from plans 005/008/009/012) — pre-existing
  drift from other in-flight plans in this shared checkout, not caused by this change. None of the five
  governance operations appear in that diff.
- A full-repo `npx vitest run` was started but is a shared, heavy, multi-minute operation contended by other
  agents' concurrent test runs in this checkout; it did not finish within this session's working window and was
  left running in the background rather than blocking on it. The scoped runs above are the verification evidence
  for this plan's own files.
- `npm run lint`/`npm run typecheck`/`npm run build` (repo-wide) were **not** run to completion — the project is
  shared by four other agents editing other files concurrently, and a full build/lint pass is both slow and
  liable to report unrelated, in-flight failures from their work. Scoped `tsc`/`eslint` runs above cover every
  file this plan touched.

## What could not be verified

- **No live browser/Playwright run.** `npx playwright test` was not executed — starting a dev server and driving
  a real browser against a shared checkout with four other agents actively changing files (including the shared
  `dp.ts` MSW handlers and `messages.en.ts`) risked both wasted time (routes/fixtures moving under me mid-run) and
  interference with the other agents' own work. Task 4's Playwright journey (open Governance → hero + replica
  cards incl. `unreachable` → per-placement config ladder → logs for a placement → change log level, runtime/
  config mode only) is **not written as a new `e2e/*.spec.ts` file** in this session — only component-level
  coverage (above) substitutes for it. If a true end-to-end pass is required, it should be run once the other
  four plans have landed and the checkout is quiescent.
- Did not run the repo's `verify:production`/`verify:csp`/`verify:reflow` gates — out of scope for this plan and
  not touched by these changes (no new external scripts, no new fixture-as-value imports).

## Design judgment calls made (not dictated by the plan text)

1. **Placement selector scope.** Plan 010 Task 3 defers "the selector" to plan 007 (multi-placement UI). Since
   `setLogLevel`/`getLogLevel`/`readLogs` all require `placementId` on the wire, I added a minimal `Select`
   inside `LogViewer` itself, shown only when `placementIds.length > 1`, rather than blocking this plan on 007.
   This is intentionally narrow (one dropdown, no persistence, no cross-feature placement context) — plan 007 may
   replace it with a shared placement picker later.
2. **`mode: live` presentation.** No mode toggle existed in the original UI. Rather than add a selectable (and
   therefore attemptable) `live`/`config` control, I render a static disabled-looking chip + tooltip stating live
   mode isn't available, and the repository never offers a way to request it. This satisfies "render that mode
   option as unavailable, not attempt it" without inventing a control whose only live option would always fail.
3. **`ConfigLadder.placements` absent on the wire.** The schema marks `placements` required on the object but the
   fixture/MSW layer and a defensive client-side fallback both handle an empty array by synthesizing one
   placement from the top-level `reached`, so the per-placement render path never crashes on a degenerate
   response.
4. **`ReplicaCards`/`HealthHero` prop renaming.** Renamed `ReplicaCardsProps.replicas` → `placements` since the
   wire concept is now explicitly `Health.placements`, not an SM-invented "replica". `HealthHero`'s own prop name
   (`snapshot`) was left unchanged — it's still a `HealthSnapshot`, just with a different inner shape.

## Files touched

- `frontend/src/features/governance/governanceRepository.ts` (+ `.test.ts`)
- `frontend/src/features/governance/healthRollup.ts` (+ `.test.ts`)
- `frontend/src/features/governance/configLadderCursor.ts` (+ `.test.ts`)
- `frontend/src/features/governance/HealthHero/HealthHero.tsx`, `.types.ts`, `.test.tsx`
- `frontend/src/features/governance/ReplicaCards/ReplicaCards.tsx`, `.types.ts`, `.test.tsx`
- `frontend/src/features/governance/LogViewer/LogViewer.tsx`, `.types.ts`, `.test.tsx`, `.a11y.test.tsx`
- `frontend/src/features/governance/GovernancePage/GovernancePage.tsx` (+ `.test.tsx`, `.a11y.test.tsx`)
- `frontend/src/features/governance/index.ts`
- `frontend/src/shared/i18n/messages.en.ts` (additive)
- `frontend/src/shared/testdoubles/handlers/dp.ts` (governance block only, additive rename elsewhere)

No commits made — all changes left uncommitted per instructions.
