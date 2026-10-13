# FE-131 · Toolbar, banners, and an explicit "Add widget" action — Report

## What this closes

Plan `126`'s third defect, the dashboard chrome: a toolbar whose Layer select stretched the full width, two
stacked full-width explainer banners above it, and dashboard-level actions rendered as a separate
right-aligned row below all of it.

## The toolbar is one dense row

- **`fullWidth={false}` on the Layer and Refresh selects.** `shared/ui`'s `Select` defaults `fullWidth` to
  `true` (`Select.tsx:23`), which is the whole reason Layer stretched — nothing in the toolbar asked for it.
- `alignItems` `flex-start` → `center`, gap `18px` → `10px` (reference `.dash-toolbar`).
- **`data-reflow-exception="persistent-toolbar"` and `tabIndex={0}` are untouched**, as the plan requires:
  `verify:reflow`'s allowlist and task 110's axe pass both depend on them.

## "Add widget", and two actions that moved

`DashboardToolbar` gains an `actions` slot, rendered at the far end behind a flex spacer. It carries three
buttons: the new **Add widget** (routing to Explore), plus **Reset defaults** and **Edit layout**, which
`DashboardGrid` used to render as its own row.

That move required lifting state, and the lift is the right shape rather than a convenience: `editing` and
"reset this dashboard" act on the **dashboard**, not on the grid that happens to draw it. So `DashboardView`
now owns both, `DashboardGrid` takes `editing` as a prop, and the grid is purely a renderer of widgets. The
grid dropped its `useState`, its reset mutation, and its `Button`/`t` imports.

Together with task `130`'s tab bar this gives two independent routes to Explore from a populated board — a
persistent tab and an explicit action — where before there were none.

## The two banners

| Note | Before | After |
|---|---|---|
| Landing vs index (`dashboardViewLandingNote`) | Permanent full-width `Alert` | `InfoTip` on the tab bar |
| Range/refresh authoritative (`DM-D21`) | Permanent full-width `Alert` | Caption directly under the toolbar |

`DM-D21` is **kept, not dropped** — it is a real rule about how the toolbar behaves, so it now reads as a
caption under the controls it explains rather than as an alert about the whole page. The landing note is a
fact an operator needs once, which is what `InfoTip` is for.

`RouteTabs` gained a `trailing` slot to host that tip. It renders as a **sibling** of the `role="tablist"`,
never inside it — a tablist may only contain tabs, and anything else there fails axe's
`aria-required-children`. The bottom rule moved to the wrapping row so `trailing` still sits on the tab
line.

## Tests moved with the code they cover

Six tests broke, all of them by construction rather than by accident, and each was moved or rewritten rather
than weakened:

- Three `DashboardGrid` tests clicked "Edit layout" to reach edit mode. `editing` is a prop now, so they set
  it directly — which is also a better test, since it no longer depends on a button in another component.
- `DashboardGrid.a11y.test.tsx`'s editing-state case, likewise (its `userEvent`/`screen` imports went with
  it).
- The "Reset defaults" test **moved to `DashboardView.test.tsx`**, following the button.
- The landing-note test now asserts the `InfoTip`'s accessible name.

Three new cases in `DashboardView.test.tsx`: the edit-mode toggle drives the grid's move controls end to
end, **Add widget** routes to Explore, and `DM-D21` is stated exactly once with no `role="alert"` left on
the page.

**One failure outside the monitoring subset:** `InstanceShell.test.tsx` asserted the landing note as visible
text. Updated to the `InfoTip`'s accessible name — caught by the full suite, not by the directory-scoped
runs.

## Verification

- `npm run typecheck` — clean
- `npm run lint` — clean at `--max-warnings=0`
- `npx prettier --write` — clean on every touched path
- `npm test` — **326 files, 2215 tests, all passed**
- `npm run verify:reflow` — **137 passed** (the `data-reflow-exception` allowlist entry still holds)

### A flaky first reflow run, called out rather than buried

The first `verify:reflow` run reported 7 failures — but four were **`governance`**, a screen this task does
not touch, and the set did not overlap the changes. A clean re-run passed 137/137. Consistent with the
Playwright suite contending for the same machine that made the unit suite hang earlier in this plan (see
task `128`'s report); recorded here so the next person seeing an odd reflow failure re-runs before
investigating.

## Files

| File | Change |
|---|---|
| `src/features/monitoring/dashboard/DashboardToolbar.tsx` | One dense row; bounded selects; `actions` slot behind a spacer. |
| `src/features/monitoring/dashboard/DashboardView.tsx` | Owns `editing` + reset; supplies the three actions; banners collapsed. |
| `src/features/monitoring/dashboard/DashboardGrid.tsx` | `editing` is a prop; button row, state and reset mutation removed. |
| `src/features/monitoring/MonitoringTabs.tsx` | Optional `note` → `InfoTip`. |
| `src/shared/ui/RouteTabs/RouteTabs.tsx` · `.types.ts` | `trailing` slot, outside the tablist. |
| `src/shared/i18n/messages.en.ts` | `dashboardAddWidget`. |
| 5 test files | Moved/rewritten as above, plus three new `DashboardView` cases. |
