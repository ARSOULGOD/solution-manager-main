# FE-088 · CSV export — Report

## What I implemented

Refs Dec `DM-D14` `DM-D18` · Design §13, §15 · `dynamic-metrics` §5.8. Deps `035, 085` (both done — `086` also
done, which is what actually gave this task somewhere to attach: the widget menu).

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/dashboard/WidgetFrame.tsx` (modified) | Adds an **Export CSV** entry to the per-widget menu (`dynamic-metrics/screens/console.html`'s own step 8 names this exact action). Gated on `capabilities.features.streamingExport` alone — **never** the `metrics.export` verb, which is `proposed` not `grantable` in `shared/authz/catalog.ts`; `permissions.ts`'s C12 rule forbids gating a v1 feature on a non-grantable verb, so gating on the real capability flag is the same posture `ResolutionControl` (task 081) already established for `forceRaw`. Also omitted for a `too-new` (`schemaVersion` ahead of the console) document, which has no query to export. Clicking the entry opens a `HighConsequenceDialog` (design §15 names streaming export among the high-consequence actions); only on confirm does it build the export request — via the same `buildQuery()` the live query already uses, against the widget's own `query` and the **dashboard's** `absoluteRange`/`resolution` props (never `widget.range`/`widget.resolution`, `DM-D21`) — and hand `metricsRepository.exportRangeUrl(request)`'s URL to `startStreamingDownload` (task 035): a same-origin, cookie-carrying, hidden-iframe navigation that never buffers the body. |
| `frontend/src/features/monitoring/dashboard/WidgetFrame.test.tsx` (modified) | Three new cases: Export CSV offered + confirmed + a real network request lands at `/export/range?metric=...` (asserted via a captured `<iframe src>`, not a mock — same style `download.test.ts` already uses); omitted entirely when the fixture backend's `/capabilities` response sets `streamingExport: false`; omitted for a `schemaVersion+1` document. |
| `frontend/src/features/monitoring/dashboard/WidgetFrame.a11y.test.tsx` (modified) | Extended the existing "open menu" case to also find the Export CSV item; added a new case for the confirm dialog itself. Both clean in light/dark. |
| `frontend/src/shared/i18n/messages.en.ts` (modified) | Added `widgetFrameMenuExportCsv`/`widgetFrameExportConfirmTitle`/`widgetFrameExportConfirmBody`/`widgetFrameExportConfirmAction` (4 keys). The confirm action is labelled **"Start export"**, deliberately distinct from the menu item's "Export CSV" label — same reason `ResolutionControl`'s own `resolutionControlRawConfirmAction` ("Switch to Raw") differs from its trigger's label ("Raw"): an identical label on both the menu item and the dialog's confirm button would collide under `getByRole('button', { name: … })` during the dialog's close transition. |
| `docs/plans/frontend/task-list.md`, `docs/plans/frontend/execution.md` (modified) | Marked `088` done. |

## What I did **not** need to build

The two seams this task depends on already existed, built ahead of time by their owning tasks:

- **`metricsRepository.exportRangeUrl(req)`** (task 033) — already resolves `GET {consoleBase}/export/range?metric=…&from=…&to=…`, same-origin, no request issued by the function itself (`metricsRepository.test.ts` already proved this).
- **`startStreamingDownload(url)`** (task 035) — already implements the hidden-iframe, never-`fetch()`, never-buffered navigation, with its own full test suite (`download.test.ts`).

This task's own job was narrower than the brief's headline might suggest: wire an existing, tested download mechanism to an existing, tested URL builder, from a UI affordance (the widget menu) that did not exist until task 086/087 landed. No new HTTP client code, no new streaming logic.

## Design decisions, and why

1. **Gated on the capability flag, never the RBAC verb — full parity with `ResolutionControl`'s own precedent.** `metrics.export` is `status: 'proposed'` in the catalog (`shared/authz/catalog.ts`), not `grantable`. C12's rule (`permissions.ts`'s own enforced signature, `requireGrantable`) forbids gating a v1 feature on a non-grantable verb, because doing so would deny every principal — no identity can hold a verb the catalog doesn't grant yet. `capabilities.features.streamingExport` is the real, backend-advertised signal for whether export is available at all; gating on it (never hardcoded `true`) is the same "control omitted" posture design §15's RBAC table describes for an absent verb, applied to the capability that actually governs this feature today. A `TODO(verb)` comment in the component's own header marks where a `Can` wrapper belongs once `metrics.export` is ratified `grantable` — the gate on the capability stays either way.

2. **The export request is built from the exact same inputs the widget's own live query already uses** — `buildQuery(migration.config.query, { range: absoluteRange, resolution }, { features: { forceRaw } })` — rather than a separate export-specific request shape. This is deliberately "export what you see": the CSV covers the same metric, selector, aggregation, range and resolution currently rendered on screen, with no separate export-only filter UI to build or spec. `exportRangeUrl()` itself only reads `metric`/`range` off the built request today (its own header already states this — the fuller selector/aggregation fields exist on the request for parity with `queryRange`'s shape but are not yet part of the wire contract, since `dynamic-metrics` §5.8 specifies only the route and response type, not a request body shape). Nothing here changes that upstream fact; it is simply reused as-is.

3. **Confirmed via `HighConsequenceDialog`, gated by neither `disabled` nor a second click debounce.** `startStreamingDownload` is a synchronous, fire-and-forget iframe navigation — there is no promise to await and no loading state to represent between confirm and the browser's own download starting. The dialog closes immediately on confirm (`setExportConfirming(false)`), matching `ResolutionControl`'s own confirm-then-commit shape.

4. **Omitted (not disabled) for a `too-new` widget.** A `too-new` document renders read-only and whole with no query ever built (`migrateWidgetDocument`, task 084) — there is no request to export. A disabled menu item with no visible reason would be confusing; omitting it is consistent with how this same file already treats "Hide (keep default)" for a `shared`-layer widget (never offered, not disabled).

## Residuals (flagged, not silently decided)

1. **The export request body/wire contract for `POST /export/range` (per `dynamic-metrics` §5.8's own route table) vs. the `GET …?metric=&from=&to=` this console actually issues remains `metricsRepository.ts`'s own residual, not new to this task.** A same-origin navigation-style download cannot carry a POST body, so `exportRangeUrl()` (task 033) already encodes only `metric`/`from`/`to` as query params — this task did not expand that, since the fuller selector/aggregation/seriesBy fields have no stated wire shape for a GET request anywhere in the spec (§16 open items already track the export/PNG contract gaps generally). If the real backend needs `seriesBy`/`aggregation`/`groupBy` on the export request too, that is a `metricsRepository.ts`/contract change, not a UI change — this task's job was wiring the existing seam, not inventing a new one.
2. **PNG export does not exist anywhere in this component**, consistent with `DM-D14`: `features.pngExport` is never read here, and no affordance references it.

## Testing

Every command run from `frontend/`:

```
npm run typecheck   # tsc -p tsconfig.json && tsc -p tsconfig.node.json — clean
npm run lint        # eslint . --max-warnings=0 — clean
npm run build       # vite build + assert-echarts-chunk — clean, echarts still isolated to its own chunk
npx vitest run src/features/monitoring/dashboard/WidgetFrame.test.tsx        # 16/16
npx vitest run src/features/monitoring/dashboard/WidgetFrame.a11y.test.tsx   # 7/7
npx vitest run src/features/monitoring/                                     # 61 files, 435/435
npx vitest run                                                              # full suite — 300 files, 1849/1850
```

**Full-suite note.** One failure, in `src/features/inventory/instances/InstancesPage.test.tsx` — a `waitFor`
timeout on a dialog closing, in a file this task never touches. Run in isolation
(`npx vitest run src/features/inventory/instances/InstancesPage.test.tsx`), it passes 29/29. This is the same
pre-existing full-suite timing flake FE-086's own report already documented and attributed to resource
contention under the full run, not a regression from this task.

**Browser-verified**, not just tested — a throwaway Playwright script (discarded, not committed) drove
`npm run dev -- --port 5199 --strictPort` against the real msw fixture backend end to end:

- `/solutions/cache/instances/cache-prod-01/monitoring` renders two widgets, each with a working menu.
- Opening the first widget's menu shows a visible **Export CSV** item.
- Clicking it opens the confirm dialog ("Export widget as CSV?").
- Clicking **Start export** fires a real network request:
  `GET http://localhost:5199/api/console/v1/export/range?metric=cache.hit_rate&from=2026-08-31T09:01:45.495Z&to=2026-09-01T09:01:45.495Z`
  — same-origin, correct metric, and a 24h absolute range matching the dashboard's default window.
- Console/page-error listener: empty — no errors across the run.

## Status

Done. Commit: `e6311df`.
