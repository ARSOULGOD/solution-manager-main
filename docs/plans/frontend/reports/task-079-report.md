# FE-079 · MetricSelector, `needs-typing`, type-metric dialog — Report

## What I implemented

Refs Dec `DM-D23` `SM-D30` `UI-D5` · `dynamic-metrics` §5.5, §11.2 · UI §A.10 (type-metric dialog). Deps `069, 077`.

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/explore/MetricSelector.tsx` (new) | Step 1's real control. Single-select over the catalog, built from bare `Radio`s under one `RadioGroup` context rather than `FormControlLabel` (a `needs-typing` row needs its own independently-clickable `Type…` button beside the radio — nesting a `<button>` inside `FormControlLabel`'s `<label>` is invalid, interactive-in-interactive HTML). Each row shows the metric name (truncating, not wrapping, in the 340px sidebar), a layer chip (`pkg`/`ext`) and a state chip (`plottable`/`needs typing`/`type-suspect`, reusing the existing `ok`/`warn` tones — no invented colour, same posture `chartStateTags.ts` documents). `needs-typing` rows are `disabled` on the `Radio` itself. A selected `type-suspect` entry renders a persistent warning `Alert` with its `suspectReason`, never auto-corrected. A selected typed entry shows a `type X · unit Y` caption. Below the list, copy states the single-select-replaces rule plainly (`DM-D23`). Owns the type-metric dialog's mutation itself: opening it, running `declareMetricType`, invalidating the catalog query (`queryKeys.metrics.catalog()`) so the entry's state flips from the server rather than being patched by guesswork, and auto-selecting the newly-typed metric (matching the reference's own `setMetricName(typing.name)` after a successful type). |
| `frontend/src/features/monitoring/explore/TypeMetricDialog.tsx` (new) | The typing dialog. States plainly that inferring a type is forbidden; collects `metricType ∈ counter\|gauge\|histogram` via a native `RadioGroup` and a free-text `unit` via `TextField`; Save is disabled until a type is chosen. States the no-suggested-type limitation explicitly (§16 Q14 — the catalog entry carries neither `firstSeen` nor a suggestion field). `metricType`/`unit` are controlled props (owned by `MetricSelector`), not internal state — avoids a stale-field-on-reopen bug a self-contained `useState` would need a `key` remount or a `useEffect` reset to avoid. `repository` is a prop, not `useMetrics()` read internally, the same shape `RollbackDialog`/`RestoreRuntimeDialog` (designer, tasks 059/062) use for a dialog that owns one mutation — keeps it testable with a stub repository and no provider tree. |
| `frontend/src/features/monitoring/explore/MetricSelector.test.tsx`, `.a11y.test.tsx` (new) | Every catalog entry renders with its layer/state chips; `needs-typing` is a genuinely disabled control (not styled-only — `user-event` refuses to even click it, `pointer-events: none`); single-select replaces rather than adds; the type-suspect warning shows only for a type-suspect selection; the typed caption; the `Type…` affordance exists only on `needs-typing` rows; opening the dialog states both the forbidden-inference and no-suggestion copy; a full type → save round-trip calls `declareMetricType` with the right args and then auto-selects the result; Save stays disabled until a type is picked; Cancel never calls `declareMetricType`. `jest-axe` with nothing selected and with a type-suspect selection, both colour modes. |
| `frontend/src/features/monitoring/explore/TypeMetricDialog.test.tsx`, `.a11y.test.tsx` (new) | Direct component tests via controlled props: title names the metric; forbidden/no-suggestion copy; exactly the three contract metric types offered; Save gating; unit is a plain text input with no vocabulary; a successful save calls `declareMetricType(name, type, unit)` then `onTyped` with the repository's response; a failed save shows an error and never calls `onTyped`; Cancel calls `onClose` without ever calling `declareMetricType`. `jest-axe` with nothing chosen and with a type chosen, both colour modes. |
| `frontend/src/features/monitoring/explore/ExplorePage.tsx` (modified) | Step 1's placeholder becomes a wired `MetricSelector` (catalog from `useMetricCatalog()`, `metricsRepository` from `useMetrics()`). `metricState` for gating now comes from the catalog entry matching whichever metric is selected — no longer a hardcoded `null` — so steps 2-4 unlock as soon as a plottable or type-suspect metric is chosen; a `needs-typing` entry can't itself be selected, so it can never reach that gate. The two `InstanceSelector` escape-route callbacks are now reachable in the running app (step 2 can unlock), still no-ops pending task 080's `AggregationControl`. |
| `frontend/src/features/monitoring/explore/ExplorePage.test.tsx`, `.a11y.test.tsx` (modified) | Added: step 1 renders the real catalog-driven `MetricSelector`; selecting a plottable metric unlocks steps 2-4 while step 5 stays locked (aggregation still unset); selecting a type-suspect metric also unlocks steps 2-4 (only `needs-typing` is unselectable). The a11y test now waits for the catalog to load before running `axe`, so the populated `MetricSelector` (chips, warnings) is actually checked, not just its empty loading shell. |
| `frontend/src/shared/i18n/messages.en.ts` (modified) | New keys for `MetricSelector`'s copy (`metricSelectorLayerPkg/Ext`, `…StatePlottable/NeedsTyping/TypeSuspect`, `…TypeAction`, `…SingleSelectNote`, `…TypedCaption`, `…TypeSuspectTitle`) and `TypeMetricDialog`'s (`typeMetricDialogTitle`, `…ForbiddenNotice`, `…TypeLabel`, `…OptionCounter/Gauge/Histogram`, `…UnitLabel`, `…UnitHelp`, `…NoSuggestionNote`, `…SaveAction`, `…Pending`, `…ErrorDetail`). Removed `exploreStepMetricPlaceholder`, now dead since step 1 renders a real control. |

## Design decisions, and why

1. **Rows are built from bare `Radio`, not `FormControlLabel`.** `StrategyPicker` (task 061) already established "native MUI `RadioGroup` over hand-rolled `div`s, for arrow-key nav and disabled-skip for free" — the natural next step is `FormControlLabel`, which is what `StrategyPicker` uses. But a `needs-typing` row needs its own real, independently-clickable `Type…` button alongside the radio, and nesting a `<button>` inside `FormControlLabel`'s `<label>` element is invalid HTML (interactive-in-interactive) — exactly the kind of defect `jest-axe` exists to catch, and a real risk once I tried it (caught before committing, not after). A bare `Radio` still reads `checked`/`onChange`/`name` from the enclosing `RadioGroup`'s context regardless of DOM nesting depth — it doesn't need to be `FormControlLabel`'s direct child to participate — so each row is a plain flex row: `Radio` (with its own `aria-label` via `slotProps.input`, since there's no `FormControlLabel` to derive one from visible text) + name + a chip group + an optional sibling `Button`, all real independent controls.

2. **A genuine nested-flexbox truncation bug, caught by browser-verifying (not just the test suite) before calling this done.** The first working version passed every test and typechecked clean, but a dev-server screenshot showed metric-name chips overflowing the 340px sidebar into the result column. Root cause: MUI's `RadioGroup` root is `flex-direction: column` + `flex-wrap: wrap` with no fixed height (`FormGroup.js`) — with an indefinite main size, each row effectively gets its own flex "line", and `align-items: stretch` then stretches a row to fill *that line's own* content-sized width, not the group's actual rendered width. Setting `minWidth: 0` on the row (the classic single-level fix for "child won't shrink below its content") wasn't enough, because the row was never being handed a width to shrink *from* — this affected every row, not just long-named ones (all 8 `plottable` chips measured at the identical x=594–665, well past the group's own 268–589 bounds, regardless of name length). `width: '100%'` on the row (resolved directly against the group's own known width, independent of the line/stretch computation) fixed it — verified via `getBoundingClientRect()` before and after, then re-screenshotted. Neither `MetricSelector.test.tsx` (jsdom, no real flex layout engine) nor `jest-axe` (checks accessibility tree, not visual layout) would ever have caught this — it needed an actual rendered browser.

3. **`TypeMetricDialog`'s `metricType`/`unit` are controlled props, owned by `MetricSelector`, not the dialog's own `useState`.** `RollbackDialog`/`RestoreRuntimeDialog` (tasks 059/062) own their mutation state internally, but neither has real form fields — they're confirm-only. This dialog does, and the codebase's convention for dialogs mounted persistently with a toggling `open` prop (`DesignerForm.tsx`'s `AcceptRuntimeDialog`/`RestoreRuntimeDialog`) means the dialog's own React component doesn't remount between opens — internal `useState` would leak a half-typed `unit` value from a cancelled attempt into the next `Type…` click on a *different* metric unless reset via a `key` trick or a `useEffect`. Lifting the two fields to `MetricSelector` (reset to `''`/`''` in the same `openTypeDialog` call that sets `typingTarget`) avoids that class of bug outright, and keeps `TypeMetricDialog` a small, directly-testable controlled component (see `TypeMetricDialog.test.tsx`'s own `Probe`).

4. **`MetricSelector` owns the mutation and cache invalidation; `ExplorePage` stays uninvolved.** `InstanceSelector` (task 078) is fully presentational — every piece of state is the caller's. `MetricSelector` mostly follows that (`selected`/`onSelectedChange` are `ExplorePage`'s), but the type-metric flow is entirely local to *this* task's own concern: nothing outside `MetricSelector` needs to know a typing dialog exists, what it collected, or that a mutation ran — only that, eventually, `onSelectedChange` fires with the typed name. `ExplorePage`'s only new responsibility is fetching the catalog (`useMetricCatalog()`) and deriving `metricState` from whichever entry is currently selected, for gating — the same "no hook infers, filters or drops an entry" posture `useCatalog.ts` already documents.

5. **No suggested type is offered, and the dialog says so — not silently.** `SM-D30` forbids inferring a type; §16 Q14 flags that the catalog entry has neither `firstSeen` nor a suggestion field to carry one anyway. `typeMetricDialogNoSuggestionNote` states this as a limitation in the dialog itself, matching the reference's own "Finding — required contract addition" box, rather than a control that just quietly offers nothing with no explanation.

6. **`ChipTone`/state-label mapping reuses the existing vocabulary, same as `chartStateTags.ts`.** `plottable → ok`, `needs-typing`/`type-suspect → warn` — no new colour invented for this task, consistent with `palette.ts`'s existing "Chip/StateChip tones..." set.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc --noEmit -p .                                   # clean
npx eslint .                                             # clean (project-wide)
npx vitest run src/features/monitoring/                  # 29 files, 188/188
npx vitest run                                           # full suite: 268 files, 1601/1601
npm run build                                             # succeeds; ExploreRoute grows 9.44 kB → 13.55 kB;
                                                            # echarts still isolated to its own chunk
```

**Browser-verified**, not just tested — the layout bug in decision 2 above would not have surfaced otherwise:

```
npm run dev   # vite dev server, http://localhost:5174
```
Drove it with a small Playwright script against `/solutions/cache/instances/cache-prod-01/monitoring/explore`:
- Initial state: catalog renders, all 8 entries with correct chips, `needs-typing` visibly disabled with `Type…`,
  no console errors.
- `Type…` → dialog opens, states the forbidden-inference and no-suggestion copy, Save disabled until a type is
  picked; Cancel closes without side effects.
- Selecting `cache.hit_rate` (plottable): steps 2-4 unlock (checkboxes, `seriesBy` toggle become interactive,
  step 4's "Pick a metric first." lock text disappears), step 5 correctly stays locked (aggregation still `none`);
  `type gauge · unit ratio` caption renders.
- Selecting `cache.queue_depth` (type-suspect): steps 2-4 unlock too (only `needs-typing` is unselectable); the
  persistent `type-suspect` warning renders with its `suspectReason`.
- Verified via `getBoundingClientRect()`/`checked` DOM queries, not just screenshots, that only one radio is ever
  `checked` at a time (single-select genuinely enforced, not just visually).
- `console --errors` equivalent (page console/pageerror listeners): empty on every step.

Task-specific acceptance items:

- **Single-select enforced** — `RadioGroup` is exclusive by construction (no code path adds a second selection);
  `MetricSelector.test.tsx`'s "single-select: choosing a different metric replaces the selection" test and the
  browser DOM check (decision above) both confirm only one radio is ever checked.
- **`needs-typing` never plotted** — the `Radio` itself is `disabled` for that state (a real disabled control:
  `user-event` refuses to click it at all, `pointer-events: none`), and `evaluateExploreGating` (task 077) already
  locks steps 2-4 while `metricState === 'needs-typing'`; `ExplorePage.test.tsx`'s existing initial-lock tests still
  pass unchanged since nothing is selected by default.
- **No inferred or suggested type** — `TypeMetricDialog` never pre-fills `metricType`/`unit`; the no-suggestion
  copy states the limitation explicitly; both `MetricSelector.test.tsx` and `TypeMetricDialog.test.tsx` assert the
  copy renders.

### Not fully covered

Step 5 (group by) remains a stated placeholder — task 080 (`AggregationControl`) is what will drive
`acrossInstances` for real; today it stays hardcoded `'none'`, same posture the previous report recorded for
`metricState` before this task.

## Status

DONE — implemented, tested (268/268 files, 1601/1601 tests full-suite), lint-clean, typecheck-clean, production
build succeeds, and browser-verified against a running dev server (caught and fixed a real layout bug that no
automated check would have found). Not yet marked done in `task-list.md`/`execution.md` or committed — pending
user confirmation. GitHub issue for `FE-079 · MetricSelector, needs-typing, type-metric dialog` — to be closed on
commit.
