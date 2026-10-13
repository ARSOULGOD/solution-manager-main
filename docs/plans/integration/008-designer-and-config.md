# Designer & config — Integration Plan 008

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Wire the Designer tab — load, stage, history, secrets, accept/restore runtime — to the production gateway.

**Supersedes:** plan 002 Task 5.

## Operations

| Operation | Route | Frontend repository |
|---|---|---|
| `getDesigner` | `GET /dp/instances/{instanceId}/designer` | `frontend/src/features/designer/configRepository.ts` |
| `stageConfig` | `POST /dp/instances/{instanceId}/config` | same |
| `getConfigHistory` | `GET /dp/instances/{instanceId}/config/history` | same |
| `setConfigSecret` | `POST /dp/instances/{instanceId}/config/secrets/{fieldKey}` | same |
| `acceptRuntimeConfig` | `POST /dp/instances/{instanceId}/config/accept-runtime` | same |
| `restoreRuntimeConfig` | `POST /dp/instances/{instanceId}/config/restore-runtime` | same |

## Task 1: `getDesigner` — server-authoritative projection

The `Designer` schema is `Versioned` + `generation` + a `viewModel` transcribed from `contracts/view-models/src/types.ts` (OR-34), plus a required `layout` (instance key → `{x, y}`, OR-96, stored outside `config_generation`/history/hash).

- [x] **Done 2026-10-09.** `configRepository.loadDesigner()` now calls the real `getDesigner`
  (`{stateVersion, generation, viewModel, layout}`), fetches the raw `SolutionPackage` via an
  injected `GetSolutionPackage` (reusing `solutionsRepository.getPackage`, dependency-injected
  rather than cross-imported — `createConfigRepository`'s new second parameter), and reverse-maps
  the wire `ViewModel` into this engine's own `DraftValues`/`ObservedState`
  (`mapWireDesignerToLocal`, unit-tested on its own in `configRepository.test.ts`) so the
  UNCHANGED local `project()` keeps running exactly as before. `layout` is read off the envelope
  but not yet surfaced to any UI (no canvas-layout editing exists in this pass) — flagged, not
  dropped.
- [x] The frontend's own `project()` engine still runs locally for every live edit (`FE-D33`); the
  server's `viewModel`/`generation`/`layout` are the load-time baseline and the
  staging/diff/history/accept-runtime authority (`OR-34`) — the reconciliation this plan's own
  "Decisions carried forward" section recommended, now built.
- [ ] `mapWireDesignerToLocal`'s wire-shape interfaces are hand-transcribed from `console.yaml`,
  same as before — replace with generated types once `getDesigner`'s real schema is codegen'd
  (unchanged follow-up, now scoped to `configRepository.ts` instead of the whole module).
- **Two fields have no wire source and are reported via `DesignerLoad.unsupportedFields`, not
  silently fabricated:** `observed.restartObservedValues` (no raw per-field restart-observed
  snapshot crosses the wire) and `observed.configState` (approximated from the wire's five-tier
  `ViewModelConfigState` via a documented best-effort rule — no ratified mapping exists).
  `connectionCatalog`/`appliedConnectionRevisions` (`UI-D27`/`28`/`29`) have no counterpart in
  `console.yaml`'s `ViewModel` schema at all and are always `undefined` against the real backend.
- **KNOWN REGRESSION, left for a follow-up task:** `app/dev/devPackageHandlers.ts`'s own
  `/designer` MSW override still returns the old fixture shape (a dev-session package drop has no
  real backend to call `getDesigner` against) — `app/dev/devPackageFlow.test.ts` fails broadly
  until that dev-tooling path is rewritten to the same wire shape. Out of this task's scope (dev
  tooling, not named in Tasks 1/2, not part of the `designer` feature's own suite).

## Task 2: `stageConfig`

- [x] **Done 2026-10-09.** Body is `{baseStateVersion, componentSelection, draftValues, rendered}`.
  `SaveBar` gained `baseStateVersion`/`componentSelection`/`rendered` props (replacing the
  fixture-era `generation` string) and builds the real body; `rendered[]` is computed by
  `useDesignerDraft.ts`'s new `rendered` field via a per-instance-SCOPED `project()` pass (the
  engine's flat `sections[]` has no per-instance `renderedPath` grouping to read directly — see
  that module's `renderedEntriesFor` header). `422` (server-side projection mismatch) is handled
  as its own "stale base, reload" state in `DesignerForm.tsx` (a dismissable inline `Alert` with a
  reload action), kept DISTINCT from the `409` `ConflictDialog` flow.
- **Discovered contract gap, not fabricated:** `stageConfig`'s request schema is
  `additionalProperties: false` with no `overrideReason` field — `UI-D16`'s override-reason dialog
  still runs (the operator still explicitly confirms the warning) but the confirmed reason is
  accepted by `SaveBar`'s mutation and discarded, never sent; there is nowhere on the wire to put
  it. Flagged for whoever owns closing that gap in the contract.

## Task 3: History + secrets

- [ ] `getConfigHistory` (list) and diff action — confirm whether diffing is a separate operation or client-side over two history entries; don't invent a server diff endpoint that doesn't exist in the contract.
- [ ] `setConfigSecret`: `I6` — the value is a reference only; it must never round-trip in a GET response, a diff, or a log line. Add a test that fails if a secret value literal ever appears in a captured response fixture.

## Task 4: Accept/restore runtime

- [x] **Done 2026-10-09.** `acceptRuntimeConfig` body `{baseStateVersion, placementId}`;
  `restoreRuntimeConfig` body `{placementId, strategy}`, confirmed still defaulting to `manual`
  (plan 012's schedule work didn't change it). `configRepository.ts`'s `acceptRuntime`/
  `restoreRuntime` methods, their request/response types (`AcceptRuntimeIntent`/
  `AcceptedRuntimeConfig`/`RestoreRuntimeIntent`/`RestoreRuntimeAccepted`), and the MSW fixtures
  (`handlers/dp.ts`) all match the real contract.
  - **`AcceptRuntimeDialog` redesigned, not just re-wired.** The old UI (a rename-mapping diff,
    `toVersion`/`rows`/`renameMap`) had no contract basis — `acceptRuntimeConfig` takes only
    `{baseStateVersion, placementId}` and returns `AcceptedRuntimeConfig.notAccepted[]` (path +
    closed reason, e.g. `secret-not-read`/`undeclared-key` — never a value, `I6`). The dialog now
    explains the operation, runs it, and lists `notAccepted[]` by reason; there is nothing for the
    operator to map by hand (a `notAccepted` field is either reverted by the next apply or was
    never SM's concern).
  - **`placementId` sourcing.** Neither `DesignerPage` nor `DesignerForm` carried one. Fixed by
    threading `InstanceShell`'s already-fetched `Instance.placements[0]?.placementId` down through
    `DesignerPage`/`DesignerForm` as a new required prop (`placementId: string | undefined`) — the
    first live placement is the documented default; no placement selector exists yet (flagged, not
    designed in this pass — multi-placement instances get no way to pick a different one here).
  - **`baseStateVersion` sourcing.** Added `DesignerLoad.stateVersion` (the `Designer` envelope's
    own `Versioned` watermark) — additive, and the fixture-era `/designer` MSW route now returns it
    too, since Task 1 hasn't rewired that route yet (see below).
  - Verification: `AcceptRuntimeDialog`/`RestoreRuntimeDialog` test + a11y suites rewritten against
    the real contract; full frontend suite green except the pre-existing, unrelated
    `devPackageFlow.test.ts` gap (item 1's own note).

**Tasks 1 and 2: DONE 2026-10-09** — see each task's own checklist above for what was built, the
mapping decisions, and the two flagged gaps (`unsupportedFields` on the load side, the missing
`overrideReason` wire field on the save side). The one known regression this pass leaves behind is
`app/dev/devPackageFlow.test.ts` (dev-tooling, not this plan's Tasks 1/2) — see Task 1's own note.
Full report: [`reports/task-008-1-2-report.md`](reports/task-008-1-2-report.md).

## Task 5: Component tests + browser verification

- [ ] Playwright: open Designer → edit a field → save (stage) → view history → accept/restore runtime where applicable. Record in `reports/008-task-05-report.md`.

## Decisions carried forward

**✅ D-4's package-fetch half resolved 2026-10-09** (commit `592bff02`): `GET
/cp/solutions/{solutionId}/package` (operationId `getSolutionPackage`) is now in `console.yaml`,
served by `SolutionController`/`SolutionCatalog` (reusing `solution.view`, `x-sm-scope: reach` —
not `organization`, so an environment-bound Operator's own Designer still works), and
`solutionsRepository.ts`'s `getPackage()` is wired against it (unwraps the `SolutionPackageDocument`
envelope, sends `packageVersion` as canonical JSON text). **Tasks 1, 2 and the `getDesigner`/
`stageConfig`/`acceptRuntimeConfig`/`restoreRuntimeConfig` half of Task 4 are now unblocked** — the
recommended reconciliation below can be built. Not done in this pass: `configRepository.ts` itself
is unchanged and still entirely fixture-era (it does not yet call the real `getDesigner`, let alone
the new package route) — that rewiring is this plan's own Tasks 1/2/4, still to execute.

**Widened 2026-10-08 (this plan's execution, Tasks 1/2/4): blocked on D-4, not just the general "server re-projects" note.** Investigated whether `OR-34` ("the Java `project()` port... server re-projects") and `FE-D33` ("the browser projects", PROPOSED, 2026-08-26) genuinely conflict. They don't — `OR-34` (owner ruling, later, wins where they overlap per `I4`) governs the server's own authoritative record for staging/diff/history/refusal; `FE-D33`'s own un-superseded rationale ("a `when:` predicate must re-evaluate as the operator changes a discriminator... round-tripping to the server per keystroke is not a design, it is a workaround" — `docs/design/frontend/decisions.md:495`) still correctly describes why live editing must run `project()` **locally** in the browser.

The blocker: `getDesigner`'s real response (`{stateVersion, generation, viewModel, layout}`) carries no raw `SolutionPackage` for the browser's local `project()` to run against. The only source for the raw package is `GET /cp/solutions/{solutionId}/package` — which has **no contract operation** (bucket C / D-4, same gap plan 005 already flagged for the Solutions screen's package preview). Tasks 1, 2 and the `getDesigner`/`stageConfig`/`acceptRuntimeConfig`/`restoreRuntimeConfig` half of Task 4 cannot be correctly implemented or verified until D-4 is resolved for the package-fetch route specifically.

**Recommended reconciliation once D-4 lands:** `getDesigner` fetches the raw package via the (then-contracted) package route, runs `project()` locally exactly as today for all live editing (unchanged Composer/FieldRow/useDesignerDraft data flow), and uses the server's `viewModel`/`generation`/`layout` only as the load-time baseline and the staging/diff/history/accept-runtime authority. `configRepository.ts`'s header comment (currently asserts the unqualified, now-superseded reading of `FE-D33`, "Q19 resolved — the browser projects") needs correcting to describe this split, not deleted outright — the live-editing half of its claim is still correct.

## Out of scope

- Apply/rollback itself (consumes the generation this plan produces) — plan 009.
- Package actions (`POST /dp/instances/{id}/actions/{actionKey}`) — **501**, no verb (bucket D). Must render as "not available," not attempted.

## Global constraints

Same as plan 004 — see that plan's closing section. `I6` is load-bearing throughout this plan — re-read it before Task 3.
