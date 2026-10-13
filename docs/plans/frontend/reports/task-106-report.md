# FE-106 · Responsive app shell and navigation — Report

## What I implemented

The 256px drawer and 64px app bar now work at 320px without hiding navigation behind a control the
operator can't find (task spec: `docs/plans/frontend/implementation.md` §FE-106; refs Dec `FE-D29` `D9` 🔒,
Design §9/§14/§18, UI §A.2).

| File | Summary |
|---|---|
| `src/app/shell/AppShell.tsx` | Drawer is `permanent` at `lg` and up, `temporary` below it (`useMediaQuery(theme.breakpoints.up('lg'))`), replacing the `Sidebar` layout primitive (which stacks the drawer full-width above content — the wrong shape for an off-canvas nav) with MUI's own `Drawer`. `variant="permanent"` never mounts a `Modal` (verified against `@mui/material/Drawer` source); `temporary` always does, regardless of variant — the same mechanism `Dialog.tsx` (task 016) already trusts for focus trap, restore-to-trigger, Escape-close and backdrop-click-close, so none of it is reimplemented here. Closes on navigation via a render-time state adjustment (React's own pattern for "reset state when a derived value changes"), not inside the existing focus/scroll effect — an unconditional `setState` in an effect body is a cascading-render lint error under this repo's `react-hooks/set-state-in-effect` rule. |
| `src/app/shell/AppBar.tsx` | New `showNavToggle`/`navOpen`/`onToggleNav` props (state lives in `AppShell`, not here) driving a labelled, toggling menu button below `lg`. Below `md` (`useMediaQuery(theme.breakpoints.down('md'))`) the tenant display, theme toggle and support menu stop rendering directly and their content folds into `AccountMenu` instead (`I5`'s tenant display is *moved*, never dropped); `Brand` appears here too, since the permanent drawer that normally carries it is hidden. |
| `src/app/shell/Brand.tsx` | New. The "Solution Manager · Solace Console" wordmark — no logo asset exists in-tree, so it reuses `LoginPage.tsx`'s own text-only treatment (`navLabel` + `loginBrandTagline`) rather than inventing a second one. Used in the drawer at every width, and in the app bar itself below `md`. |
| `src/app/shell/AccountMenu.tsx` | New optional `extraEntries` prop, spliced into its `Menu` entries between the identity block and the DEV-only Targets/Admin preview — the collapsed app bar's fold-in point. |
| `src/app/shell/SupportMenu.tsx` | Extracted `SupportMenuContent` (the version/correlation-id/copy `Stack`) from `SupportMenu` so the collapsed app bar can reuse the same content without nesting one popover menu inside another. |
| `src/app/shell/icons.tsx` | New `MenuIcon` (hamburger). |
| `src/shared/ui/Breadcrumbs/Breadcrumbs.tsx` | Below `md`, collapses to *"… / current"* — the current crumb stays (not a link, not recoverable elsewhere); every ancestor with a `to` moves into a `Menu` behind the "…" trigger via `onClick: () => navigate(crumb.to)`, same wiring `AccountMenu.tsx`'s own preview links already use. No ancestors (Home) renders no "…" trigger at all. |
| `src/shared/i18n/messages.en.ts` | New keys: `navOpenMenu`, `navCloseMenu`, `breadcrumbMore`. |
| `src/test/matchMedia.ts` | New. jsdom has no `window.matchMedia` — MUI's `useMediaQuery` falls back to `defaultMatches` (`false`) without one, which would make every pre-existing shell test see a permanently-closed overlay drawer and a collapsed app bar regardless of intent. Polyfills it against a mutable width, defaulting to a desktop 1280px so every test written before this task keeps seeing the chrome it already asserts against; `setViewportWidth`/`resetViewportWidth` let a test exercise the narrower branches deliberately. Only parses `min-width`/`max-width` px queries — the only shapes `theme.breakpoints.up`/`.down` ever produce (verified against `@mui/system/createBreakpoints` source). |
| `src/test/matchMedia.test.ts` | New. Proves the polyfill's parsing, boundary inclusivity, and reset behaviour. |
| `src/test/setup.ts` | Installs the polyfill once globally; resets the width in `afterEach` so a test that narrows it can't leak into the next test's desktop assumptions. |
| `eslint.config.js` | `matchMedia.test.ts` needs real `@media (min-width:…)` string literals to prove the polyfill parses the exact shape `theme.breakpoints` produces — added it to the raw-`@media` rule's `ignores`, then a sibling block re-enabling the other three rules in that combined block (raw colour, plane-host env var, demo-states), the same technique already used for `shared/ui/layout/**`. |

## Rulings

1. **`SidebarNav.tsx` needed no changes**, despite being named in the task's own "Files: Modify" list. All
   of the responsive behaviour — permanent-vs-overlay, open/close, focus trap/restore, close-on-navigation —
   is handled by the `Drawer` wrapper and `AppShell`'s own state; `SidebarNav`'s content is identical at
   every width. Left it alone rather than touching it just because the plan named it, which would have been
   change for its own sake.
2. **The overlay drawer's own close-on-navigation is a render-time state adjustment, not an effect.** The
   obvious `useEffect(() => setMobileNavOpen(false), [location.pathname])` is exactly the
   `react-hooks/set-state-in-effect` lint error this repo treats as a hard error (cascading renders) — fixed
   with React's own documented pattern (track the last-seen pathname, adjust `mobileNavOpen` inline during
   render when it changes), not a lint suppression.
3. **A `matchMedia` polyfill was a hard prerequisite, not a nice-to-have.** Without one, `useMediaQuery`
   silently returns `false` for every query in every test (MUI's own defensive fallback for jsdom), which
   would have made `permanentDrawer` and `collapsed` both always `false` — every existing `AppShell`/`AppBar`
   test would have started asserting against a permanently-closed, collapsed shell regardless of what it
   actually wrote. Discovered this by running the full suite after the first pass at `AppShell.tsx` and
   seeing `AppShell.test.tsx`'s pre-existing tests fail on a shell that suddenly rendered nothing.
4. **Testing the "closes on navigation" behaviour had to click a link *inside* the drawer, not the existing
   test page's own link.** The temporary drawer is a real ARIA modal while open (`role="dialog"`,
   `aria-modal="true"`) — MUI hides the rest of the page from the accessibility tree while it's open, so
   `getByRole('link', …)` can no longer find `TestPage`'s own "Go to /other" link once the drawer is open.
   That's correct behaviour (a real user couldn't click it either), not a test bug to route around — the
   test now clicks `SidebarNav`'s own "Solutions" link instead, which is the only reachable destination.
5. **Brand appears in the app bar below `md`, not below `lg`.** The task's own line ("App bar below `md`:
   brand + menu button + account menu stay") only specifies the *composition* at `< md`; it's silent on
   whether brand shows in the `lg`-to-`md` band where the menu button already exists but nothing else has
   collapsed yet. Chose `< md` for brand specifically — anything wider still has the permanent drawer's own
   brand block one click away behind the menu button, and showing it earlier would just be one more thing
   competing for space in a toolbar that hasn't otherwise needed to shed weight yet.
6. **No explicit close (✕) button on the overlay drawer.** The task's Implementation bullet lists exactly
   three close triggers — Escape, navigation, scrim click — and the toggle button doubles as a fourth
   (clicking it again while open closes it, via `aria-expanded`/label swap). A fifth affordance wasn't asked
   for and the three specified ones are already redundant with each other.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run lint                                                                    # clean
npm run typecheck                                                                # clean
npm run lint:fixtures:assert                                                     # 10 files, intended rule each
npm test -- src/app/shell --environment jsdom                                    # 2 files, 24 tests — all passed
npx vitest run --max-workers=4                                                   # full suite: 144 files, 659 tests — all passed
npm run build                                                                    # production build succeeds
```

(`npm run test:a11y` from the task's own Verify line doesn't exist as a script — pre-existing across the
plan, not this task's to fix; every prior task's report ran the a11y suite as part of the normal `npm test`
invocation instead, since `.a11y.test.tsx` files are ordinary Vitest tests in this one-project setup. Did
the same here.)

Task-specific acceptance items:

- **Every nav destination reachable at 320px** — `AppShell.test.tsx`: drawer starts closed below `lg`,
  opens via the labelled menu button, both nav destinations (`Home`, `Solutions`) render inside it.
- **Tenant never dropped** — `AppShell.test.tsx`'s dedicated test: absent from the app bar directly below
  `md`, present inside the opened account menu.
- **Focus trapped and restored** — `AppShell.test.tsx`: opens the drawer, presses Escape, asserts the drawer
  is gone *and* the trigger button has focus.
- **`assertReflow` passes on the shell** — two tests: the shell root at the closed state, and (since the
  open drawer's `Paper` portals to `document.body` via MUI's `Modal` — not a descendant of the shell root
  `assertReflow` walks) the drawer's own paper separately, both at `reflowFloorPx` (320).
- **Breadcrumb collapse preserves the current page** — `Breadcrumbs.test.tsx`: current crumb visible and
  un-linked below `md`; every ancestor reachable from the "…" menu; full trail restored at `md` and up; no
  "…" trigger on Home, where there is nothing to elide.
- **axe at 320 · 600 · 905 · 1240, both themes** — `AppShell.a11y.test.tsx`: 8 width×mode combinations, plus
  a ninth with the overlay drawer open at 320px — all clean.
- **Visually verified against the reference** — dev server, real Chromium, four widths
  (320/600/905/1240) on `cache-prod-01/designer`: permanent drawer with brand at 1240; menu button appears
  with the full breadcrumb trail still visible at 905 (the `lg`-to-`md` band); brand + collapsed breadcrumb +
  account-menu fold-in at 600; identical at 320, plus the open overlay drawer (dimmed backdrop, brand at
  top), the "…" menu (every ancestor crumb present), and the account menu (tenant, theme toggle, and the
  full support content block, none dropped).

## Status

DONE — implemented, tested (659/659 on the full run), lint-clean, typecheck-clean, production build
succeeds, visually verified in a real browser at all four named widths plus the interactive states. Not yet
closed on GitHub — pending review, particularly Ruling 5 (brand's `< md` threshold, a judgment call the task
text left open) and Ruling 6 (no explicit close button on the overlay drawer).
