# FE-087 · Layers: badge, filter, hide-not-delete, reset — Report

## What I implemented

Refs Dec `SM-D31` `DM-D5` (scope deferred) `DM-D18` · Design §12 · Conflict C7. Deps `086`.

Badge (`LayerBadge`) and the layer filter (`DashboardToolbar`) already existed from tasks 085/086, built ahead of
this task's own schedule slot. What FE-087 actually adds is the rest of the acceptance criteria: **hide-not-delete**
for a `product-default` widget, its **Restore** path, and **Reset defaults** for the whole dashboard.

| File | Summary |
|---|---|
| `frontend/src/features/monitoring/dashboard/HiddenDefaultWidget.tsx`, `.test.tsx`, `.a11y.test.tsx` (new) | What a hidden `product-default` widget renders in its own grid slot instead of the live `WidgetFrame` — mirrors the visual reference's own `.hidden-def` card. Reuses `EmptyState` inside `Paper`, same precedent `WidgetFrame.tsx`'s own `TooNewContent` already set for an `h2` inside a widget card. States "Hidden default · {title}" / "Hidden, not deleted — a later package can still improve this default and your choice survives" / a `Restore` action, disabled while a restore is in flight. |
| `frontend/src/features/monitoring/dashboard/WidgetFrame.tsx`, `.test.tsx`, `.a11y.test.tsx` (modified) | New `onHide`/`hidePending` props. The menu always carries the "Edit widget" pending caption (unchanged posture — that flow has no owning task); a `product-default` widget's menu additionally gets a real "Hide (keep default)" item wired to `onHide`, disabled while `hidePending`. A `shared` widget's menu is unchanged — no hide, no delete (deleting an operator-saved widget has no owning task or repository method). |
| `frontend/src/features/monitoring/dashboard/DashboardGrid.tsx`, `.test.tsx`, `.a11y.test.tsx` (modified) | Owns three new mutations: `hideMutation` (`dashboardRepository.hideDefault`, shared by both Hide and Restore — same PATCH, opposite boolean) and `resetMutation` (`dashboardRepository.resetDefaults`), each invalidating the dashboard query on success, same pattern `setLayoutMutation` already used. Renders `HiddenDefaultWidget` in place of `WidgetFrame` for any widget with `hidden === true`, in the exact same grid slot (`gridColumn`/`gridRow` from its placement) a live widget would occupy. New `movableWidgets` (widgets with `hidden !== true`) is what move-earlier/move-later adjacency now resolves against — a hidden default has no move controls of its own, so a live widget's move-adjacent neighbour is no longer necessarily its array-adjacent one. New "Reset defaults" button next to "Edit layout". |
| `frontend/src/features/monitoring/dashboard/DashboardView.tsx`, `.test.tsx` (modified) | `visibleWidgets` no longer filters out `widget.hidden === true` — a hidden default stays in the list (still layer-filtered, still `order`-sorted) so `DashboardGrid` can render it as a placeholder instead of making it disappear. The `hiddenCount` note/`Typography` block is retired — the per-widget placeholder card now states that fact inline, so a dashboard-wide count would be redundant. Added an integration test proving a fully-hidden dashboard renders its placeholder(s), not the "no widgets saved" empty state. |
| `frontend/src/shared/i18n/messages.en.ts` (modified) | Added `dashboardGridResetDefaults`, `widgetFrameMenuHide`, `hiddenDefaultWidgetTitle`/`Body`/`Restore`. Renamed `widgetFrameMenuPendingNote` → `widgetFrameMenuEditPendingNote` (its copy now describes only the still-pending "Edit widget" flow, not hide/restore/reset, which this task ships). Removed the now-dead `dashboardViewHiddenCountNote` (plural message, no longer referenced).|
| `docs/plans/frontend/task-list.md`, `docs/plans/frontend/execution.md` (modified) | Marked `087` done. |

## Design decisions, and why

1. **Hide/Restore share one mutation (`hideMutation`), not two.** Both are the identical PATCH with the opposite
   `hidden` boolean for one widget id — a second mutation object would just duplicate the `onSuccess` invalidation
   wiring for no behavioural difference. `WidgetFrame`'s `onHide` and `HiddenDefaultWidget`'s `onRestore` both call
   `hideMutation.mutate({ widgetId, hidden: <bool> })`.

2. **A hidden default is included in `DashboardView`'s `shown` list (and therefore reaches `DashboardGrid`),
   rather than being filtered out and handled as a separate "N hidden" side list.** The reference implementation
   renders the placeholder inline, in the widget's own grid slot, at the same visual weight as a live widget —
   that's what makes "hidden, not deleted" legible: the operator sees exactly where their choice took effect, not
   an abstract count elsewhere on the page. This also means a fully-hidden dashboard is **not** the empty state —
   it still has content, all of it currently hidden and all of it restorable — new test in `DashboardView.test.tsx`
   proves this directly.

3. **`movableWidgets` — not `widgets` — is what move-earlier/move-later resolves adjacency against.** Task 086's
   own header already established "swapping is scoped to adjacent VISIBLE widgets… moving a visible widget past an
   invisible one it doesn't visually border would be confusing," for layer-invisible widgets (filtered out before
   reaching `DashboardGrid`). Once hidden defaults also reach `DashboardGrid` (decision 2), the same invariant has
   to extend to them: a hidden default sitting between two live widgets in the full `widgets` array must not become
   the "neighbour" a move swaps order with, since it has no move controls of its own and the operator never saw it
   move. `movableWidgets = widgets.filter(w => w.hidden !== true)` is what `moveWidget`, `canMoveEarlier`, and
   `canMoveLater` index into; the render loop itself still maps over the full `widgets` list so every slot (hidden
   or live) gets drawn. New test (`skips a hidden widget when resolving adjacency…`) proves 'First' swaps with
   'Third', not the hidden 'Second' sitting between them.

4. **"Reset defaults" restores every hidden default at once — there is no per-widget "un-reset."** Design §12
   states only "`Reset defaults` restores the package layer," with no narrower scope; `resetDefaults(dashboardId)`
   is already declared exactly that way in the repository interface (dashboard-scoped, no widget id argument).
   Implementing anything more granular here would be inventing a second, unspecified reset semantics.

5. **`HiddenDefaultWidget` reuses `EmptyState` rather than a bespoke placeholder component.** Same `h2`-inside-a-
   card precedent `WidgetFrame.tsx`'s own `TooNewContent` (task 086) already accepted for the "schema too new"
   placeholder — a whole-card placeholder state is exactly what `EmptyState` is for elsewhere in this codebase
   (`ExploreResult.tsx`, `SingleStatMark.tsx`, `GaugeMark.tsx`), and a second bespoke component would just
   duplicate its layout for no behavioural gain.

6. **The "Edit widget" pending caption stays on every widget's menu, including a `shared` one, unrenamed in
   substance but re-copied.** The old `widgetFrameMenuPendingNote` ("Hide, restore and reset actions land in a
   following task") described exactly the three things this task now ships — leaving it as-is would have been
   actively wrong once Hide/Restore/Reset landed. The new `widgetFrameMenuEditPendingNote` names only what is
   still actually pending: design §12's "widget menu → Explore prefilled → overwrite or fork" edit flow, which has
   no owning `FE-*` task either before or after this one.

## Residuals (flagged, not silently decided)

1. **A `shared` widget's menu has no delete (or any other) action — just the "Edit widget" pending caption.** The
   visual reference's own `WidgetCell` shows "Delete" for an operator-layer widget, but no `FE-*` task builds it and
   `DashboardRepository` has no delete method to call — inventing one here would be adding both UI and a contract
   surface no task asked for. Acceptance criteria for this task name only "package defaults cannot be deleted,"
   never "shared widgets can be," so this is a deliberate scope boundary, not an oversight.
2. **The hidden-default representation and the reset payload still have no shape in the contract** (§16 Q15,
   unchanged from `dashboardRepository.ts`'s own header) — `hideDefault`/`resetDefaults` were already declared
   there (ahead of this task, by 086's own scaffolding) as `PATCH .../widgets/:widgetId {hidden}` and
   `POST .../reset`, both still invented. This task does not change that status; it is the first to actually call
   both from the UI.
3. **The MSW dev fixture (`console.ts`) still ships zero `shared`-layer and zero pre-hidden widgets** — both of its
   two widgets are `product-default`, unhidden. Adding either would have broken an existing, still-valid test
   (`DashboardView.test.tsx`'s "filters the widget list by layer" asserts the empty state when filtered to
   `shared`). Manual/browser verification below used a temporary local edit, reverted before commit (see Testing) —
   the same "throwaway, not committed" posture task 086's own report already used for its Playwright script.

## Testing

Every command run from `frontend/`:

```
npm run typecheck   # tsc -p tsconfig.json && tsc -p tsconfig.node.json — clean
npm run lint        # eslint . --max-warnings=0 — clean
npm run build       # vite build + assert-echarts-chunk — clean, echarts still isolated to its own chunk
npx vitest run src/features/monitoring/dashboard/   # 14 files, 94/94
npx vitest run src/features/instance/ src/features/monitoring/ src/shared/i18n/   # 66 files, 457/457
npx vitest run                                      # full suite — 300 files, 1846/1846, clean (no flake this run)
```

**Browser-verified**, not just tested — Playwright against the real msw fixture backend (`npm run dev -- --port
5191 --strictPort`), discarded scripts, not committed:

- `/solutions/cache/instances/cache-prod-01/monitoring` renders both fixture widgets with their "package" badges.
- Opening a live widget's menu shows "Hide (keep default)"; clicking it fires a real
  `PATCH .../dashboards/db_cache-prod-01/widgets/w1` with body `{"hidden":true}`.
- "Reset defaults" fires a real `POST .../dashboards/db_cache-prod-01/reset`.
- With one fixture widget temporarily marked `hidden: true` (reverted before commit —
  `git status` confirms `console.ts` carries no diff), the dashboard renders the "Hidden default · Hit rate"
  placeholder card **in its own grid slot**, alongside the still-live "Cache size" widget, with a "Restore"
  button; clicking it fires `PATCH .../widgets/w1` with body `{"hidden":false}`. Screenshot taken and reviewed
  (not committed — scratch file).
- Console/page-error listener: empty — no errors across either run.

## Status

Done, pending user confirmation to commit.
