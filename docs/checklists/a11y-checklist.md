# Checklist — accessibility

Target WCAG 2.2 AA (`dynamic-metrics/design.md` §18). Automated checks catch structural defects;
they do not catch everything — the manual keyboard script
([`a11y-manual-keyboard-script.md`](a11y-manual-keyboard-script.md)) exists because axe cannot walk
a focus trap, a keyboard-only chart cursor, or a jump-to-field scroll for you.

## Automated (every PR touching a screen or shared component)

- [ ] `npm run test:a11y -- <path>` is clean for every file changed (task 098; runs axe/jest-axe
      against the touched `.a11y.test.tsx` files — add one if the change adds a new screen or
      shared component and none exists yet)
- [ ] Both themes checked, not just the default — every `.a11y.test.tsx` in this repo is
      parametrized `it.each(['light', 'dark'])`; a new one should be too
- [ ] If the change adds a component to `Gallery.tsx` (`src/app/dev/Gallery.tsx`), re-run
      `npm run test:a11y -- src/app/dev` — the assembled gallery is checked as one page, and two
      landmark-bearing demo instances shown side by side (e.g. a rail's expanded/collapsed states)
      can produce a **gallery-only** duplicate-landmark violation that no per-component test would
      catch; see `SidebarNav.gallery.tsx`'s own `aria-hidden` comment for the pattern that fixes it
      without losing per-component coverage
- [ ] No state is signalled by colour alone — every status carries an icon or a text label
      alongside the colour (cross-check against the state-vocabulary module, `StateChip`'s own
      `ALL_STATE_TAGS`-style source of truth)
- [ ] A canvas-rendered chart (`ChartRenderer.tsx`) ships its tabular alternative
      (`ChartDataTable.tsx` / `ChartDataTableContent`) — a chart with no non-visual alternative is
      never acceptable, "residual, not silently decided" is not a valid posture here
- [ ] `npm run verify:reflow` is clean (task 110, `FE-D29`): every screen reflows with no
      horizontal scroll at 320 · 375 · 600 · 905 · 1240 · 1560 CSS px, any container that does
      scroll horizontally declares it via `data-reflow-exception="data-table" | "persistent-toolbar"`
      (an undeclared scroller fails the build — see `e2e/reflow/reflowCheck.ts`), a real
      `axe-core` pass is clean at 320 px and desktop in both themes (WCAG 2.x A/AA tags;
      `color-contrast` excluded pending open item 19), and no screen locks to portrait or
      landscape (SC 1.3.4)

## Manual (before a release — see the script for what "run" means)

- [ ] The manual keyboard script has been run this release and its results recorded in the script's
      own log table
- [ ] Any item that regressed is either fixed before release or explicitly accepted with a reason
      recorded in the log — never silently skipped

## Why this exists

`dynamic-metrics/design.md` §18 flagged its own gap: *"There is no accessibility checklist in
`docs/checklists/` — this section is the proposal, and adding a checklist entry is routed in §21."*
Task 098 is that routing. A checklist item earns its place only if it has caught a real problem —
the gallery-composition item above did, on the first run of the new `Gallery.a11y.test.tsx`
(a genuine `landmark-unique` violation from two `SidebarNav` demo instances sharing one
`aria-label`), which is why it is stated as its own bullet rather than folded into "run the tests."
The `verify:reflow` item above earned its place the same way: its first real run against the app
(not the self-test fixtures) caught two genuine defects invisible to `jest-axe`'s jsdom shim —
`index.html`'s static skip link pointing at `#main-content` while `AppShell.tsx` rendered
`id="main"` (broken on every authenticated screen), and `InstanceSelector.tsx`'s scrollable
instance list having no `tabIndex`, both fixed by task 110 rather than left for a later task.
