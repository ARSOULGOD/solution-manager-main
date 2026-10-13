# FE-081 · TimeRangePicker and ResolutionControl — Report

## What I implemented

Refs Dec `DM-D10` `DM-D11` `SM-D31` `B/D15` 🔒 · `dynamic-metrics` §5.4. Deps `068, 032`.

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/explore/timeRangeOptions.ts` (new) | Pure `DM-D10` rule: `permittedPresets(tiers)` filters the candidate set `15m·1h·6h·24h·7d·30d·90d·6mo` to whatever the LONGEST advertised tier retention covers (`null` retention = unlimited); `'custom'` is never tier-gated. Against `FAIL_CLOSED_CAPABILITIES` (`P1D`) this yields exactly `15m/1h/6h/24h` — matching §17's own words ("presets to 24h") without a second hardcoded cutoff. `resolveTimeRange(value, now)` turns a preset or custom value into an absolute `{from, to}` UTC window; `now` is injectable for deterministic tests. |
| `frontend/src/features/monitoring/explore/timeRangeOptions.test.ts` (new) | Fail-closed tier → 24h cap; full fixture tiers (`P6M`/`P2Y`/null) → all 8 presets (proves `REQ-007`'s 6mo reach); longest-tier-wins regardless of array order; empty tiers → no presets; preset/custom resolution math; preset durations strictly increasing. |
| `frontend/src/features/monitoring/explore/TimeRangePicker.tsx` (new) | The result column's real range control. One `Select` (`@shared/ui`) bound to a preset key or `'custom'`, options built from `permittedPresets(capabilities.tiers)` plus a `'custom'` entry that's always offered. A stale preset no longer offered (a live capabilities downgrade) is auto-corrected forward via effect — same posture `AggregationControl`'s own `DM-D22` forcing takes, never left pointing at a dropped option. Switching to `'custom'` seeds its From/To from whatever window is currently resolved, not a jarring fixed default. Custom bounds are two native `datetime-local` inputs built directly on MUI's own `TextField` (the shared `TextField` wrapper's `type` is hardcoded `text`/`password`; widening it for one call site isn't warranted — same "bespoke MUI primitive" precedent `GroupByControl`/`InstanceSelector`'s `Meter` already set), converting local wall-clock to/from UTC ISO at the boundary. A caption below always renders the resolved window through `shared/format/time.ts` (task 007) with the zone spelled out (`B/D15`) — never a bare local time. |
| `frontend/src/features/monitoring/explore/{TimeRangePicker,ResolutionControl}.test.tsx`, `.a11y.test.tsx` (new) | Full-tier fixture offers all 8 presets + custom, in order; single-day tier caps at 24h + custom; a stale out-of-range preset is corrected to the longest still-permitted one; selecting custom reveals From/To; the caption always ends with the resolved zone label (computed via the same `timeZoneLabel()` helper, not a hardcoded assumption about the CI runner's zone); `disabled` disables the select. `ResolutionControl`: Raw omitted/offered by `forceRawAdvertised`; clicking Raw opens the confirm and does NOT call `onChange` until confirmed (verified via a spy, not by asserting hidden-modal DOM state — a real background-aria-hidden pitfall this task's own draft tests hit and fixed); cancelling never calls `onChange`; switching back to Auto commits immediately with no dialog; `disabled` disables every option. `jest-axe`, light+dark, across {preset, custom, disabled} / {Raw unavailable, Raw available, disabled}. |
| `frontend/src/features/monitoring/explore/ResolutionControl.tsx` (new) | The result column's real resolution control. `ToggleGroup` (`@shared/ui`) — `Auto` always, `Raw` only when `capabilities.features.forceRaw` is advertised. **Not** gated on the `metrics.forceRaw` permission: that verb is `proposed`, not `grantable` (`shared/authz/catalog.ts`), and `permissions.ts`'s C12 rule forbids gating a v1 feature on a non-`grantable` verb — same posture `designer/overlayRbac.ts` already takes for its own unratified verbs, with the same `TODO(verb)` marker for when it ratifies. Confirmed, not just gated: switching `auto → raw` opens a `HighConsequenceDialog`; `onChange` fires only on confirm, so the toggle never visually flips until the operator has seen the warning. Switching back to `auto` commits immediately — not high-consequence. |
| `frontend/src/features/monitoring/explore/ExplorePage.tsx` (modified) | Result column gains a toolbar row above the placeholder `Paper`: `<TimeRangePicker>` (bound to new `timeRange` state, default `{kind:'preset', preset:'24h'}`) and `<ResolutionControl>` (bound to new `resolution` state, default `'auto'`), reading `capabilities.tiers` / `capabilities.features.forceRaw` — neither hardcoded. Removed the stale header comment noting task 081 hadn't landed; added this task's own note. Both controls are independent of the five gated steps (component tree §9.1 places them as siblings of `FilterBar`, not inside it) — they render regardless of metric/instance selection state, matching the reference's own always-visible range/resolution toolbar. |
| `frontend/src/features/monitoring/explore/ExplorePage.test.tsx` (modified) | Added: the result column renders `TimeRangePicker` defaulted to "Last 24h" and `ResolutionControl` at Auto, neither gated behind metric selection; Raw becomes available once capabilities resolve (the fixture advertises `features.forceRaw: true`). No existing assertion needed to change. |
| `frontend/src/shared/i18n/messages.en.ts` (modified) | Added `timeRangePickerFieldLabel`, eight `timeRangePickerPreset*` keys (baked per key, mirroring the reference's own "Last …" text — same convention `AggregationControl` set for its own option set, not runtime interpolation), `…CustomLabel`, `…FromLabel`, `…ToLabel`; `resolutionControlAriaLabel`, `…AutoLabel`, `…RawLabel`, `…RawConfirmTitle`, `…RawConfirmBody`, `…RawConfirmAction`. |

## Design decisions, and why

1. **Presets are derived from the LONGEST tier's retention, not `capabilities.maxRange`.** The task text says "derived from `capabilities.tiers`" specifically, and `metricsRepository.ts`'s own comment already documents `maxRange` as *"the longest range any tier can serve"* — i.e. a value a correct tier-scan would reproduce anyway. Scanning `tiers` directly keeps the rule self-contained and testable without depending on the server also getting `maxRange` right, and it's what lets `permittedPresets([])` (no tiers at all) return nothing rather than silently trusting an absent `maxRange`.

2. **`'custom'` is never tier-gated.** No tier field bounds an operator's ability to *request* an absolute window — only the server's willingness to *serve* it. Task 082 (Explore result states, "Retention truncation") is where a request outside retention gets a real, informative response; gating `'custom'` itself here would just move that refusal earlier with worse copy.

3. **A stale preset is corrected forward, not left stranded or silently reset to a fixed default.** If capabilities degrade after `90d` was selected (or on first fail-closed render before the real `/capabilities` fetch resolves), `permittedPresets` may no longer include the current value. Correcting to the longest still-permitted preset (rather than always snapping to `24h`) preserves as much of the operator's intent as the platform can still serve — the same reasoning `AggregationControl`'s own effect already established for `DM-D22`'s forcing in this exact component family.

4. **`ResolutionControl` gates on the capability flag alone, never the RBAC verb.** `metrics.forceRaw` carries `highConsequence: true` in the catalog but is still `status: 'proposed'`. Per C12 (`permissions.ts`), gating a v1 feature on a non-`grantable` verb would deny every principal — no identity can hold a verb the catalog doesn't grant yet. `designer/overlayRbac.ts` already sets the precedent for this exact situation (a real UI feature, an unratified verb): render ungated, mark `TODO(verb)`. The confirm dialog supplies the actual "high-consequence" behaviour independent of RBAC status.

5. **The confirm dialog lives inside `ResolutionControl`, not lifted to `ExplorePage`.** Self-contained orchestration (a `useState` for the pending confirmation, gating `onChange`) keeps the "don't flip until confirmed" contract co-located with the control that owns it, rather than spreading a two-step commit protocol across the parent. `AggregationControl`'s own internal `useEffect` is the same call: real UI logic that never needs to leave the component it belongs to.

6. **Custom bounds use native `datetime-local`, not a new date-picker dependency.** No date-picker library exists in `package.json` yet and none of this task's deps (068, 032) suggest adding one. `datetime-local` reads/writes the browser's own local wall clock with no zone in the string — exactly matching `displayTimeZone`'s own "browser's resolved zone is the default" behaviour (`shared/format/time.ts`) — so the conversion at the component boundary is a straightforward `Date ⇄ ISO` round-trip, not a new formatting concern.

## Residuals (flagged, not silently decided)

1. **`RefreshControl` is out of scope for this task** — the component tree (§9.1) places it only on `<DashboardToolbar>`, and the task list's own title names only `TimeRangePicker`/`ResolutionControl`. Explore has no refresh control (matching the reference's own `explore.jsx`, which never builds one either).
2. **`timeRange`/`resolution` state is not yet wired into any query** — no `buildQuery`/`QueryRequest` call site exists in `ExplorePage` yet (same residual `AggregationControl`'s own `acrossReplicas: 'auto'` already carries). Both controls are real, tested, and browser-verified in isolation; connecting them to an actual `queryRange` call is a later task's job.
3. **No clamping of a custom range against `capabilities.maxRange`.** The acceptance box for this task doesn't ask for it, and the server-side truncation/422 path (task 082) is the spec's own answer for a request outside retention — clamping here would duplicate that logic ahead of the component that's actually specified to render it.

## Testing

Every command run from `frontend/`:

```
npm run typecheck   # tsc -p tsconfig.json && tsc -p tsconfig.node.json — clean
npm run lint        # eslint . --max-warnings=0 — clean
npx vitest run src/features/monitoring/explore/   # 22 files, 154/154
npx vitest run                                    # full suite: 278 files, 1669/1669
```

**Browser-verified**, not just tested — a throwaway Playwright script (discarded, not committed) drove
`npm run dev -- --port 5173 --strictPort` against `/solutions/cache/instances/cache-prod-01/monitoring/explore`:

- Time range select opened: exactly `Last 15m / 1h / 6h / 24h / 7d / 30d / 90d / 6mo / Custom range`, in order —
  the fixture's `/capabilities` route advertises the full tier set (`P6M`/`P2Y`/null), so all eight presets plus
  custom render, matching `REQ-007`'s 6-month reach.
- Default render: `Time range` shows "Last 24h"; caption below reads
  `Aug 30, 2026, 8:28:17 PM – Aug 31, 2026, 8:28:17 PM GMT+5:30` — a real resolved window with the runner's own
  zone spelled out, not a hardcoded example.
- Selecting "Custom range" revealed both `From`/`To` `datetime-local` inputs, seeded from the previously-resolved
  24h window.
- `Auto`/`Raw` toggle rendered both options (fixture's `features.forceRaw: true`). Clicking `Raw` opened
  "Switch to Raw resolution?" with the stated consequence copy; clicking "Switch to Raw" closed the dialog and
  set `Raw`'s `aria-pressed` to `true` — confirmed via automation, not just visually.
- `console --errors` equivalent (`page.on('console'/'pageerror')` listener): zero errors across the whole run —
  only pre-existing, unrelated font-decode warnings from the headless Chromium runner.

Two real defects were caught and fixed only by driving the actual dialog transition (not visible from a static
read of the component): asserting `aria-pressed` on the *background* toggle button while the modal was still
open threw `TestingLibraryElementError` — MUI's `Modal` sets `aria-hidden` on the rest of the page while a
dialog is open, so those assertions were rewritten to spy on `onChange` and check dialog presence instead of
querying background DOM mid-transition. The "renders the resolved window with a labelled zone" test also
initially asserted the caption ends in `[A-Z]{2,5}` — true for `UTC`/`PDT`/etc. but not for a `GMT+5:30`-style
offset zone (this machine's own), so it now compares against `timeZoneLabel()`'s own real output instead of a
guessed pattern.
