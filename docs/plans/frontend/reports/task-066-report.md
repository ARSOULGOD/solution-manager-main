# FE-066 · Log viewer — Report

## What I implemented

Reference: `docs/design/frontend/reference/app/governance.jsx`'s `LogViewer` (lines 23-82) and
`dynamic-ui/design.md` §5.2 ("Logs"), which is the actual spec behind this task's Refs (`UI-D10` `I6` `I1`)
— it's what confirms components come from the package's own declarations ("a component declares its
`loggerPrefix`, making 'turn on DEBUG for this component' declarative for any MI") and pins the two hard
constraints: **log content is streamed on request and never persisted**, and **the `loggers` POST is
privileged and audited**.

| File | Summary |
|---|---|
| `frontend/src/features/governance/LogViewer/` (new: `.tsx`, `.types.ts`, `.test.tsx`, `.a11y.test.tsx`, `index.ts`) | Component selector, level selector (`ERROR`/`WARN`/`INFO`/`DEBUG`), Stream/Pause, a bounded (`MAX_LINES = 200`) monospace viewport. Raising to `DEBUG` opens `HighConsequenceDialog` (task 016) before any repository call; on confirm, the mutation's server correlation id backs a `pushOutcome` (task 021) — both the persistent header badge and a transient toast. A component with `logLevels().supported === false` renders a `severity="neutral"` `Alert` — a stated degradation, never `role="alert"`. |
| `frontend/src/features/governance/governanceRepository.ts` | `setLogLevel` now returns `Promise<{ correlationId: string }>` (via `dpClient.postWithCorrelation`) instead of `Promise<void>` — `ToastInput.correlationId` is required (`FE-D13`), and a toast can't fabricate one, so the repository has to surface the server's id. Same pattern `configRepository.save()` already uses. |
| `frontend/src/features/designer/index.ts` | Re-exports `deriveComponentSelection` (already built at task 123) from the public barrel, so `GovernancePage` can reuse the same "which components exist for this instance's version" logic Designer uses, instead of a second copy. Cross-feature imports may only reach a public barrel (`boundaries/dependencies`, task 009) — this is the one-line addition that makes the reuse legal. |
| `frontend/src/features/governance/GovernancePage/GovernancePage.tsx` | Added a `configRepository.loadDesigner()` query (same query key `DesignerPage` uses, so a Designer visit warms this one from cache) purely to get the component list, and a **Logs** section built from it. **Gated independently** from health/config-state — a failure to load the package for the log viewer's component list degrades just that section, it does not hide the rest of the page (`I1`). |
| `frontend/src/shared/testdoubles/handlers/dp.ts` | The log-level POST/GET pair was stateless (POST always 204, GET always hardcoded `INFO`) — harmless for task 033's own scaffold, but it meant a DEBUG raise's own "DEBUG · audited" chip could never light up against the real fixture, only in a stubbed unit test. Made it stateful via a small `Map<string, LogLevel>` keyed `instanceId:component` — safe unlike the declare/undeclare handlers' deliberate statelessness (their own comment explains why those stay stateless), because no existing test exercises the real MSW-backed `setLogLevel()` + `logLevels()` pair together. |
| `frontend/src/shared/i18n/messages.en.ts` | 15 new `logViewer*` keys plus `governanceLogsSectionTitle`/`governanceLogsPackageErrorTitle`. |
| `frontend/src/features/instance/InstanceShell.tsx` | Forwards its existing `configRepository` prop to `GovernancePage` (already had it, for Designer). |
| `GovernancePage.test.tsx`/`.a11y.test.tsx` | Updated stubs to include `configRepository`; added cases for the Logs section, its independent error path, and a DEBUG-confirm-dialog a11y pass. |

## Design decisions, and why

1. **No new "components" endpoint invented.** The log route itself is `API CONTRACT: TBD` (§16 Q13), so
   adding a *second* unspecified endpoint just to list components would compound the same problem. Reusing
   `configRepository.loadDesigner()` — already real, already fixture-backed, already the thing that answers
   "what components does this instance have at this version" for Designer — means the log viewer's component
   list can never disagree with Designer's, and there's one fewer invented contract to later reconcile.
2. **Logs is gated independently from health/config-state**, unlike 064/065's combined gate. Task 065's own
   report argued combined gating specifically because health and config-state are one narrative Governance
   exists to tell; Logs is explicitly the "optional with a defined degradation" case `I1` describes, so a
   failure to load the package for its component list degrades only that section — `GovernancePage.test.tsx`
   has a case proving the rest of the page (hero, replicas) still renders when this happens.
3. **`setLogLevel`'s return type changed from `void` to `{correlationId}`.** Reusing `pushOutcome` is what
   `UI-D10`'s "the change and the operator's identity are written to the audit log" actually cashes out to
   in this UI — the correlation id is the visible proof a real audited request happened, not just a client
   flag. `ToastInput.correlationId` being required (`FE-D13`) is exactly the guard that would have caught a
   fabricated one.
4. **The MSW fixture fix.** Caught during browser verification, not by the test suite: the DEBUG chip never
   appeared against the running app, because the log-levels GET handler was hardcoded regardless of what was
   POSTed. The unit tests never caught this because they all use a stubbed `GovernanceRepository`, not the
   real MSW-backed one — see the file's own new comment for why making it stateful is safe here specifically
   (no test today exercises the real POST+GET pair together, unlike declare/undeclare where that risk is
   real and already documented).
5. **Level selector is exactly the four values the spec names** (`ERROR`/`WARN`/`INFO`/`DEBUG`), even though
   `LogLevel` (the repository's own type) also has `TRACE` — a component could in principle report `TRACE`
   from `logLevels()`, and the log-line renderer/tone map handles that case, but the *setter* only offers
   what task 066's own Implementation bullet lists.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run typecheck                                                    # clean
npm run lint                                                          # clean (project-wide, --max-warnings=0)
npm run test -- governance InstanceShell designer                     # 85 files, 435/435
npm run test                                                            # full suite: 237 files, 1360/1360 green
npx prettier --write <every file this task touched or added>              # applied, then re-verified typecheck/lint/tests
npm run build                                                           # production build succeeds
```

Task-specific acceptance items:

- **Nothing persisted** — `LogViewer.test.tsx` spies on `Storage.prototype.setItem` through a full
  stream-and-render cycle and asserts it is never called; the buffer lives only in `useState`, capped at
  200 lines, discarded on unmount or component switch.
- **Degradation stated** — `LogViewer.test.tsx` and `.a11y.test.tsx` assert the no-endpoint case renders the
  stated alert with `queryByRole('alert')` returning `null` (it's `severity="neutral"`, not `err`).
- **DEBUG confirmed and marked** — `LogViewer.test.tsx` proves `setLogLevel` is never called before the
  dialog is confirmed, and never called at all if cancelled; a non-DEBUG change calls it directly with no
  dialog. The "marked" half (the live `DEBUG · audited` chip) is browser-verified below, since it depends on
  the fixture actually round-tripping the level — see design decision 4.

### Browser verification

Dev server + Playwright, same setup as tasks 064/065. Confirmed, with screenshots and zero page errors:
streaming renders colour-coded `INFO`/`WARN` lines; selecting `DEBUG` opens the confirm dialog (its
high-consequence banner plus the specific audit sentence); confirming raises the level, auto-starts
streaming, shows the live `DEBUG · audited` chip, and produces both the header's persistent outcome badge
and a transient toast, each carrying the same server correlation id. This run is what surfaced the MSW
fixture gap (design decision 4) before it reached anyone else. Screenshots sent to the user; verification
scripts were scratch files, not committed.

## Status

DONE — implemented, tested (435/435 on the touched suite; full suite green, 237/237 files), lint-clean,
typecheck-clean, prettier-clean, production build succeeds, browser-verified including the fixture fix the
browser pass itself found. Not yet marked done in `task-list.md` or committed — pending user confirmation.
GitHub issue #82 (`FE-066 · Log viewer`) already exists — to be closed on commit.
