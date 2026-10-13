# FE-111 · Collapsible nav drawer at desktop widths; dark-mode login contrast fix — Report

## What I implemented

Two operator-reported defects (task spec: `docs/plans/frontend/implementation.md` §FE-111; refs Dec
`FE-D29`, `FE-D5`, UI §A.2/§A.3).

| File | Summary |
|---|---|
| `src/shared/state/uiStore.ts` | New persisted `navCollapsed` field (`setNavCollapsed`/`toggleNavCollapsed`), same store and same `zustand`/`persist` pattern `mode`/`ThemeToggle` already use — so the desktop collapse choice survives navigation and reload the same way colour mode does. |
| `src/app/shell/AppShell.tsx` | **Bug 1 fix.** `variant="permanent"` ignores its `open` prop and always renders docked (verified against `@mui/material/Drawer` source) — that's why task 106 shipped a drawer with no way to hide it. Fixed as "full close": at `lg` and up the whole `Drawer` element is conditionally unmounted (`{(!permanentDrawer \|\| !navCollapsed) && <Drawer …>}`) rather than passed `open={false}`, which would have had no effect. `navOpen`/`onToggleNav` now route through `navCollapsed` at `lg`+ and through the existing `mobileNavOpen` below it. |
| `src/app/shell/AppBar.tsx` | Toggle button (`showNavToggle` prop removed) now renders unconditionally at every width — it used to be hidden at `lg`+ (`showNavToggle = !permanentDrawer`), which was the actual mechanism of the reported bug: no toggle existed at desktop widths at all. Reused the existing `navOpenMenu`/`navCloseMenu` i18n strings and `aria-expanded` wiring; no new labels needed since "open/close" reads correctly for both the mobile overlay and the desktop dock. |
| `src/features/auth/LoginPage.tsx` | **Bug 2 fix.** Card `Paper`'s `backgroundColor`/`boxShadow` came from `tokens.semantic.bg1`/`tokens.console.e8` — the flat `tokens` object, resolved once from `:root` at module load (`tokens.ts:518-519`'s own note) and never re-reading `[data-theme="dark"]`, so the card stayed white while `Typography` inside it (reading `theme.palette.text.*`) went light-on-white in dark mode. Replaced with `theme.palette.background.paper` (mode-reactive, the same value `Paper.tsx` already uses for every other surface), added `border: 1px solid theme.palette.divider` (`Paper.tsx`'s own pattern — needed because a shadow reads as near-invisible against a dark backdrop, so the border becomes the primary depth cue in dark mode), and `boxShadow: theme.shadows[24]` (MUI's own default `Dialog` elevation — the closest existing "floating surface over a dimmed backdrop" analogue, since `Dialog.tsx` never hand-picks a shadow). Outer `Box`'s `tokens.console.navy` backdrop is unchanged: `--navy` has no `[data-theme="dark"]` override in the reference CSS either, so it already resolves to the identical value in both modes — not an instance of the frozen-token bug, nothing to fix. |
| `src/app/shell/AppShell.test.tsx` | Replaced the now-obsolete `'the drawer is permanent with no menu button at lg and up'` test (that behaviour was exactly the bug) with one asserting the toggle is present and labelled at `lg`+. Added a `'AppShell desktop nav collapse (task 111)'` block: collapse/expand round-trip with the toggle visible throughout, `localStorage` persistence of `navCollapsed` (mirroring `uiStore.test.ts`'s own mode-persistence check) verified across an `unmount` + fresh render, and a check that the collapsed state renders no `.MuiBackdrop-root` (i.e. it's a docked `<div>`, never a modal — no focus trap). |
| `src/shared/state/uiStore.test.ts` | New `'useUiStore — desktop nav collapse (task 111)'` block mirroring the existing colour-mode tests: default, explicit set, toggle, and `localStorage` persistence. |
| `src/features/auth/LoginPage.test.tsx` | New regression test asserting the card's computed `background-color` differs between light and dark renders — the frozen-token bug produced the identical `#fff` in both, so this is a direct check against that exact regression re-appearing. |

## Rulings

1. **Full close, not an icon rail, for the desktop-collapsed state.** The task offered either. `SidebarNav`
   renders text-only `ListItemText` items with no icons at all — building an icon rail would mean inventing
   icons for Home/Solutions/each loaded solution, which is scope the bug report didn't ask for and the
   design has no icons committed for. "Full close" reuses the exact same mechanism the below-`lg` overlay
   already has (conditionally not rendering the `Drawer`), so it's the smaller, more consistent change.
2. **Outer `Box` background left untouched, despite the task text naming "outer Box and card Paper" both.**
   Traced `tokens.console.navy` (`#03223B`) against `tokens.background.wMain` (dark-mode
   `theme.palette.background.default`, also `#03223B`) and `tokens.darkTheme.surfaceBg` (also `#03223B`) —
   all three are the same literal value, and the reference CSS's `.login-wrap{background:var(--navy)}` rule
   is not scoped under `[data-theme="dark"]`, meaning navy was always intended to be constant across modes
   (the same rationale `AppShell`'s `nav.bg` uses for the drawer rail). Routing it through
   `theme.palette.background.default` instead would have made it track the *light*-mode value
   (`tokens.background.w20`, `#F7F8F9` — a light grey), which would have broken the reference's light-mode
   look for zero benefit, since there was never a dark-mode discrepancy to fix here. The acceptance line
   ("login card, border and text meet contrast in both themes") and the Testing line also only name the
   card, not the backdrop, which supports this reading.
3. **`theme.shadows[24]` for the card, not a literal shadow value.** `Dialog.tsx` never overrides MUI's
   default Dialog elevation (24), which resolves to `tokens.shadow.modal` per `createSmTheme.ts`'s shadow
   bands — the closest existing "floating card over a dimmed backdrop" analogue in the app, and already the
   pattern `theme.shadows[N]` follows (`Paper.tsx` uses `theme.shadows[8]` for its hover state). A literal
   `tokens.shadow.modal` value would have worked too but wouldn't have demonstrated sourcing from `theme.`
   the way every other themed surface does, which is what `FE-D5` (theme tokens only) asks for.
4. **`showNavToggle` prop removed from `AppBarProps` rather than left `true` everywhere.** It existed
   specifically to hide the toggle at `lg`+; once the toggle must show at every width there's no state left
   for the prop to carry, so keeping an always-`true` boolean parameter would have been dead API surface.
5. **No new i18n keys.** `navOpenMenu` ("Open navigation") / `navCloseMenu` ("Close navigation") already
   read correctly for the desktop collapse/expand action, not just the mobile overlay — reused them rather
   than adding "Collapse navigation"/"Expand navigation" duplicates.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run lint                                                          # clean
npm run typecheck                                                      # clean
npm test -- src/app/shell src/features/auth --environment jsdom        # 6 files, 47 tests — all passed
npm test                                                                # full suite: 175 files, 845 tests — all passed
npm run build                                                          # production build succeeds
```

(`npm run test:a11y` from the task's own Verify line doesn't exist as a script — same pre-existing gap noted
in the task-106 report; every `.a11y.test.tsx` file is an ordinary Vitest test in this one-project setup and
ran as part of the `npm test` invocations above, including `AppShell.a11y.test.tsx`'s 9 width×mode
combinations and `LoginPage.a11y.test.tsx`'s 6 light/dark cases.)

Task-specific acceptance items:

- **Drawer collapsible/expandable at every width ≥ `lg`, state persisted across reload** —
  `AppShell.test.tsx`'s new "desktop nav collapse" block: toggle collapses and re-expands the docked drawer;
  a separate test collapses it, reads `navCollapsed: true` back out of `localStorage`'s `sm-ui-store` key,
  unmounts, and confirms a fresh mount (the closest a jsdom test gets to a real page reload) renders collapsed.
- **Login card, border and text meet contrast in both themes** — `LoginPage.a11y.test.tsx`'s existing
  light/dark axe runs now exercise genuinely different computed styles; new `LoginPage.test.tsx` test asserts
  the card's background differs between light and dark renders (the frozen-token bug produced the same `#fff`
  in both).
- **No regression to task 106's below-`lg` overlay behaviour** — all of `AppShell.test.tsx`'s existing
  below-`lg` tests (overlay open/close, focus trap/restore, close-on-navigation, reflow at 320px) pass
  unmodified; that code path (`mobileNavOpen`, `variant="temporary"`) was untouched.
- **Collapsed state never hides its own toggle** — asserted directly: the toggle button is queried and
  clicked both before and after collapsing.
- **Collapsed state never traps focus** — asserted directly: no `.MuiBackdrop-root` renders when the desktop
  drawer is collapsed (it was never a `Modal` to begin with — `variant="permanent"` never mounts one, per the
  `AppShell.tsx` header comment inherited from task 106 and re-verified here).

## Status

DONE — implemented, tested (845/845 on the full run), lint-clean, typecheck-clean, production build succeeds.
Not yet closed on GitHub — pending review, particularly Ruling 2 (leaving the outer login backdrop
unchanged despite the task text naming it) and Ruling 1 (full-close over an icon rail).

---

# Revision — 2026-08-26 · icon rail, sidebar icons, selected-solution state

Follow-up on operator feedback against the shipped FE-111/FE-024/FE-112 drawer. No new task ID: all
three requests fall inside tasks already in the register.

| Request | Task it belongs to |
|---|---|
| An icon on every sidebar entry | **FE-024** (`SidebarNav`) — the component's own icon set, extended to solutions via **FE-112**'s catalog wiring |
| Collapse the solutions sidebar to a compact width, icons visible, names hidden | **FE-111**, whose spec offered *"icon-rail or full close"* and whose first pass chose full close |
| Make the selected solution obvious, with a green status circle | **FE-112** (the solution sub-list) rendered through **FE-024**'s active-item treatment |

## Ruling 1 above is reversed

The original Ruling 1 chose full close over an icon rail on the ground that *"`SidebarNav` renders
text-only `ListItemText` items with no icons at all — building an icon rail would mean inventing icons
for Home/Solutions/each loaded solution."* That premise no longer holds:

- Home and Solutions now have glyphs of their own (`shared/ui/SidebarNav/icons.tsx`), taken from the
  in-repo visual reference (`docs/design/frontend/reference/app/icons.jsx`: `home`, `grid`), not invented.
- A **solution's** glyph was never something to invent either — `SolutionPackageSummary.icon` has carried
  one since task 033, and the catalog card has rendered it since task 039. The drawer now reads the same
  field, so a solution shows one mark in both places.

With icons in hand, full close is the worse of the two: it costs the operator *all* navigation, not just
the labels, and leaves the app bar's hamburger as the only way back. The rail keeps every destination one
click away at `--drawer-w-rail` (76px) instead of `--drawer-w` (256px).

## Files changed

| File | Summary |
|---|---|
| `src/shared/theme/tokens.css`, `tokens.ts` | New `--drawer-w-rail: 76px` / `console.drawerWidthRail`, alongside the existing `--drawer-w`. |
| `src/shared/ui/internal/solutionGlyphs.tsx` | **Moved** from `features/inventory/solutions/icons.tsx` (`BoltIcon`, `FilesIcon`, `BeakerIcon`, `PackageGlyphIcon`, `SolutionGlyph`). `shared/ui` may not import a feature, and the drawer needs the same glyphs the catalog card uses — one shared set beats two drifting copies. Barrelled from `shared/ui/index.ts` exactly as `ToneIcon` already is. `SolutionGlyph`'s `name` became optional so a package naming no glyph falls back to the generic package mark instead of rendering nothing. |
| `src/features/inventory/solutions/icons.tsx`, `SolutionCard.tsx`, `SolutionsPage.tsx` | The moved glyphs deleted here; both consumers now import `SolutionGlyph` / `PackageGlyphIcon` from `@shared/ui`. No visual change. |
| `src/shared/ui/SidebarNav/icons.tsx` | New. `HomeIcon`, `GridIcon` for the two platform entries; `ChevronLeftIcon`/`ChevronRightIcon` for the rail control. Inlined `SvgIcon`s — `@mui/icons-material` is still not a dependency. |
| `src/shared/ui/Breadcrumbs/Breadcrumbs.types.ts` | `SidebarNavSolution.icon?`; `SidebarNavProps.collapsed?` and `onToggleCollapsed?`. |
| `src/shared/ui/SidebarNav/SidebarNav.tsx` | Icons on every entry; rail mode; per-solution status dot. Active state is computed with `useMatch` against the *same* `to`/`end` pair the `NavLink` navigates with, so the styling and the link's own `aria-current` / `.active` can never disagree. Every solution sub-route lives under `/solutions/:solutionId/instances` (`app/routes.ts`), so a non-`end` match keeps the solution selected on Designer/Governance/Monitoring too. Active background and hover moved off `action.selected`/`action.hover` — those are mixed for the *page* surface and come out near-invisible on the always-navy drawer — onto `alpha(nav.accent, …)` / `alpha(nav.text, …)`, matching the reference's `.nav-item.on` rule. |
| `src/app/shell/AppShell.tsx` | The `Drawer` is no longer conditionally unmounted at `lg`+. Docked wrapper *and* Paper both animate between `var(--drawer-w)` and `var(--drawer-w-rail)`, so `main` reflows into the width the rail gives back (the wrapper is `main`'s flex sibling; the Paper is `position: fixed` for every variant, so both must move together or the Paper floats over the content). `railed = permanentDrawer && navCollapsed` is the *rendered* collapse — below `lg` the drawer is a modal overlay and the stored preference is left untouched, so widening the window restores it. |
| `src/app/shell/AppBar.tsx` | `showNavToggle` prop reinstated (`!permanentDrawer`). The hamburger belongs to the overlay drawer; at `lg`+ the rail carries its own control, and two identically-labelled toggles for the same navigation would be a duplicate name in the accessibility tree. |
| `src/app/shell/Brand.tsx` | `compact` prop: a monogram tile in place of the wordmark, which does not fit 76px. Decorative — the `<nav>` is already labelled "Solution Manager". |
| `src/features/inventory/solutions/sidebarSolutions.ts` | Carries `pkg.icon` through to the drawer. |
| `src/shared/i18n/messages.en.ts` | `navCollapse` / `navExpand`. Distinct from `navOpenMenu`/`navCloseMenu`, which now mean only the overlay drawer. |

## Rulings

1. **The rail's toggle lives in the rail, not in the app bar.** Task 111's invariant — *the collapsed state
   never hides its own toggle* — holds more directly this way: the toggle is *in* the thing it collapses,
   and that thing is always on screen. Adding a second app-bar toggle would have duplicated the accessible
   name without adding a capability.
2. **One dot treatment, attached to the glyph, not two.** The dot rides the corner of the solution's own
   glyph in both modes rather than sitting at the row's trailing edge when expanded. The glyph is the one
   part of the row the rail keeps, so anchoring the marker to it is what makes a single treatment work at
   both widths — and it frees the horizontal space the trailing dot was costing the label.
3. **Labels leave the layout, never the accessibility tree.** Each rail entry keeps an `aria-label` holding
   its full expanded label (instance count included) and gains a tooltip, so a screen-reader user and a
   sighted user get the same information. `MuiTooltip` is used directly rather than `shared/ui`'s `Tooltip`
   wrapper: that wrapper exists to make *unfocusable* triggers reachable by wrapping them in a
   `tabIndex={0}` `role="button"` span (task 014), and a nav link is already focusable — nesting it would
   misreport the role and add a second tab stop.
4. **Nothing is keyed to a particular solution.** Glyph, dot and active treatment are all derived from the
   package row and the current route; `File Mesh` is not special-cased anywhere.

## Tests

`AppShell.test.tsx`'s four full-close assertions asserted the *old* contract (the nav absent when
collapsed) and were rewritten to the rail contract; one new test asserts the docked wrapper's width
actually changes token, which is what makes `main` reflow. `SidebarNav.test.tsx` gains icon coverage
(including the unknown-glyph fallback), dot-moves-with-selection, sub-route selection, and a collapsed
case. `SidebarNav.a11y.test.tsx` gains a collapsed pass in both modes — the rail is where accessible
naming is easiest to lose. `sidebarSolutions.test.ts`'s `toEqual` gained the new `icon` key.

## Verification

| Check | Result |
|---|---|
| `npm run typecheck` | clean |
| `npm run lint` | clean (`--max-warnings=0`) |
| `npx prettier --check` on every file touched | clean (files already failing at `HEAD` were left alone) |
| `SidebarNav` + `app/shell` suites | 41 passed |
| Full unit suite | see below — the only failures are pre-existing, caused by an **uncommitted working-tree edit to `src/shared/testdoubles/data/solutions.ts`** (the `cache-nightly` dev-mode package was deleted). Restoring the committed fixture makes `src/features/inventory/solutions` + `solutionsRepository` go 36/36 green, which is the proof they are not this change's doing. |
| `npm run e2e` | 4 passed, 1 failed — `smoke.spec.ts > boots and renders the home route`, which lands on `/login`. Reproduced identically on a clean `HEAD` worktree, so pre-existing. |
| Real browser (`npm run dev`, MSW fixtures, Chromium) | 1440×900 expanded and railed, 320×700 overlay, light and dark: icons on every entry, green ringed dot on the selected solution and muted on the others, selection moves on navigation, `main` reflows on collapse, tooltips on hover in the rail. |
