# FE-148 · Three shell/monitoring UI defects — Report

Three defects reported from the running app, not from a plan task: the app bar's identity label, the
Dashboards toolbar's alignment, and the account popover's rows. All three were found by looking at the
screen, and all three are fixed against the `FE-D30` reference rather than against taste.

---

## 1 · The app bar said "Tenant"

**What was wrong.** After `FE-D46` the console draws, filters and grants against
`Organization → Sub-org → Environment`. "Tenant" then survived in exactly one place in the entire UI — the app
bar — naming a concept nothing else on screen used. `FE-D46` was careful to separate the two senses of the
word in the *documentation* (isolation boundary vs hierarchy level); on screen they were still colliding, and
an operator had no way to read that chip as anything but a fourth, higher hierarchy level they could not find.

**What changed.** The label and its tooltip only. `TenantDisplay` → `OrganizationDisplay`
(`app/shell/OrganizationDisplay.tsx`), prop `tenant` → `organization`, messages
`tenantLabel`/`tenantTooltip` → `organizationLabel`/`organizationTooltip`.

**What deliberately did not change** — `I5`, `REQ-003` and `FE-D8` are untouched:

- `Identity.tenant` keeps its name and its **no-`tenantId`-sibling** shape.
- The value is still server-returned, still never sent back, still never a route segment.
  `routes.test.ts` and `router.test.tsx` are untouched and green.
- The chip is still text inside no `button` and no `a` — `AppShell.test.tsx` asserts it directly, and that
  test passes unchanged because it matches on the *value*, not the label.

The seam (prop `organization`, field `tenant`) is documented in `OrganizationDisplay`'s own header, at the one
place it exists.

**Recorded as `FE-D51`**, superseding one clause of `FE-D46` — the sentence "the app-bar chip keeps displaying
the server-returned string." `FE-D46` is **not edited** (`I3`). The entry records the flagged divergence from
the `FE-D30` reference, which still renders `<span className="tk">Tenant</span>` because it predates `FE-D46`.

---

## 2 · The Dashboards toolbar did not line up

**What was wrong**, measured in the browser rather than guessed — the five children of the toolbar row came
back **75 / 53 / 39 / 53 / 32 px tall**, every one a different height, all vertically centred, so no two
controls shared an edge. Three separate causes:

1. **Mixed control sizes.** The selects rendered at MUI's default `medium` (56px) beside a `small`
   `Auto/Raw` toggle (39px) and `sm` buttons (32px). The reference's `select.bare` is **40px** and its `.tbg
   button` **36px** — one dense row.
2. **Captions inside the row.** `TimeRangePicker` stacked the resolved-window caption under its own select,
   and the refresh `Select` stacked its floored/paused/disabled note under its own. That made those two
   children 75px and 53px tall and pushed their *inputs* off the row's centre line. Worse, the caption
   `Sep 4, 2026, 11:34:34 AM – Sep 5, 2026, 11:34:34 AM GMT+5:30` is 369px wide and the select above it was
   `fullWidth` — so the caption's width **dictated the time-range control's width**. That is the width
   mismatch, and it is why the time-range select was 369px next to a 104px layer select.
3. **No declared widths.** Nothing pinned a control's width, so the row resized as option labels changed.

**What changed.**

- `Select` gains `size?: 'small' | 'medium'`, defaulting to `'medium'` — every existing form screen keeps the
  height it had. The toolbar passes `'small'` (40px, the reference's own number).
- `TimeRangePicker` splits into `<TimeRangePicker>` (the select) and `<TimeRangeDetails>` (custom bounds +
  the zone-labelled resolved window, `B/D15` 🔒). It renders both by default, so **Explore is byte-for-byte
  unchanged**; the toolbar passes `showDetails={false}` and places `<TimeRangeDetails>` itself.
- The toolbar becomes a sticky shell containing **a controls row and a notes line**. Every control on the row
  is one height with a declared width (`CONTROL_WIDTH`). The notes line carries the resolved window, the
  refresh note, and `DM-D21`'s "authoritative for every widget" sentence.

**The placeholder bar.** That `DM-D21` sentence was a bare full-width grey `Typography` floated under the
toolbar on `mt: '-8px'` — a negative-margin hack holding an unstyled line of text in place. It explains these
controls, so it moved onto their notes line and the hack is gone. `dashboardViewAuthoritativeNote` is renamed
`dashboardToolbarAuthoritativeNote` to match where it now lives.

**The clipped labels — a defect the fix exposed and then fixed.** Once the row was 39px tall the outlined
selects' floating labels were being **cut in half**. Cause: `overflow-x: auto` computes `overflow-y` to `auto`
too, so that row clips vertically whether asked to or not. Two things live outside a control's own box — the
floating label (~9px above the input) and the 4px focus ring `createSmTheme` paints — and both were being cut.
The row now carries `py: '10px'` to give them room. The old layout only avoided this by being tall enough by
accident.

**SC 1.4.10.** The persistent-toolbar exception now sits on the row that actually scrolls, not the root.
`reflowCheck.ts` resolves it with `closest()`, so nesting is fine; the unit test was updated to follow the
attribute rather than assume the root is the scroller.

---

## 3 · The account popover was a list of bare strings

**What was wrong.** `Targets`, `Admin` and `Sign out` rendered as MUI's default full-bleed text rows: no
icons, no rules between groups, no rounded hover strip, and a `ListSubheader` group label in sentence case.
The reference (`reference/app/shell.jsx`, `.mi` in `theme.css`) draws each as a **36px rounded strip — leading
icon, label, hover fill** — with `hr.divider` between the identity block, the destinations and Sign out.

**What changed** — the geometry went into `shared/ui`'s `Menu`, once, not into the call site:

- `MenuItemDef` gains `icon`, `trailing` and `selected`; a new `MenuDividerDef` (`kind: 'divider'`) renders a
  rule.
- Rows get `.mi`'s geometry: gap 11px, padding 9/11px, `minHeight` 36, radius, 13.5px label. The list gets
  6px of padding so a hovered row's rounded corners have somewhere to sit. The icon inherits the row's colour,
  so `danger` tints the glyph with the label instead of leaving a grey icon beside red text.
- The caption row becomes the reference's `.mi-cap` — 10.5px, uppercase, tracked.
- `AccountMenu` supplies `MapPinIcon` / `ShieldIcon` / `LogoutIcon` (added to `app/shell/icons.tsx` as the
  reference's exact Lucide paths, stroke 1.75) and the two dividers.

**Accessible names are unchanged.** The icons are `aria-hidden` (every `SvgIcon` in this repo already is) and
the label sits in its own span, so `getByRole('menuitem', { name: 'Targets' })` still matches — which is why
`AppShell.test.tsx` passed without edits. Trailing `preview` badges were deliberately **not** added even
though the reference has them: they would have changed those accessible names to "Targets preview", and the
`PREVIEW` caption above already says it.

**One regression, caught by screenshotting the narrow breakpoint and fixed.** Giving the paper a fixed
`width: 230` (the reference's per-menu number) clipped the support panel that folds into this same menu below
`md` — the correlation id and its **Copy id** button ran off the right edge. `width` is now a `minWidth` with
`maxWidth: calc(100vw - 32px)`: desktop keeps the reference's proportions, the collapsed menu sizes to what it
carries, and neither can grow off-screen.

---

## Verification

| Check | Result |
|---|---|
| `npx tsc -p tsconfig.json --noEmit` | clean |
| `npm run lint` | clean (`--max-warnings=0`) |
| `npm test` | 2381 tests pass |
| `npm run verify:reflow` | **137 passed** — reflow + axe at 320px and 1560px, light and dark, every screen including `dashboard` |
| Visual | Dashboard, Explore, dark mode and 380px captured in a real browser before and after; the toolbar row measured child-by-child |

`npm run format` reports 207 pre-existing failures repo-wide; the two touched files it flags
(`DashboardToolbar.test.tsx`, `messages.en.ts`) were already unformatted before this change, verified by
stashing and re-checking.

**One test changed, and only for structure**: the toolbar's SC 1.4.10 assertion now checks the sticky root and
the declared scroll row separately, because they are two elements now. Two assertions were added, covering the
notes line the toolbar gained.
