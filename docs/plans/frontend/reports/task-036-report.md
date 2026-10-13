# FE-036 · AppShell — Report

## What I implemented

The persistent chrome: navy drawer, white app bar, breadcrumbs, tenant display, theme toggle, support menu,
account menu, skip link, focus management (task spec: `docs/plans/frontend/implementation.md` §FE-036; refs
`REQ-003`, `D9` 🔒, `FE-D8`, `FE-D13`, design §9/§14/§18, UI §A.2).

| File | Summary |
|---|---|
| `src/app/shell/AppShell.tsx` | Root layout: skip link → `#main`, `Sidebar` primitive (task 105) holding `SidebarNav` (task 024) + a column of `AppBar` + `<main id="main" tabIndex={-1}>`. On every `location.pathname` change, focuses `<main>` and scrolls it to top. `<Outlet>` wrapped in `RouteErrorBoundary` (task 006) so a failing route leaves the shell mounted. `<Toaster>` (task 021) mounted once here. |
| `src/app/shell/AppBar.tsx` | White, sticky app bar (64px, `tokens.console.appbarHeight`): `Breadcrumbs` (task 024) · spacer · `TenantDisplay` · `ThemeToggle` · `SupportMenu` · `AccountMenu`. |
| `src/app/shell/TenantDisplay.tsx` | Tenant as non-interactive text with a `title` explaining it's server-provided, never a switcher (`I5`, `FE-D8`). |
| `src/app/shell/SupportMenu.tsx` | Help-icon trigger opening a `Menu` content panel: UI version (`buildInfo`), a session id, and a copy button. |
| `src/app/shell/AccountMenu.tsx` | Avatar trigger opening a `Menu` content panel: name/email, role `Chip`, `DEV`-only Targets/Admin preview links, sign out. |
| `src/app/shell/ThemeToggle.tsx` | Light/dark `ToggleGroup`, backed by `useUiStore` (task 003). |
| `src/app/shell/icons.tsx` | Inline `SvgIcon`s (sun/moon/help/copy) — `@mui/icons-material` is not a dependency, same pattern as `Button`'s danger marker and `shared/ui/internal/toneIcons.tsx`. |
| `src/app/shell/AppShell.test.tsx`, `AppShell.a11y.test.tsx` | Component + axe coverage against AppShell's own nested route tree (see Ruling 1). |

Three small, directly-necessary extensions to already-shipped shared components:

| File | Change |
|---|---|
| `shared/ui/Menu/{Menu.tsx,Menu.types.ts,index.ts}` | New `MenuContentDef` entry kind (`{ kind: 'content', content: ReactNode }`) — a free-form panel row alongside the existing `item`/`caption` kinds, for the identity block and the support-info panel neither fits into a clickable `MenuItem`. |
| `shared/ui/Button/{Button.tsx,Button.types.ts}` | `IconButtonProps` gained `aria-haspopup`/`aria-controls` (already present on `ButtonProps`) and `IconButton` now forwards them. Without this, an icon-only `Menu` trigger silently dropped its menu-trigger ARIA wiring — a real, previously-untested gap since every existing `Menu` consumer used the text `Button`, not `IconButton`. |
| `shared/ui/ToggleGroup/{ToggleGroup.tsx,ToggleGroup.types.ts}` | `ToggleOption` gained an optional `'aria-label'`, forwarded to each `ToggleButton` — needed for the icon-only light/dark toggle to have an accessible name per option (`FE-D9`: an icon-only control needs its own name). |

Plus the required lint guardrail and its fixture:

| File | Change |
|---|---|
| `frontend/eslint.config.js` | New `NO_DEMO_STATES` `no-restricted-syntax` rule forbidding the identifier `DEMO_STATES` outside `shared/testdoubles` (task spec: "the *Demo state* selector and the per-screen *Scenario* selectors... must not ship"). |
| `src/test/lint-fixtures/app/demo-states.ts` (+ `README.md`, `scripts/assert-lint-fixtures.mjs`) | Fixture proving the rule fires, following the existing one-fixture-per-guardrail pattern from task 009. |

`src/app/boot/buildInfo.ts`'s header comment said "task 049's support menu" — a stray forward-reference from before
the task register settled; corrected to "task 036" since this file is what `SupportMenu.tsx` actually consumes.

## Rulings

1. **AppShell is built and tested standalone; it is not mounted into `router.tsx` in this task.** The Files list
   is exactly the six `src/app/shell/*` files; `router.test.tsx` currently renders every route with no
   `AuthProvider` in scope, so nesting the real route table under `RequireAuth` + `AppShell` would need
   `useAuth()` to resolve everywhere those tests deep-link — a change to Phase 2 test infrastructure this task's
   Files list doesn't ask for. `RequireAuth.tsx`'s own header already defers "wraps a route element in
   `router.tsx`" as "Phase 3 wiring," and `RouteErrorBoundary.tsx`'s header independently confirms *how* AppShell
   wraps `<Outlet>` once wired — so the component is built to spec and proven against its own nested test route
   tree (`AppShell.test.tsx`), ready for whichever task actually restructures `router.tsx` (plausibly FE-037,
   which owns the Login screen the guard redirects to). Flagging this explicitly since otherwise AppShell would
   look "finished" while still being unreachable from the running app.
2. **The sidebar's solution list renders empty (`solutions={[]}`), not wired to live data.** `SidebarNav` (task
   024) needs `SidebarNavSolution[]`; `solutionsRepository.ts` (already built ahead of schedule, task 033) returns
   a different shape (`SolutionPackageSummary[]`) and needs a plane client, which nothing yet distributes outside
   `AppProviders`'s own local `useMemo`. Building that distribution mechanism (a context, most likely) isn't in
   this task's Files list and would be invented ad hoc rather than designed for the several features that will
   need it (Home, Solutions catalog). Rendering `[]` is the honest state given that — SidebarNav already handles
   it gracefully (shows "Solutions (0)"). Worth noting for the record: a literal `(0)` for a genuinely-unknown
   count is in tension with `UI-D8` ("absence is never zero") elsewhere in this same design; I judged that
   `UI-D8` governs *data* screens (Home's stat tiles, explicitly, which render `—`) rather than `SidebarNav`'s
   fixed count-in-label format from task 024, which I did not reopen. Flagging for a second opinion rather than
   resolving it myself.
3. **Support menu's "session correlation id" is a client-generated id distinct from the per-request
   `X-Correlation-Id`.** `FE-D13` and `shared/api/correlation.ts` (task 026) are explicit that the *response's*
   correlation id is the only one that reaches the UI/logs — never the client-sent one — for exactly one request.
   The reference `shell.jsx` generates its own `"sess-" + random()` id for the support panel instead, for a
   different purpose (letting a human quote *something* stable to a support agent for the browser session, not
   auditing a specific request). I reused `generateCorrelationId()` (same generator, same entropy/format) rather
   than inventing a second one, and added a dedicated `correlationIdLabel` i18n key distinct from the existing
   `correlationId: 'Correlation id {id}'` (used elsewhere as one interpolated sentence) since this panel needed
   a label/value pair, not a sentence.
4. **`Menu`'s `content` entries do not auto-close the menu on click** (unlike `item` entries). The support panel's
   "Copy id" button and any future interactive content inside a content row should stay open after use; only
   outside-click/Escape/an explicit `item` selection closes the menu, which was already `Menu`'s behaviour for
   everything else.
5. **`IconButtonProps`/`Button.test.tsx` and `ToggleGroup` changes are additive and backward compatible** — every
   existing caller (Menu.test.tsx's `<Button>` trigger, every `ToggleGroup` consumer with a text-labelled option)
   is unaffected; both new fields are optional and only change behaviour when supplied.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run lint                    # clean
npm run lint:fixtures           # 16 problems across 10 files — all on their intended rule (was 9 files/15 problems before this task's fixture)
npm run lint:fixtures:assert    # "lint-fixtures: 10 files failed on the intended rule"
npm run typecheck               # clean
npm run check:forbidden-deps    # no Pro/Premium packages
npx vitest run                  # 115 files, 477 tests, all passing
npm run build                   # production build succeeds, 995 modules
```

Task-specific acceptance items (`docs/plans/frontend/implementation.md` FE-036), each backed by a concrete test:

- **Focus moves to `<main>` on navigation** — `AppShell.test.tsx`: "focuses `<main>` on first render and again
  after navigating" clicks a link, asserts `document.activeElement` is `<main>` both before and after.
- **Tenant is not interactive** — "renders the tenant as non-interactive text, never a switcher": asserts the
  tenant text's closest ancestor is neither a `<button>` nor an `<a>`, and its `title` explains why.
- **No demo control** — "renders no demo-state or scenario selector" (component test) plus the new
  `no-restricted-syntax` rule + fixture (static/lint-time guarantee, not just a runtime assertion).
- **No bell** — "renders no bell / paging affordance": scoped to exclude `Toaster`'s `aria-live="Notifications"`
  region, which is acknowledgement (task 021), not alerting — the one thing in the shell whose accessible name
  contains "notification" and is intentional.
- **Axe** — `AppShell.a11y.test.tsx`, light and dark mode, waits for the DEV mock identity to resolve so the
  account-menu avatar (an icon-only trigger) is present before inspection.

## Status

DONE for the six-file scope in `docs/plans/frontend/implementation.md`'s FE-036 Files list — implemented, tested,
lint-clean, typecheck-clean, full suite green, production build succeeds. Not yet marked `✅` in
`task-list.md`/`execution.md` or closed as a GitHub issue — pending the user's review of Rulings 1–2 above, since
both describe scope this task deliberately did not close (router wiring; live sidebar data).

Two things for the user's attention before sign-off:

1. **Ruling 1** — AppShell is built but unreachable from the running app until a later task nests the route
   table under it. Worth confirming FE-037 (or a dedicated task) is expected to do that, rather than assuming
   it.
2. **Ruling 2** — the `UI-D8` tension around `SidebarNav`'s fixed "(0)" count format is real but pre-dates this
   task (task 024); flagging rather than silently deciding it.
