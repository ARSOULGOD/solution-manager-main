# FE-123 · `DesignerPage` — the screen, and the route that renders it — Report

## What this closes

The ~25 Designer components built through task 059 had no caller — `InstanceShell` rendered a stated
"coming in a later phase" placeholder for the `designer` category. This composes them into an actual screen
and wires it into the real Instance → Designer route, discharging `REQ-001`/`REQ-006`'s v1 gate: open any
instance, see its real configuration, editable from the package alone.

## What was built

**`DesignerPage`** (`DesignerPage.tsx`) routes between four states and does nothing else — the fifth,
composer, is `FE-124`:

- **skeleton** — while `configRepository.loadDesigner()` is in flight.
- **error-with-retry** — `ErrorPanel` wired to `query.refetch()`.
- **empty** — `deriveComponentSelection(pkg, packageVersion)` returns nothing for this version; states so
  rather than rendering blank.
- **form** — `DesignerForm`, keyed on `load.configGeneration`.

**`deriveComponentSelection`** (`componentSelection.ts`) is the single source of truth for which components
exist, filtering through the contract's own `inVersion()`. Per `FE-D35`, it returns the degenerate
`exactly-one` case — one entry per declared component, `instanceId = type` — and says so in its own header:
`one-of-many` composition is not invented, because nothing in the console produces that composition data.

**`isEditingBlocked`** (`observedState.ts`) mirrors `project()`'s own `editingEnabledFor` exactly, so
`SaveBar`-hiding and the `disabled` flag threaded to every field-editing component in `DesignerForm` can
never disagree with what `project()` itself decided.

**`DesignerForm`** assembles the already-built components into the reference's own layout (sticky section
nav, scrolling form pane, pinned save bar): `VersionPill` + package-version chip, `BlockedBanner`,
`ValidationSummary`, `FieldSearch`, `SectionCard` per section (or `FieldSearch`'s own hits when a search is
active), `PackageActions`, `SaveBar`, plus `ConflictDialog`/`AcceptRuntimeDialog`/`RestoreRuntimeDialog` as
modals. Nothing here restates projection, validation or save logic — every rule lives in the component that
already owns it.

- **One combined `project()` call** (`useDesignerDraft`) across the whole `componentSelection`, correct for
  Cache (one component) and for any package whose field keys are unique across the whole package (both
  shipped packages are, `file-mesh` by the `FE-122` disambiguation). A `fieldOwner` map built from the
  package's own `components[].fields[]` is what lets one `onFieldChange` route an edit to the right
  component instance. `FE-124`'s Composer replaces this for a genuinely multi-component canvas.
- **The generation keys the draft** (`FE-D20`). `DesignerPage` holds `load` as local state, seeded from the
  query via the "adjust during render" pattern (`Composer.tsx`'s own convention — no `useEffect`, no
  `setState`-in-effect lint violation) rather than merged. `DesignerForm` is remounted via
  `key={load.configGeneration}` whenever a save, a conflict refetch, or an accepted/restored runtime replaces
  it, so `useDesignerDraft`'s `initialDraft` is always stable for the component's lifetime and nothing is
  ever an optimistic edit.
- **No source for the accept/restore/conflict diff rows** (§16 Q9/Q10) — passed as `[]`, stated in a comment
  rather than fabricated from the retained `DIFF_DRAFT`/`CONFLICT_THEIRS` fixtures, which are a different
  shape (`DiffRowFixture`'s string secret token vs `DiffRow`'s `SECRET_MASK` symbol) representing one canned
  scenario, not a general answer for an arbitrary instance.

**`InstanceCategoryRoute.tsx`** now constructs `configRepository`/`applyRepository`/`actionsRepository`
alongside the existing `instancesRepository` (one `dpClient`, four repositories) and threads all three through
`InstanceShell` to `DesignerPage`. `applyRepository` is unused until `FE-124` — plumbed now so that task
touches no route or shell file, matching its own stated file list.

**`src/features/designer/index.ts` (new)** — the feature's public barrel, required because `InstanceShell`
lives in a *different* feature (`@features/instance`): the boundaries policy allows `feature → feature` only
through the target's `index.ts`.

## A real a11y bug, found by being the first real caller

`SectionCard`'s section-title `Typography` used `variant="subtitle2"` with no `component` override — MUI's
default variant mapping renders that as a real `<h6>`. Under `InstanceHeader`'s own `<h1>`, that skips five
heading levels, an axe `heading-order` violation. Every earlier `SectionCard` test mounted it standalone, so
this was never exercisable until `DesignerForm` became the first real page-level composition. Fixed with
`component="span"` — the header text is a toggle button's label, not a document heading, so no semantic
heading was actually intended.

## Testing

- `DesignerPage.test.tsx` — all four reachable states from a stub `ConfigRepository`; a v6 instance renders
  no v7-only field, a v7 one does; a discriminator flip reveals/hides a dependent field with the
  `loadDesigner` spy asserted at exactly one call throughout; a saved value (draft) beats the package
  default; version drift hides the save bar and disables the fields.
- `DesignerPage.a11y.test.tsx` — editable and blocked states, both colour modes, zero violations.
- `componentSelection.test.ts` / `observedState.test.ts` — the two pure-logic modules in isolation.
- `InstanceShell.test.tsx` — rewritten (not deleted) per this task's own acceptance criterion: the `designer`
  category now asserts real `DesignerPage` content and the ABSENCE of placeholder text, alongside the
  pre-existing loading/error/outcome-badge/governance-placeholder coverage, all switched to real
  MSW-fixture-backed repositories.
- `InstanceShell.a11y.test.tsx` — now waits for the real Designer body (not just the header) before auditing.
- **Manual browser verification** (`npm run dev`, Playwright, both requested by CLAUDE.md for UI changes):
  navigated to `cache-prod-01`'s Designer, confirmed no placeholder text, expanded the General section,
  edited `Namespace`, watched the blocking-issue count and unsaved-change count update live, confirmed Save
  correctly stays disabled while `key-source`/`solace.host`/`max-size` remain empty per the real fixture
  draft. Separately confirmed `cache-prod-03` (declared at `packageVersion: 6`) shows `pkg v6`, renders `Max
  entries` and correctly omits `Max weight` (v7-only). Zero browser console errors in either check.

## Files

| File | Change |
|---|---|
| `src/features/designer/DesignerPage/{DesignerPage.tsx,DesignerPage.types.ts,DesignerForm.tsx,componentSelection.ts,observedState.ts,index.ts}` | New. |
| `src/features/designer/index.ts` | New — the feature's public barrel. |
| `src/features/designer/SectionCard/SectionCard.tsx` | `component="span"` fix for the heading-order bug found above. |
| `src/features/instance/InstanceShell.tsx` | Renders `DesignerPage` for the `designer` category; three new required repository props. |
| `src/app/routes/InstanceCategoryRoute.tsx` | Constructs and threads `configRepository`/`applyRepository`/`actionsRepository`. |
| `src/shared/i18n/messages.en.ts` | `instanceDesignerPlaceholder` deleted; four `designer*` keys added. |
| `src/features/instance/InstanceShell.test.tsx`, `InstanceShell.a11y.test.tsx` | Rewritten for the real `DesignerPage`, not deleted. |
| `src/features/designer/DesignerPage/{DesignerPage.test.tsx,DesignerPage.a11y.test.tsx,componentSelection.test.ts,observedState.test.ts}` | New tests. |

## Acceptance

- ☑ the Designer renders from a package alone, zero per-solution React
- ☑ a package declaring nothing for this version says so rather than rendering blank
- ☑ no refetch on a discriminator change (asserted via a `loadDesigner` call-count spy)
- ☑ `InstanceShell`'s placeholder test rewritten, not deleted

## Verification

`npm run lint` · `npm run typecheck` · `npm test` (1303 tests) · `npm run build` — all green. Manual browser
verification per above (console clean in both checks).

## Not done here (deferred to `FE-124`, per its own file list)

Multi-component solutions (File Mesh) render through the same combined-`project()`/`fieldOwner` path as
Cache rather than the Composer canvas — correct as far as it goes (field keys are package-wide unique in
both shipped packages) but not the real multi-component UI `UI-D1` describes. `RolloutCard`/`StrategyPicker`/
`RestartConfirm`/`ApplyOutcomePanel`/`DivergedBanner`/`RollbackDialog` are built but not yet wired onto the
page — `SaveBar`'s `onApply` is left unset, so Apply is visually enabled at `dirtyCount === 0` but does
nothing yet, exactly the seam `FE-124` fills.
