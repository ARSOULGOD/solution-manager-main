# FE-083 · Explore draft persistence — Report

## What I implemented

Refs Dec `FE-D21` `DM-D9` `DM-D19` · Design §6.2, §26 (self-pass row "Incident URL"). Deps `077`.

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/explore/exploreDraftStore.ts` (new) | The sessionStorage half of the draft. `ExploreDraftDocument` (`metric`, `seriesBy`, `acrossInstances`, `groupBy`, `resolution`) — everything `ExplorePage` doesn't already keep in the URL. `generateExploreDraftId()` mints a 12-hex-char id (`crypto.randomUUID`, sliced, with a `Math.random` fallback). `saveExploreDraft`/`loadExploreDraft`/`discardExploreDraft`, keyed `smc.exploreDraft.<id>` in `sessionStorage`. Every read is revalidated field-by-field (`isExploreDraftDocument`) — a hand-edited, truncated, or stale-build entry is treated as "no draft", never thrown into a render, matching `app/dev/devPackageStore.ts`'s own posture for untrusted storage. The module header calls out that `useIdleTimer.ts`'s `saveDraft`/`loadDraft` family is a **different** mechanism for **`FE-D25`** (Designer drafts surviving an idle lock), whose own header says "Designer drafts ONLY. Never metrics, Explore, or anything else" — this is a deliberate sibling, not a reuse, with its own key prefix. |
| `frontend/src/features/monitoring/explore/exploreDraftStore.test.ts` (new) | Id generation (length, distinctness); round-trip; two ids stay independent; re-saving overwrites; six revalidation cases (unparseable JSON, non-object, each of the four typed fields hand-corrupted) all fall back to `null` rather than throwing; a `null` metric round-trips (no metric chosen yet); discard removes an entry and no-ops on a never-saved id. |
| `frontend/src/features/monitoring/explore/timeRangeOptions.ts` (modified) | Added `isTimeRangePresetKey()` — a type guard for a value read off the URL (untrusted), reusing the same `PRESET_ORDER` this file already treats as the one source of truth for the preset set, rather than a second hardcoded list in `ExplorePage.tsx`. |
| `frontend/src/features/monitoring/explore/ExplorePage.tsx` (modified) | Split the page's draft state across two lifetimes instead of one flat `useState` block. **URL** (`useSearchParams`, same `?sel=`/replace pattern `InstancesPage.tsx` already set): selected instance ids (`?sel=`) and time range (`?range=` + `?from=`/`?to=` for custom) — reload-safe and, within this tab, link-safe. **`?draft=<id>`** is minted once per visit (lazy `useState`, so a re-render never re-mints it) and written into the URL the first time it's missing, so a reload keeps the SAME id. **sessionStorage**, keyed by that id: metric, seriesBy, aggregation, group by, resolution — hydrated once on mount from `loadExploreDraft`, persisted on every change via one `useEffect`, and discarded on unmount via another (`DM-D9` — nothing here can save it yet; that's task 084). Wired `InstanceSelector`'s and `TimeRangePicker`'s `onChange` through the new URL-writing handlers. Added the stated sharing-limitation caption (`FE-D21`, R1 finding 3) right under the page title, ahead of every control step. |
| `frontend/src/features/monitoring/explore/ExplorePage.test.tsx` (modified) | Extended `renderExplorePage` with optional `route`/`onLocation` (the latter mounts a `LocationProbe` sibling that reads `useLocation().search` directly, since these tests don't route through a `<Routes>` table). New `describe` block: the sharing note renders; `?draft=` is minted on mount; instance selection lands in `?sel=` while `metric=`/`acrossInstances=`/`groupBy=` never appear in the URL (the "no widget JSON in the URL" acceptance line, checked directly rather than trusted from code structure); a draft seeded via `saveExploreDraft` and referenced by `?draft=` on mount restores the metric radio and the aggregation select; the sessionStorage entry is read straight after selecting a metric, then confirmed gone after `unmount()`. |
| `docs/plans/frontend/task-list.md`, `docs/plans/frontend/execution.md` (modified) | Marked `083` done. |

## Design decisions, and why

1. **Two lifetimes, not one.** `design.md` §6.2's ownership table is explicit that range, instance selection and (for a dashboard) the dashboard id are URL-owned, and that everything else in the Explore document is `sessionStorage`-or-server (`FE-D21`). Splitting `ExplorePage`'s state along that line — rather than, say, putting the whole draft in one JSON blob under one storage key referenced by the URL — is what keeps the URL itself reload-safe and shareable-within-tab even before `?draft=` resolves to anything, and is what makes the "no widget JSON in the URL" acceptance line true by construction rather than by convention.

2. **The draft id is minted client-side and lazily, not server-issued.** `FE-D21`'s own text allows "sessionStorage keyed by a short id **or** a server-side draft (preferred once the API exists)". No server draft route exists yet (this task's deps are `077` only — no backend task), so a client-minted id is what task 083 can actually build; `DM-D19`'s Phase 2 share-by-URL mints a **server** id instead, which is a different, later mechanism this task does not anticipate or stub.

3. **`exploreDraftStore.ts` is a new module, not an extension of `useIdleTimer.ts`'s draft family.** That file's own header is explicit and deliberate: *"Designer drafts ONLY. Never metrics, Explore, or anything else"* (`FE-D25`). Reusing its `saveDraft`/`loadDraft`/`clearDraft` for Explore would silently widen a scope that decision fixed on purpose — a new module with its own `smc.exploreDraft.` prefix keeps the two mechanisms (and the two decisions behind them) from ever colliding on a key.

4. **The sharing limitation is stated copy, not a non-functional "Share" button.** The task text is explicit: *"Offer 'Save as widget to share this' rather than a share affordance that silently does not work."* No save flow exists yet (task 084), so building a "Share" control now would be exactly the silently-broken affordance the task warns against. A `Typography variant="caption"` note — the same pattern `TypeMetricDialog`'s (task 079) `typeMetricDialogNoSuggestionNote` already set for a different missing-capability residual — states the limitation up front without inventing a control that has nothing to do yet.

5. **Discard-on-unmount is unconditional, not gated on a "saved" flag.** `DM-D9` and design §6.2 both say the draft is "discarded on navigate-away **unless saved**". Task 084 (Save) doesn't exist yet, so there is no "saved" state to gate on — the honest implementation for what exists today discards every time, and a comment at the effect says so rather than stubbing an unused parameter for a flow that isn't built. Task 084 will need to suppress this discard after a successful save; that's its own change, not invented here.

6. **A reload never reaches the discard path, by construction, not by a special case.** React's cleanup effects only run on an actual component unmount (SPA navigation within the app). A browser reload tears the whole page down without running React's cleanup at all, and the URL's own `?draft=<id>` plus the sessionStorage entry (which a reload does not clear — only a tab close does) are what let the next mount rehydrate the same draft. No `beforeunload` handling was added; none is needed for this.

## Residuals (flagged, not silently decided)

1. **The custom time-range bounds (`?from=`/`?to=`) travel in the URL alongside the preset.** Design §6.2's table names "range preset" specifically; it says nothing about a custom absolute window. Putting `from`/`to` in the URL too (rather than folding them into the sessionStorage document) keeps `FE-D6`'s reload-safety promise consistent regardless of which range mode is active, and it's two ISO timestamps, not the kilobytes-of-JSON concern `FE-D21`'s own rationale is about — but this is this task's own extension of the table, not something the table states directly.
2. **StrictMode's dev-only double-invoke of effects** (mount → cleanup → remount, only in `import.meta.env.DEV`) means the discard effect's cleanup fires and the persist effect's setup re-fires within the same synchronous pass on first mount. Verified harmless (the persisted value is identical both times) but not exercised by an automated test — the real unmount-discard path is (`ExplorePage.test.tsx`'s new suite), and the reload-restore path is verified live in the browser-verified section below.

## Testing

Every command run from `frontend/`:

```
npm run typecheck   # tsc -p tsconfig.json && tsc -p tsconfig.node.json — clean
npm run lint        # eslint . --max-warnings=0 — clean
npm run build        # vite build + assert-echarts-chunk — clean, echarts still isolated to its own chunk
npx vitest run src/features/monitoring/explore/   # 26 files, 204/204
npx vitest run                                    # full suite: 282 files, 1719/1719
```

**Browser-verified**, not just tested — a throwaway Playwright script (discarded, not committed) drove
`npm run dev -- --port 5183 --strictPort` against `/solutions/cache/instances/cache-prod-01/monitoring/explore`,
using the real msw fixture backend end to end:

- On load: a `?draft=<12-hex-char id>` appears in the URL, and the sharing-limitation note is visible.
- Selecting `cache.hit_rate`, an instance, `sum` aggregation and the `region` group-by: the URL becomes
  `?draft=<id>&sel=cache-prod-01` — `sel=` present, `metric=`/`acrossInstances=`/`groupBy=` all absent — and
  `sessionStorage['smc.exploreDraft.<id>']` holds exactly `{"metric":"cache.hit_rate","seriesBy":"instance",
  "acrossInstances":"sum","groupBy":["region"],"resolution":"auto"}`.
- Reloading the page: the same `?draft=<id>` survives, the metric radio is still checked, and the aggregation
  select still reads "Across instances: sum" — the sessionStorage half restored the document the URL alone
  doesn't carry.
- Clicking "Back to Monitoring": once the navigated page settles, `sessionStorage['smc.exploreDraft.<id>']` reads
  `null` — the draft was discarded on navigate-away, per `DM-D9`.
- Console/page-error listener: the only entries were the browser's own informational 422 logs from the
  deliberately-triggered `AGGREGATION_NOT_APPLICABLE` refusal (one instance + `sum` aggregation) — expected noise
  from a real non-2xx fetch, not an application error, same as task 082's own browser verification.
