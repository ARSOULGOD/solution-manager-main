# FE-108 · Responsive Designer — Report

## What I implemented

A 132-field component, its section rail, its save bar and its composer, usable at 320 px (task spec:
`docs/plans/frontend/implementation.md` §FE-108; refs Dec `FE-D29` `UI-D20` `UI-D21` `FE-D17`, Design §10,
UI §A.8, WCAG 2.2 SC 1.4.10 / SC 1.3.2).

| File | Summary |
|---|---|
| `src/features/designer/SectionNav/SectionNav.tsx` | The existing list is extracted unchanged as `NavList`; `SectionNav` now picks the container. Persistent rail at `up('lg')`; below that a *"Jump to section"* `Button` opens the **same `NavList`** inside a temporary `Drawer`. The block/warn dots survive the collapse *by construction* — both branches render one component, so there is no second copy of the dot logic to drift. `onJump` is wrapped (not replaced) so selecting a section also closes the drawer. Same `up('lg')` breakpoint and permanent/temporary `Drawer` pattern `AppShell.tsx` (task 106) already established. |
| `src/features/designer/FieldRow/FieldRow.tsx` | The metadata cluster (discriminator hint, rendered path, `Default` hint, `Modified` chip) is lifted into one `metadataRow` node rendered **above** the control at `sm`+ and **below** it under `sm`. Nothing is hidden — each of those is a correctness signal. The DOM order genuinely changes rather than a CSS `order` flip, which would desync visual from focus/reading order (SC 1.3.2). `wordBreak: 'break-all'` on the rendered path. |
| `src/features/designer/SectionCard/SectionCard.tsx` | Header row gets `flexWrap`, `flexShrink: 0` on icon/count, and `minWidth: 0` + `wordBreak` on the title — a flex item's default `min-width: auto` sizes to its longest unbreakable run, which is what forces a long section title to overflow at 320 px. |
| `src/features/designer/Composer/Composer.tsx` | Below `md`, `effectiveView` forces `'list'` regardless of the operator's toggle state, and the toggle itself is replaced by an `Alert` stating why. No mobile canvas is invented — the flat "All fields" list task 063 already ships as the non-pointer path is reused. Forcing rather than merely defaulting means shrinking the window while parked on the canvas lands on the list, not a broken grid; replacing rather than disabling the toggle means no tap that does nothing. Node positions stay presentation-only and out of the config hash (`UI-D20`) — untouched. |
| `src/shared/ui/DiffView/DiffView.tsx` | New `StackedDiffView`: below `sm` the two-column table becomes one card per row, each value labelled by its own side (`labels[0]`/`labels[1]`) rather than by column position. Both layouts call the **same** `cellValue()`, and the rename warning + `secret · masked` chip are shared as `RowMeta` — so a secret row renders masked identically in either (`I6`/`G7`). |
| `src/shared/ui/Dialog/Dialog.tsx`, `Dialog.types.ts` | New opt-in `fullScreenBelow?: BreakpointKey` driving MUI's own `fullScreen`. `useMediaQuery` is called unconditionally; the `?? 'xs'` fallback queries `down('xs')`, which never matches (the `xs` floor is 0), so an omitted prop resolves `false` without a conditional hook call. Every caller predating this task keeps its current centred behaviour at every width. |
| `AcceptRuntimeDialog.tsx`, `ConflictDialog.tsx`, `RollbackDialog.tsx` | Opt in with `fullScreenBelow="sm"` — the three diff/conflict/rollback dialogs the task names. |
| `src/features/designer/controls/ListControl/ListControl.tsx`, `MapControl/MapControl.tsx` | Row `flexDirection` switches to `column` below `sm`. A `fullWidth` `TextField` (times two, for the map) plus a remove button in an unwrapped row overflows well before 320 px. `data-list-row`/`data-map-row` hooks for the tests. |
| `src/shared/i18n/messages.en.ts` | New keys: `sectionNavJumpButton`, `composerCanvasUnavailableReason`. |
| `src/features/designer/DesignerReflow.test.tsx` | New. The task's own 100-field fixture (10 sections × 10 fields) at the 320 px floor. |
| `SectionNav`, `SectionCard`, `FieldRow`, `SaveBar`, `Composer`, `DiffView`, `Dialog`, `ListControl`, `MapControl` tests | New responsive coverage (below). |

`SaveBar.tsx` itself needed **no change** — it was already a wrapping, sticky flex row; `assertReflow` at
320 px on its worst case (dirty count + blocking chip + recommendation chip + both actions) passes against
the existing markup. Only its test grew. Claiming a layout change here would have been a change made to
have something to report.

## Rulings

1. **The drawer renders the same `NavList`, it does not reproduce it.** The task's acceptance line is
   "block/warn dots survive the rail collapse". A second rendering path with its own dot logic would satisfy
   that on the day it was written and silently drift later. Extracting `NavList` and giving the two
   breakpoints different *containers* makes the property structural.
2. **FieldRow reorders the DOM, not just the visuals.** `flexDirection: column-reverse` or CSS `order` would
   have been a one-liner, and both would have left focus/reading order pointing up while the eye reads down
   — SC 1.3.2. The `useMediaQuery` branch renders the same node in a different position instead.
3. **`fullScreenBelow` is opt-in, with no default.** Making it default to `'sm'` for every `Dialog` would
   have silently converted ~12 existing dialogs across inventory/auth/designer into full-screen sheets on a
   phone, none of them reviewed for it. The three dialogs the task names opt in; nothing else changes.
4. **The Composer *forces* the list and *states why*, rather than defaulting or disabling.** Three options
   were live: default to list (but leave the toggle usable — lands the operator on a broken grid), disable
   the toggle (an inert control invites a tap that does nothing), or replace it with the reason. The last
   is the only one that both prevents the broken state and explains it, and it is what the task's
   "says why" asks for.
5. **`ListControl`/`MapControl` were not in the task's `Files` list but were in its acceptance.** "Every
   field reachable *and editable* at 320 px" is not satisfied by a control whose own row overflows. They are
   reached through `FieldRow`, so they are in scope for the acceptance criterion even though the spec's file
   list stopped at `FieldRow`.
6. **The 100-field reachability test mounts one section at a time.** Mounting all 100 MUI `FieldRow`s
   simultaneously starved the vitest worker pool enough to time out unrelated files. Every field is still
   visited — the test jumps through all 10 sections and asserts each section's 10 rows by
   `data-field-key`, editing one per section plus field 100. The comment in the test says so, so the next
   reader does not "fix" it back.

## A pre-existing test-suite flake, fixed (not part of FE-108)

Verifying FE-108 surfaced a failure that reproduces on the **committed baseline** with all FE-108 changes
stashed — so it is not something this task introduced:

```
FAIL src/features/designer/ReplicaTable/ReplicaTable.a11y.test.tsx
  Error: Test timed out in 5000ms
  Unknown Error: Axe is already running. Use `await axe.run()` to wait for the previous run to finish
```

Root cause: a single `axe()` pass over a rendered MUI tree costs ~0.3 s idle but 10–20× that when the worker
pool is saturated, blowing Vitest's 5 s default. The failure is not benign — a timed-out case leaves axe
mid-run, so **every later case in the same file** dies with "Axe is already running". One wide run took out
20 tests across 6 files this way. Two fixes, both minimal:

- `ReplicaTable.a11y.test.tsx` — was looping both fixtures *inside* one `it`, so each case carried two
  renders and two axe passes. Restructured to the 2 modes × 2 rollouts cross-product, one render + one axe
  pass per case, matching every other a11y test in the repo.
- `vitest.config.ts` — `testTimeout: 20_000` on the `unit` project, clear of the loaded worst case but
  still short enough to catch a real hang.

This is flagged separately because it changes a file outside the task's scope (`vitest.config.ts`) and
because the timeout choice is a judgement call worth reviewing on its own.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run lint                                                    # clean
npm run typecheck                                               # clean
npx vitest run src/features/designer src/shared/ui --environment jsdom
                                                                # 137 files, 545 tests — all passed
npm test                                                        # full suite: 215 files, 1057 tests — all passed
npm run build                                                   # production build succeeds
```

(`npm run test:a11y` from the task's own Verify line does not exist as a script — the same pre-existing gap
noted in the FE-106 and FE-107 reports; `.a11y.test.tsx` files ran as ordinary Vitest tests instead.)

Task-specific acceptance items:

- **Every field reachable and editable at 320 px** — `DesignerReflow.test.tsx`: the 100-field fixture at
  `reflowFloorPx`, jumping through all 10 sections, asserting each section's 10 rows by `data-field-key` and
  firing a real `change` on one field per section plus field 100, each verified through `onFieldChange`.
- **Section jump moves focus** — `DesignerReflow.test.tsx`: with section 10 collapsed and `Field 91` absent
  from the DOM, opening the *"Jump to section"* drawer and clicking Section 10 expands it, puts DOM focus on
  `Field 91`, calls `scrollIntoView`, and closes the drawer.
- **Block/warn dots survive the rail collapse** — structural (Ruling 1: one `NavList`, two containers);
  `SectionNav.test.tsx` pins it behaviourally — below `lg` the rail is absent until the drawer opens, and the
  entry inside the drawer still carries its blocking signal (`aria-label` = `"Broker — 1 blocking issue"`).
- **Save ≠ apply at every width** — asserted at 320 px in *two* places: `DesignerReflow.test.tsx` (`Save
  draft` enabled, `Apply…` disabled while dirty) and `SaveBar.test.tsx`'s reflow case (both disabled with a
  blocking count). The rule is unchanged code; the tests pin that the layout work did not touch it.
- **Secrets masked in the stacked diff** — `DiffView.test.tsx`: at 320 px a secret row yields exactly two
  `[data-diff-side]` nodes, both `••••••••`, with the `secret · masked` chip present and `hunter2` absent
  from `container.textContent`. Confirmed in a real browser below.
- **No mobile canvas invented** — `Composer.test.tsx`: below `md` the view toggle is gone, the "All fields"
  controls are present, and the reason string renders; at 905 px the canvas and its `Component types` nav
  are back, toggle intact.
- **No horizontal scroll at 320 px** — `assertReflow(root, reflowFloorPx)` on `SectionCard`, `SaveBar`,
  `Composer` and `DiffView`, per the task's "`assertReflow` on each Designer scenario".
- **axe clean** — the existing `.a11y.test.tsx` files for every touched component still pass, both themes.

**Real-browser confirmation (partial, and here is the limit).** The Designer route
(`/solutions/:s/instances/:i/designer`) still renders `InstanceShell`'s placeholder — the Designer page is
not assembled yet (task 044's own comment: "Real content per category is Phase 4/5/6"), and no Designer
component has a `*.gallery.tsx` entry. So the SectionNav drawer, FieldRow stacking, SaveBar and Composer
could **not** be verified in a real browser; jsdom + `assertReflow` is all that is available for them today.
What *could* be checked was, via `/dev/gallery` in real Chromium (Playwright):

- at 320 px — `document.body.scrollWidth === documentElement.scrollWidth === clientWidth === 320`, i.e. no
  page-body horizontal scroll, with zero console errors;
- `DiffView` renders **1** `[data-diff-stacked]` container and **0** tables at 320 px, and **0** stacked /
  **1** `table[data-diff-view]` at 1280 px — the transform fires on the real breakpoint, not just in jsdom;
- the 320 px screenshot shows the card-per-row layout with `Deployed`/`Draft` side labels, the rename
  warning preserved, and `broker.password` masked on **both** sides under its `secret · masked` chip.

`Dialog`'s `fullScreenBelow` has no gallery entry exercising it (the gallery's dialog predates the prop and
does not opt in), so the full-screen sheet is covered by `Dialog.test.tsx` only, not in-browser.

## Status

DONE — implemented, tested (1057/1057 on the full run), lint-clean, typecheck-clean, production build
succeeds. Real-browser verification is partial and bounded as stated above, because no assembled Designer
page exists to load yet; that gap closes when Phase 4's page wiring lands and is worth re-checking then.
Not yet closed on GitHub — pending review, particularly Ruling 3 (`fullScreenBelow` opt-in rather than a
default), Ruling 4 (the Composer forcing the list view) and the out-of-scope `vitest.config.ts` timeout
change described above.
