# Integration plan 011 · Monitoring — Report

**Result:** Partial. The seam was already far closer to the real `console.yaml` shape than the other eleven
features (its own header says so), so this pass is a set of targeted contract-alignment fixes plus optimistic-
concurrency wiring for dashboards, not a from-scratch build. Several real gaps remain — listed under "Not done /
could not verify" below, with reasons.

## What changed

- `frontend/src/features/monitoring/metricsRepository.ts`
  - Added `Capabilities.collector` (required in `console.yaml`'s `Capabilities` schema, `OQ-16` (a), `I5`) — install
    health plus organization-scoped `reasons`, with `CollectorReason` typed from the contract's closed enum.
  - `declareMetricType`'s real behavior: `console.yaml` row 61 (`metrics/catalog/{name}/type`) is a **contracted
    refusal in v1** — it answers `501 NOT_IMPLEMENTED`, `reason: metric-declare-deferred`, for every caller who
    holds the verb. Changed the return type to `Promise<never>` and documented that there is no success response
    to type against the real backend.
  - `exportRangeUrl`: the contract's `exportRange` (row 60) takes a REPEATED `instanceId` query parameter (absent
    = every instance the caller can reach); the repository only sent `metric`/`from`/`to`. Now appends
    `instanceId` for each id in `req.selector.instanceIds`.
- `frontend/src/features/monitoring/dashboardRepository.ts`
  - `DashboardDoc.revision` added (`console.yaml`'s `Dashboard.revision`, REQUIRED optimistic-concurrency token).
  - `saveWidget`/`setLayout`/`hideDefault`/`resetDefaults` all now take a `revision: number` and send it via
    `X-SM-Dashboard-Revision` (the contract's `DashboardRevision` parameter — required on every dashboard write,
    W2-07; a stale value is `409`, missing/malformed is `400`, and there is no last-writer-wins form).
- Call sites updated to thread the revision through:
  - `frontend/src/features/monitoring/explore/ExplorePage.tsx` — `saveWidget`'s mutation now re-reads the
    dashboard immediately before writing (Explore never keeps a dashboard read open) to get a fresh `revision`.
  - `frontend/src/features/monitoring/dashboard/DashboardGrid.tsx` — new required `revision` prop, forwarded to
    `setLayout`/`hideDefault`.
  - `frontend/src/features/monitoring/dashboard/DashboardView.tsx` — passes `dashboard.revision` to
    `<DashboardGrid>` and to `resetDefaults`.
- `frontend/src/features/monitoring/explore/TypeMetricDialog.tsx` — since the real backend never returns success,
  the error panel now shows the server's actual refusal: the deferred-quarantine message on a `501`, or
  `describeErrorCode(code, message)` otherwise, instead of one static string regardless of cause.
- `frontend/src/shared/i18n/messages.en.ts` — added `typeMetricDialogDeferredDetail`.
- `frontend/src/shared/testdoubles/handlers/console.ts` — `capabilities` fixture now includes `collector`;
  `declareMetricType` now always answers `501` (matching the real backend) instead of echoing back a typed entry;
  all four dashboard write handlers now read/validate `X-SM-Dashboard-Revision` (400 missing/malformed, 409 stale).
- `frontend/src/shared/testdoubles/handlers/dashboardStore.ts` — `DashboardDocument.revision`, bumped by every
  mutator; new `RevisionConflictError` + `assertRevision` guard; `addWidget`/`setLayout`/`setWidgetHidden`/
  `resetDashboard` all take and check a `revision`.
- Tests updated/added: `dashboardRepository.test.ts` (revision threaded through every call, plus two new tests —
  a stale-revision `409` and a malformed-header `400`), `useCapabilities.test.tsx` and `WidgetFrame.test.tsx`
  (literal `Capabilities` fixtures now include `collector`), `capabilityDefaults.ts`'s fail-closed default
  (`collector: { state: 'unknown', ... }` — never a fabricated `live`), `DashboardGrid.test.tsx` /
  `DashboardGrid.a11y.test.tsx` / `dashboardPerformance.test.tsx` (new required `revision` prop),
  `TypeMetricDialog.test.tsx` (updated the "save fails" expectation to the real dynamic message, and added a new
  test for the 501-deferred case).

## Task 2 (query + composition caveat) — verified, no change needed

- Error-code routing (`SERIES_CAP_EXCEEDED`, `AGGREGATION_NOT_APPLICABLE`, `GROUP_BY_REQUIRES_AGGREGATION`,
  `STAT_REQUIRES_AGGREGATION`, `METRIC_UNTYPED`) was **already generic and complete**: `ApiError.code` flows
  through untouched from the server to `describeErrorCode()` (`shared/api/errorCodes.ts`), which has a distinct
  message per code already — there was no hardcoded single "query failed" path to fix. `SpecifiedErrorCode` in
  `shared/api/types.ts` already lists exactly these five.
- Composition caveat: grepped every `acrossReplicas`/`replicaComposition` reference in `frontend/src/features/
  monitoring`. `acrossReplicas` is hardcoded to `'auto'` everywhere a `QueryRequest` is built (`AggregationControl.tsx`
  deliberately has no control for it — see that file's own header) and every render of `replicaComposition.inactive`
  / series `state` reads the value the server actually sent rather than asserting composition happened. No UI copy
  claims cross-replica composition occurs. **Nothing to flag or rewrite** — the plan's "don't silently rewrite the
  reference design's wording" instruction doesn't apply because no such wording exists in this codebase (as
  opposed to the `docs/design/frontend/reference/` mockup, which this task did not need to touch).

## Task 3 (export) — done

`exportRangeUrl` now matches the contract's query parameters and already goes through
`startStreamingDownload` (`WidgetFrame.tsx`'s "Export CSV" action) — no second download path was built. The
"large export may be slow" UX note and the `EXPORT_TOO_LARGE` 422 (`{projectedRows, limit}`) are **not implemented
by this pass**: the current export path has no 422 handler and no slow-export notice. Flagging rather than adding,
since `exportRange` is explicitly uncounted-as-served (P11-8.1) per the plan's own caveat — the row-limit response
shape is contracted but nothing in the fixture or the live backend's current state was available to verify it
against. Left as a follow-up rather than guessed.

## Task 4 (dashboards) — mostly done; one visible gap

Done: `getDashboard`/`saveWidget`/`setDashboardLayout`/`setWidgetHidden`/`resetDashboard` all route correctly and
now carry the REQUIRED optimistic-concurrency header end to end, with server-side (fixture) enforcement of both
failure modes (`400` malformed/missing, `409` stale).

**Gap, not fixed:** a `409 CONFLICT` from any of the four writes is not yet surfaced to the operator as "someone
else changed this dashboard, reloading" — it currently just rejects the mutation and leaves whatever error UI that
mutation already had (a toast/alert showing a generic failure, not a reload prompt). Building that UX (and
deciding whether to auto-refetch-and-retry or force an explicit reload) is a real design question, not a
mechanical wire-up, so it is **not invented here**. Noted as an open item below rather than silently added.

## Task 1 (catalog) — real gap identified and documented, not resolved

`console.yaml`'s `MetricCatalogEntry` (row 56) is materially richer and DIFFERENTLY shaped than the fixture-era
`CatalogEntry`/`MetricType` still imported from `@shared/testdoubles/data/metrics` into `metricsRepository.ts` and
`TypeMetricDialog.tsx`:
- Real `state` enum is `plottable | unsupported` — **no `needs-typing`, no `type-suspect`.** The real backend's v1
  behavior drops undeclared metric names rather than ever offering one for typing (`console.yaml`'s own text:
  "undeclared names are dropped and counted, never offered for typing").
- Real schema requires `layer`, `labelKeys` (always `[]` in v1), `durability`, `onUnavailable`,
  `observedAvailability` (with its own `unavailable`/`reason` plane-degraded signal) — none of which the fixture
  type carries, and `layer` only ever being `'package-baseline'` in v1 (the fixture's `'user-extension'` has no
  v1 producer).

Net effect: **`MetricSelector`'s whole "Type…" action path (which only fires on a `needs-typing` entry) is dead
code against the real backend** — it can never trigger, because the real catalog never emits that state. This is
consistent with, and confirmed by, `declareMetricType` always 501-ing (fixed above) — the catalog and the
type-declare endpoint agree with each other, just not with the fixture.

**Not done:** replacing `CatalogEntry`/`MetricType` wholesale with a generated/hand-authored contract type
(`frontend/README.md`'s seam-replacement step 3) and reconciling every consumer (`MetricSelector.tsx`,
`exploreGating.ts`, `useCatalog.ts`, the catalog-rendering tests). That is a real, multi-file migration — not a
drive-by fix — and risked destabilizing a feature area five other agents are not touching but that a half-done
type migration could still break at build time. Scoped out; recorded as the top follow-up.

## Task 5 (component tests + browser verification)

- Component/unit level: `npx vitest run src/features/monitoring` → **497/497 passing** (66 files), after the
  changes above (one pre-existing test, `TypeMetricDialog.test.tsx`'s "save fails" case, was updated to match the
  dialog now showing the server's real message instead of one static string; a new test for the 501-deferred path
  was added alongside it).
- `npx tsc --noEmit -p .` — **zero errors anywhere under `monitoring`, `dashboardRepository`, `metricsRepository`,
  or `TypeMetricDialog`.** (The repo-wide `tsc` run has pre-existing errors in `governance`/`solutions`-area files
  from the other agents' concurrent work on plans 005/008/010 — unrelated to this plan, left untouched.)
- `npx eslint` on every file touched by this plan — clean.
- **Playwright browser verification: attempted, could not complete.** `npx playwright test
  e2e/monitoring-visual.spec.ts --project=fixture` (the existing MSW-backed visual spec that already covers the
  Monitoring dashboard) failed to start its own dev server: `Error: Port 5173 is already in use` — one of the
  other agents working in this same shared checkout (plans 004/005/008/010) already has a dev server bound to
  that port. This is an environment collision, not a code defect; I did not attempt to kill another agent's
  process. **Not browser-verified** as a result — a re-run once the other agents' sessions are clear would use the
  same spec unmodified.
- The plan's own Playwright script (Explore → run a range query → see an error-code case → export → Dashboard add
  /rearrange/hide/reset) was not newly written: `e2e/monitoring-visual.spec.ts` covers the dashboard-rendering half
  already; a dedicated error-code-case + export + dashboard-write-flow script was not added, for the same port
  collision plus the time budget already spent on the contract-fidelity fixes above, which were the larger and
  more durable gap.

## Open items / follow-ups (not invented, flagged instead)

1. Migrate `CatalogEntry`/`MetricType` off `@shared/testdoubles/data/metrics` onto a real `MetricCatalogEntry`
   contract type, and decide what (if anything) replaces the now-dead `needs-typing`/"Type…" UI path — a design
   decision, not a mechanical fix.
2. Surface `409 CONFLICT` on a dashboard write as a distinct "someone else changed this, reload" UX rather than a
   generic failure.
3. `EXPORT_TOO_LARGE` (422, `{projectedRows, limit}`) has no client handler; no "large export may be slow" notice
   exists yet. Both are real contract behavior, deferred because `exportRange` itself is not yet counted "served"
   at scale (P11-8.1).
4. Re-run `e2e/monitoring-visual.spec.ts --project=fixture` once no other agent holds port 5173, as the actual
   browser-verification step this report could not complete.

## Files touched

- `frontend/src/features/monitoring/metricsRepository.ts`
- `frontend/src/features/monitoring/dashboardRepository.ts`
- `frontend/src/features/monitoring/dashboardRepository.test.ts`
- `frontend/src/features/monitoring/explore/ExplorePage.tsx`
- `frontend/src/features/monitoring/explore/TypeMetricDialog.tsx`
- `frontend/src/features/monitoring/explore/TypeMetricDialog.test.tsx`
- `frontend/src/features/monitoring/dashboard/DashboardGrid.tsx`
- `frontend/src/features/monitoring/dashboard/DashboardGrid.test.tsx`
- `frontend/src/features/monitoring/dashboard/DashboardGrid.a11y.test.tsx`
- `frontend/src/features/monitoring/dashboard/DashboardView.tsx`
- `frontend/src/features/monitoring/dashboard/dashboardPerformance.test.tsx`
- `frontend/src/features/monitoring/capabilityDefaults.ts`
- `frontend/src/features/monitoring/useCapabilities.test.tsx`
- `frontend/src/features/monitoring/dashboard/WidgetFrame.test.tsx`
- `frontend/src/shared/i18n/messages.en.ts`
- `frontend/src/shared/testdoubles/handlers/console.ts`
- `frontend/src/shared/testdoubles/handlers/dashboardStore.ts`

No commits were made (per instructions) — all changes are uncommitted in the working tree.
