# Manual keyboard script — accessibility

Task 098's own written script: the paths axe cannot reach — focus trap and restore, jump-to-field
focus movement, canvas node keyboard operation, the chart keyboard model, and dashboard `order`
traversal (`dynamic-metrics/design.md` §18; `implementation.md`'s own line for FE-098: *"a written
manual keyboard script (checked into `docs/`) covering: skip link, drawer, breadcrumbs, tabs, data
grid, Designer field traversal and jump-to-field, dialogs (trap, Esc, focus restore), composer
nodes, dashboard `order` traversal, and the chart keyboard model. Manual results are recorded per
release."*

Run this **mouse-free** — unplug it or don't touch it. Use `Tab` / `Shift+Tab` to move, `Enter` /
`Space` to activate, arrow keys where an item says so. Record the result in the log at the bottom
after each release candidate.

## 1. Skip link

- Load any authenticated page. Press `Tab` once, before clicking anything.
- **Expect**: a visible "Skip to main content" link appears as the very first focus stop — this is
  `index.html`'s own static link (it sits before `#root` in the DOM, so it always wins the race
  against `AppShell.tsx`'s own copy, `skipToMain` message key). Press `Enter`.
- **Expect**: focus lands inside the main content region (`id="main-content"`), past the app bar
  and drawer. Task 110 found this broken on every authenticated screen and on `/login` — the
  static link's target didn't exist (`AppShell.tsx` used `id="main"`; `LoginPage.tsx` had no id at
  all) — before fixing both to match.

## 2. Drawer (nav)

- At a narrow viewport (< `lg`, 1240px): find and activate "Open navigation" (the app bar's menu
  button).
- **Expect**: focus moves into the drawer; it behaves as a modal (MUI `Drawer` — the rest of the
  page is `aria-hidden` while it's open). `Tab`/`Shift+Tab` cycle only within the drawer, never
  escaping to the page behind it.
- Press `Escape`.
- **Expect**: drawer closes, and focus **returns** to the "Open navigation" button that opened it
  — not to the page body.
- Reopen the drawer, activate a link inside it (e.g. "Solutions").
- **Expect**: drawer closes and the page navigates — same as Escape, closing on navigate, not just
  on Escape.
- At a wide viewport (≥ `lg`): find the drawer's own collapse control (task 111) instead.
- **Expect**: the collapse/expand toggle is itself keyboard-reachable and operable, and the
  collapsed rail still exposes an expand control — a collapsed rail must never trap the operator
  with no way back.

## 3. Breadcrumbs

- Navigate to a nested page (e.g. an instance's Designer). Tab into the breadcrumb trail.
- **Expect**: every ancestor segment is a real, individually-focusable link in path order; the
  current page's own segment (last one) is text, not a link to itself.

## 4. Tabs (Designer / Governance / Monitoring)

- On an instance page, Tab to the category tablist.
- **Expect**: the currently-selected tab is reachable, `aria-selected="true"`, and activating an
  adjacent tab moves keyboard focus into that tab's panel (`RouteTabs.tsx`'s own `panelRef.current?.focus()`
  effect).
- ⚠️ **Known open question, not yet resolved as of task 098**: `RouteTabs.tsx` gives only the
  selected tab `tabIndex={0}` (the other two are `tabIndex={-1}`), which is the correct **roving
  tabindex** half of the WAI-ARIA APG tabs pattern — but no `ArrowLeft`/`ArrowRight` handler moves
  focus *between* tabs was found in that file. Check explicitly: with focus on the selected tab,
  do the arrow keys move focus to a sibling tab? If not, the only way to reach an unselected tab
  by keyboard may be activating it as a plain link via `Tab`, which the roving-tabindex pattern
  specifically prevents (siblings are `tabIndex={-1}`, so `Tab` skips straight past them). Record
  the actual observed behaviour below — this script is what surfaces it either way.

## 5. Data grid (`DataTable` — MUI X Data Grid Community)

- On a list screen (e.g. Instances), Tab into the grid.
- **Expect**: arrow keys move the active cell; `Home`/`End` jump to row start/end; a row's checkbox
  (if selectable) and its expansion control are both independently reachable.
- Below `md`, the same screen is a card list (`CardView.tsx`, task 107) — repeat: every card's
  controls (checkbox, expand) reachable in visual order.

## 6. Designer field traversal and jump-to-field

- Open Designer on an instance with several sections. Below `lg`, open "Jump to section"
  (`SectionNav.tsx`, task 108) and pick a section other than the first.
- **Expect**: the drawer closes and focus lands on that section's **first field**, with the browser
  having scrolled it into view (`scrollIntoView`) — not merely expanded and left off-screen.
- Tab through a section's fields in order.
- **Expect**: each field's metadata cluster (rendered path, `Default` hint, `Modified` chip) is
  reachable or at least announced alongside its control, never only visually adjacent with no
  programmatic association.

## 7. Dialogs (trap, Esc, focus restore)

- Open any dialog (a save conflict, a high-consequence confirm, `SaveWidgetDialog`, …) from a named
  trigger button.
- **Expect**: focus moves into the dialog; `Tab`/`Shift+Tab` cycle only within it (`Dialog.tsx`
  wraps MUI `Dialog`, verified against its own trap/restore/`aria-modal` behaviour).
- Press `Escape`.
- **Expect**: dialog closes, focus **restores** to the exact trigger button that opened it.
- Where `Dialog`'s `fullScreenBelow` opts a dialog into a full-screen sheet below a given width
  (the diff/conflict/rollback dialogs, task 108): repeat below that width. The trap/Esc/restore
  behaviour must be identical to the centred desktop dialog — full-screen is a size change, not a
  different interaction model.

## 8. Composer nodes

- Open Designer's Composer canvas (≥ `md` — below that it's the flat "All fields" list, task 108,
  and this section doesn't apply).
- Tab to a component node (`ComposerNode.tsx`).
- **Expect**: the node is reachable and operable via its own `onKeyDown` handler — confirm which
  keys select/open it (read the on-screen hint or `ComposerNode.tsx`'s own comment if unclear) and
  that using them does not require a pointer at any point.

## 9. Dashboard `order` traversal

- Open a dashboard with 3+ widgets, at least one of them **not** in reading/visual order relative
  to its neighbours (or use "Edit layout" to move one).
- Tab through the widgets.
- **Expect**: keyboard focus visits them in the dashboard's stated `order` field, **not** in
  left-to-right/top-to-bottom visual (pixel) position — this holds at every width (task 109 made
  the responsive stack below `lg` rely on this same contract, rather than a second one).

## 10. Chart keyboard model

- Reach any line chart (`ChartRenderer.tsx`) at ≥ `sm` width (below `sm` the tabular alternative is
  the primary affordance — task 109 — so this section's canvas model doesn't apply there; instead
  confirm the table itself is reachable and its rows are real, focusable-by-scan content).
- Tab to the chart. It is **one** focus stop (`role="application"`).
- `→` / `←`: **expect** the cursor moves along the time axis; an off-screen live region announces
  the focused series/instant/value (confirm with a screen reader, or by reading
  `aria-live="polite"` text in the accessibility tree).
- `↑` / `↓`: **expect** the focused series changes.
- `Enter`, or the visible "Open data table" button: **expect** the tabular alternative
  (`ChartDataTable.tsx`) opens, keyboard-reachable, with a text summary (range, series count,
  min/max/last per series).

## Results log

Fill in per release. `✓` pass, `✗` fail (link the issue), `—` not applicable that release.

| Release | Date | Tester | 1 Skip | 2 Drawer | 3 Crumbs | 4 Tabs | 5 Grid | 6 Designer | 7 Dialogs | 8 Composer | 9 Dashboard | 10 Chart | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — | — | — | — | — | — | *(no release has run this script yet — task 098 only just wrote it)* |
