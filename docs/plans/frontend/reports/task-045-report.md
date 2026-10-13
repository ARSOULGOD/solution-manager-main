# FE-045 · Declare-instance stub and the 403 page — Report

## What I implemented

Two independent, unrelated-at-runtime pieces the task brief groups together (task spec:
`docs/plans/frontend/implementation.md` §FE-045; refs Dec `SM-D13` `D10` 🔒, Design §14/§15, UI §A.6 · §A.12).

| File | Summary |
|---|---|
| `src/features/inventory/instances/DeclareInstanceDialog.tsx` | New. Replaces the disabled `Declare instance` button task 041 shipped (no dialog behind it yet) with an **enabled** one that opens this dialog — plain `Dialog` (task 016), no `ConfirmDialog`, since there is nothing to confirm. States plainly that declaring an instance has no API, no field set and no entitlement-check shape yet, rather than inventing a form. No inputs, no submit. |
| `src/features/inventory/instances/DeclareInstanceDialog.a11y.test.tsx` | New. axe clean, light/dark — same pattern as `BulkRestartDialog.a11y.test.tsx`. |
| `src/features/inventory/instances/InstancesPage.tsx` | `DeclareInstanceButton` now takes `onClick` instead of always rendering `disabled`; wires both call sites (page-header action, empty-state action) to open `DeclareInstanceDialog`. Dropped the now-unused `Tooltip` import. Header comment rewritten — it no longer describes a disabled control. |
| `src/features/inventory/instances/InstancesPage.test.tsx` | Replaced the "disabled, with its reason stated" test with two: buttons are present and **enabled**; clicking one opens the dialog, the dialog's text matches the unspecified-surface notice, and `dialog.querySelectorAll('input, select, textarea')` is empty (task's own acceptance wording — "no invented declare form" — asserted structurally, not just by eyeballing copy). |
| `src/app/Forbidden.tsx` | New. The 403 page (proposed `FE-D27`). Takes only `{ correlationId: string }` — no permission-id prop exists on the component at all, which is what makes the "leaks no permission name" acceptance item structurally true rather than a copy-review promise. States the action isn't permitted, shows the correlation id, offers a `Button` back to `/` (`useNavigate`, same pattern `FirstRun.tsx` already uses — the shared `Button` has no `component`/`to` prop, so this is a click handler, not an `<a>`). |
| `src/app/Forbidden.test.tsx` | New. Asserts the correlation id renders; asserts **none** of `PERMISSION_CATALOG`'s ids (imported from `@shared/authz/catalog`, not hand-copied) appear in the rendered text; asserts the not-permitted copy; asserts clicking the back action lands on a `/`-routed marker. |
| `src/app/Forbidden.a11y.test.tsx` | New. axe clean, light/dark. |
| `src/shared/i18n/messages.en.ts` | Removed the now-unused `instancesDeclareComingSoon` (the disabled-tooltip copy); added `instancesDeclareDialogTitle/Body/Note` and `forbiddenTitle/Body/CorrelationId/BackHome`. |

## Rulings

1. **No live call site throws a real 403 yet.** Both `grantable` verbs (`target.use`, `instance.bindTarget`)
   belong to flows not built in this phase (target binding in Designer, the apply protocol — task 061 is
   `BLOCKED` on open item 1). `Forbidden` is therefore built and tested standalone, the same posture
   `BulkRestartDialog`'s `onConfirm` already takes for "the mechanism it would trigger doesn't exist yet" —
   not wired into a global error interceptor, since no task in this plan's dependency chain asks for one and
   inventing an app-wide 403-catching mechanism is a bigger architectural decision than this task's three
   deps (`041`, `016`, `032`) authorize.
2. **`Forbidden` renders no `<main>` of its own.** `NotFound.tsx` does (`<main id="main-content">`), which
   was harmless when it predated `AppShell` but is now a duplicate-landmark bug now that `AppShell` wraps
   every route's `<Outlet>` in its own `<main id="main">` (from this session's earlier fix mounting
   `AppShell` into `router.tsx`). `Forbidden` follows the pattern every *real* page already uses
   (`HomePage`/`InstancesPage`: `PageWidth` > `Stack`, no `<main>`) rather than copying `NotFound`'s
   pre-existing quirk forward into new code. `NotFound.tsx` itself is unchanged — out of scope here.
3. **The "no permission id" acceptance item is enforced by the type signature, not just the test.**
   `ForbiddenProps` has one field. There is no prop to accidentally leak a permission id through even if a
   future call site tried; the test iterating `PERMISSION_CATALOG` is a belt-and-suspenders check against
   the rendered *copy* (`forbiddenBody`) ever growing an interpolated permission name later.
4. **`severity="neutral"` for the containing `Alert`, not `err`.** A 403 isn't a fault to retry — closest
   existing precedent is `FirstRun.tsx`'s own admin-only notice (`Alert severity="neutral" icon={<LockIcon />}`)
   for "a different actor must act," not `ErrorPanel`'s retry-oriented `err` treatment. No icon override: MUI's
   default reference kit has no icon dependency in this app (`@mui/icons-material` is deliberately absent —
   see `home/icons.tsx`'s header), and inventing a one-off `LockIcon` scoped to `src/app/` for a single
   component felt like the wrong tradeoff versus `Alert`'s existing default tone icon, which already reads as
   "blocked, not broken."
5. **Two dialog "Close" buttons collide on accessible name.** `Dialog.tsx` (task 016) always renders an
   `aria-label={t('close')}` × icon in the titlebar; `DeclareInstanceDialog` also has a text "Close" action per
   its footer per the reference's own dialog-action convention (`LoadPackageDialog`'s Cancel/submit pair).
   Both resolve to the same accessible name — the new `InstancesPage.test.tsx` case picks the first match via
   `getAllByRole` (the existing `expander`-lookup test in the same file already establishes this
   assert-defined-then-narrow pattern under `noUncheckedIndexedAccess`), since either button exercises the
   identical `onClose`.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run lint                                                    # clean (0 warnings, --max-warnings=0)
npm run typecheck                                                # clean
npm test -- src/features/inventory --environment jsdom          # 13 files, 83 tests — all passed
npx vitest run src/app/Forbidden.test.tsx src/app/Forbidden.a11y.test.tsx   # 2 files, 5 tests — all passed
npx vitest run --max-workers=4                                   # full suite: 143 files, 635 tests — all passed
```

Task-specific acceptance items:

- **No invented declare form** — structural assertion in `InstancesPage.test.tsx`: the open dialog contains
  zero `input`/`select`/`textarea` elements, plus its body text is asserted against the exact "no API, no
  field set, no entitlement check" wording from the task brief.
- **403 leaks no permission name** — `Forbidden.test.tsx` asserts none of `PERMISSION_CATALOG`'s ids (the
  live catalog task 032 built, not a hand-typed guess at what "a permission id" might look like) appear
  anywhere in the rendered output.
- **Correlation id shown** — same test, direct text assertion.
- **a11y** — `DeclareInstanceDialog.a11y.test.tsx` and `Forbidden.a11y.test.tsx`, light/dark, axe clean.
- **Visually verified** — dev server + MSW, real Chromium: clicked "Declare instance" on
  `/solutions/cache/instances`, confirmed the dialog renders the stated notice with no fields and a working
  Close.

## Status

DONE — implemented, tested (635/635 on the full run), lint-clean, typecheck-clean, visually verified in a
real browser. Not yet closed on GitHub — pending review, particularly Ruling 1 (`Forbidden` has no live call
site yet) and Ruling 4 (tone/icon choice for the containing `Alert`).
