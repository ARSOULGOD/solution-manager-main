# FE-044 · Instance context header and category tabs — Report

## What I implemented

The shared instance-context shell: header (name/env/health/version-drift/outcome) + Designer | Governance |
Monitoring tabs (task spec: `docs/plans/frontend/implementation.md` §FE-044; refs Design §9/§14, UI §A.7 —
`docs/design/frontend/reference/app/instance.jsx` is the concrete reference, since no separate §A.7 file
exists in-tree). Real category *content* (Designer/Governance/Monitoring bodies) is Phase 4/5/6 — out of
scope here; each category renders a stated placeholder.

| File | Summary |
|---|---|
| `src/features/instance/InstanceHeader.tsx` | New. Name, `EnvChip`, health `StateChip` (auto-carries the "not-declared" info tip via `healthVocab.not_applicable.tip` — no new code needed, `StateChip` already renders a vocab entry's `tip`), a `Version drift · Designer read-only` badge when `version.state` is `drift`/`unknown` (`UI-D12`) with the specific reason behind an `InfoTip`, the "Governance and Monitoring still work" note, the persistent `OutcomeBadge` (via the new `useOutcome` hook), and `RouteTabs` (task 018) wrapping the category body. |
| `src/features/instance/InstanceShell.tsx` | New. Fetches the instance (`InstancesRepository.get`, already existed) via `useQuery`/`queryKeys.instances.detail`; loading skeleton / error+retry / loaded states, same shape as `InstancesPage.tsx`. Renders a stated placeholder for the category body — Designer/Governance/Monitoring content is later phases. |
| `src/features/instance/index.ts` | Public barrel: `InstanceHeader`, `InstanceShell`, `InstanceCategory`. |
| `src/features/inventory/index.ts` | **New top-level barrel**, `export * from './instances'`. Required because `inventory` is a multi-module feature (`instances/`, `solutions/`) with no top-level `index.ts` — the boundaries policy's cross-feature exception only recognises a feature's *top-level* barrel, so `features/instance` reusing `InstancesRepository`/`createInstancesRepository` needed this to exist. |
| `src/shared/ui/Toaster/outcomeStore.ts` | New. The persistence half of FE-021's own stated contract ("acknowledged by a toast and recorded on the resource") that task 021 built the two presentational pieces for (`Toaster`, `OutcomeBadge`) but never wired together. `pushOutcome(resourceId, input)` is the one call a future mutation (Designer save, apply, restart) makes for both — it fires the transient toast *and* records the per-resource outcome a `useOutcome(resourceId)` selector reads. In-memory (Zustand), not `localStorage` like the reference prototype — the store surviving a route change within the same SPA session is what the acceptance criterion asks for; this codebase has no other precedent for persisting UI record state to `localStorage` (`localSessionAdapter.ts`'s own header argues against it), so a plain store matching `toastStore`'s own pattern was the natural fit, not a deviation to defend. |
| `src/shared/ui/Toaster/{index.ts,Toaster.test.tsx}`, `src/shared/ui/index.ts` | Barrel exports for `pushOutcome`/`useOutcome`/`useOutcomeStore`; a new test asserting `pushOutcome` records the outcome **and** fires exactly one toast. |
| `src/app/routes/InstanceCategoryRoute.tsx` | Replaced the `RouteStub` placeholder with real DI-and-render, same pattern `InstancesRoute.tsx` documents (`RuntimeConfig` is app-layer; a feature can't reach it). `:category` is still validated here before anything renders. |
| `src/app/router.test.tsx` | This is the *first* deep-rendered route in this file that calls `useQuery` — added `QueryClientProvider` to `renderAt`'s tree and a fixture DP response for `*/dp/instances/*`, since the file's own hand-rolled `fetch` stub previously only handled `config.json` and threw on anything else. Updated the one assertion that depended on the old stub's literal `Instance · designer` title text. |
| `messages.en.ts` | 9 new keys (`instanceTab*`, `instanceVersionBlocked*`, `instanceErrorTitle`, `instance*Placeholder`). |

## Rulings

1. **`healthVocab`/`versionVocab`'s existing `.tip` fields already satisfy two of the task's stated
   requirements with zero new copy.** "Info tip when health is not-declared" is `StateChip` rendering
   `healthVocab.not_applicable.tip` (task 013's own mechanism) — I didn't special-case "not-declared"
   separately. The blocked-badge's specific reason (drift vs unknown) rides the same `versionVocab[state].tip`
   behind a plain `InfoTip`, rather than inventing new wording that would drift from the vocab module's.
2. **`inventory` needed a top-level barrel it didn't have.** `eslint-plugin-boundaries`' cross-feature
   exception is keyed to a feature's *top-level* `index.ts` (its own comment: "ANY feature's public barrel
   only") — `inventory/instances/index.ts` doesn't qualify because the captured feature name from
   `src/features/(*)/**` is `inventory`, not `inventory/instances`. Rather than duplicate
   `InstancesRepository`'s type locally in `features/instance` (the `instancesHref`-style workaround
   `columns.tsx` documents for the app-layer case), I added the missing barrel — this is what the policy's
   own comment says should exist for legitimate reuse, and `inventory` gains nothing by *not* having one.
3. **No `category` prop on `InstanceHeader`.** `RouteTabs` already derives the active tab from
   `useLocation()` — passing `category` down again would be a second source of truth for the same fact.
   `InstanceShell` still takes `category` (it has to, to pick the placeholder body), but the header itself
   doesn't need it.
4. **`InstanceHeader` doesn't render its own `<main>` landmark.** `AppShell.tsx` already renders
   `<main id="main">` around every route's content and manages scroll/focus on navigation — `InstancesPage.tsx`
   and every other real (non-`RouteStub`) page follow that; only the yet-to-be-replaced `RouteStub` routes
   still carry their own (differently-`id`'d, `main-content` vs `main`) landmark, a pre-existing quirk of
   the placeholder, not a pattern to extend.
5. **`router.test.tsx`'s pre-existing `:category` deep-link tests broke, not from a regression, but because
   they were asserting against `RouteStub`'s placeholder text.** `InstanceCategoryRoute` now does real data
   fetching, so the test file needed a `QueryClientProvider` (this is the first query-backed route it
   deep-renders — `/login` and `/dev/gallery` are the only other cases, and neither calls `useQuery`) and a
   fixture response for the instance-detail fetch. Fixed rather than routed around, since the alternative
   (reverting to `RouteStub` semantics to dodge the test) would ship a fake shell.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc --noEmit    # clean
npm run lint         # clean (0 warnings, --max-warnings=0)
npx vitest run       # 140 files, 627 tests — 625 passed; 2 failures on the full run were the DataTable
                       # Columns-menu axe scan and an InstancesPage selection test, both 5000ms timeouts
                       # under full-suite parallel load (the exact class FE-042's own report already
                       # documented for the former); both pass cleanly re-run in isolation
                       # (`npx vitest run <these two files>` → 18/18 passed) — not caused by this task.
npm run build         # production build succeeds, same pre-existing layout-chunk size note
```

Task-specific acceptance items:

- **Blocked Designer does not read as a dead instance** — `InstanceHeader.test.tsx`: a `drift`-state
  instance renders the `Version drift · Designer read-only` badge *and* the "Governance and Monitoring
  still work — two of three categories remain available…" line; a healthy instance renders neither.
  Visually confirmed in a real browser (`cache-prod-02`) against `docs/design/frontend/reference/app/instance.jsx`'s
  `ihead`/`blocked` structure — badge, note and the tabs all render, none disabled.
- **Outcome persists on the resource** — `InstanceShell.test.tsx`'s dedicated test: `pushOutcome` for an
  instance id, render `InstanceShell` at `category="designer"`, confirm the badge shows, `rerender(...)`
  with `category="monitoring"` (simulating the route change a tab click causes) on the *same* provider tree
  (same `QueryClient`), and assert the badge — title, severity and correlation id — is still there. Also
  browser-verified: clicked the Governance tab on a running instance, URL and active tab changed, header
  (including where an outcome badge would sit) persisted unchanged.
- **`Toaster.test.tsx`'s new `pushOutcome` case** confirms the single call records the per-resource outcome
  *and* fires exactly one toast — the "acknowledged AND recorded, one call" contract task 021 stated but
  didn't wire.
- **a11y** — `InstanceHeader.a11y.test.tsx` (healthy; blocked-with-outcome-badge) and
  `InstanceShell.a11y.test.tsx` (loaded state), light/dark, axe clean.
- **Visually verified against the reference** — dev server + MSW, real Chromium: healthy instance
  (`cache-prod-01/designer`), blocked instance (`cache-prod-02/designer`), and a live tab click to
  Governance — 0 console errors in all three, layout/copy matching `app/instance.jsx`'s `InstanceHeader`.

## Status

DONE — implemented, tested (625/627 on the full run, the 2 failures being pre-existing full-suite-load
flakes unrelated to this task and confirmed passing in isolation), lint-clean, typecheck-clean, production
build succeeds, visually verified in a real browser against the reference. Not yet closed on GitHub —
pending review, particularly Ruling 2 (`features/inventory/index.ts`, since it changes what's importable
from the `inventory` feature going forward) and Ruling 5 (`router.test.tsx` provider-tree change, which
affects any future route deep-link test added to that file).
