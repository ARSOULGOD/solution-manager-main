# FE-043 · Bulk restart confirmation — Report

## What I implemented

The bulk-restart confirmation dialog (task spec: `docs/plans/frontend/implementation.md` §FE-043; refs
`SM-D15`, `SM-D22`, `FE-D18` clause B (blocked), Design §10.1, UI §A.6). Per the spec's own instruction,
**only the dialog and copy ship** — the restart call itself is not wired, since multi-instance apply has
no protocol yet (open item 1) and single-instance apply defaults to `APPLY_INDETERMINATE` (C11).

| File | Summary |
|---|---|
| `src/features/inventory/instances/BulkRestartDialog.tsx` | New component. Built on `ConfirmDialog` (task 016, `consequence="outage"`) rather than a bespoke dialog, so the high-consequence mechanism (`FE-D9`) is reused, not reinvented. Body: the "writing config ≠ applying it" rule, a bulleted per-instance list (name · replica count via the existing `replicaCount` plural key · a fixed unavailability estimate), and the closing "restart is operator-initiated" caption — both sentences quoted verbatim from the task spec. `onConfirm` only closes the dialog: no mutation, no toast, no outcome badge. |
| `src/features/inventory/instances/InstancesPage.tsx` | Wired the trigger: a `Restart {count}…` button appears in the `DataTable` toolbar (next to the Env filter) only when `selection.length > 0`, opening `BulkRestartDialog` with the selected rows resolved from the **full** instance list (not the env-filtered `rows`), matching the reference (`app/instances.jsx`'s `allRows.filter(...)`) and FE-042's "env filter is display-only" rule. |
| `src/shared/i18n/messages.en.ts` | Six new keys: `instancesBulkRestartTrigger`/`Title`/`Confirm`/`Rule`/`Unavailability`/`ClosingNote`. |
| `InstancesPage.test.tsx` | Two new tests: the trigger is absent with no selection and shows the right count once rows are selected; the dialog states name/replica-count/unavailability per selected instance (via `[data-bulk-restart-list] li` textContent, not `getByText`, since the strings live in the same catalogue key but the same substring appears in multiple nested ancestors — see Rulings), and confirming closes the dialog while nothing matching `/success/i` renders anywhere in the document. |
| `BulkRestartDialog.a11y.test.tsx` | New file — axe clean, light and dark. |

## Rulings

1. **Reused `ConfirmDialog`'s `consequence="outage"` banner instead of only the spec-quoted body text.**
   This is the first real (non-gallery) caller of the high-consequence mechanism task 016 built; using it
   here — rather than a plain `Dialog` with a hand-rolled warning `Alert` — keeps FE-043 consistent with
   `FE-D9`'s stated mechanism and gives future high-consequence actions (apply, rollback) the same shape.
   The spec's own quoted sentences (`"writing configuration is not the same as applying it"`, the closing
   operator-initiated note) are additive body content, not a replacement for the banner.
2. **Selected instances resolve against the unfiltered instance list, not the Env-filtered `rows`.**
   The reference screen (`docs/design/frontend/reference/app/instances.jsx`) computes `selRows` from
   `allRows`, and FE-042 already established that the Env toggle is display-only, never mutating `?sel=`.
   Filtering the dialog's instance list by the currently-visible Env would silently drop a previously
   selected row the moment the toggle changes — same class of bug FE-042's report (Ruling 1) fixed for
   the grid's own selection state, applied here to keep the dialog consistent with it.
3. **`getByText` cannot reliably assert the per-instance bullet text and was replaced with a direct
   `textContent` check.** The bullet renders as `<li>{name}{' · '}{count}{' · '}{unavailability}</li>` —
   testing-library's default text matching walks every ancestor element, and each one's full `textContent`
   also contains the target substring (the `<ul>`, `DialogContent`, the dialog root), so `exact:false`
   substring queries threw "multiple elements found." Fixed by asserting
   `[...dialog.querySelectorAll('[data-bulk-restart-list] li')].map(li => li.textContent)` contains the
   exact expected string per instance — deterministic, no ambiguity.
4. **The dialog-close assertion needed `waitFor`.** MUI's `Dialog` unmounts after its exit transition, so
   `screen.queryByRole('dialog')` immediately after the confirm click still found the (closing) dialog;
   wrapped in `waitFor`.
5. **No repository/mutation code added.** `onConfirm` is a plain `() => void` prop the page wires to
   `setBulkRestartOpen(false)` — there is nothing to call yet (open item 1), so adding a
   `useMutation`/repository method now would be dead code with no server contract behind it.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc --noEmit    # clean
npm run lint         # clean (0 warnings, --max-warnings=0)
npx vitest run       # 136 files, 611 tests, all passing (up from 135/607 before this task)
npm run build         # production build succeeds, no new warnings beyond the pre-existing
                       # layout-chunk size note (FE-041/FE-042's own reports)
```

Task-specific acceptance items:

- **Never reports success** — `InstancesPage.test.tsx`: after clicking the dialog's confirm button, the
  dialog unmounts and `screen.queryByText(/success/i)` is `null`. No mutation, toast, or outcome-badge
  call exists in `BulkRestartDialog.tsx` to produce one in the first place.
- **Per-instance consequence stated** — dialog body asserted to contain, per selected instance, its name,
  `replicaCount`-pluralized replica count, and the unavailability estimate; plus the "writing config ≠
  applying it" rule and the operator-initiated closing note, both verbatim from the spec.
- **Trigger gated on selection** — a dedicated test asserts the `Restart N…` button is absent with an
  empty selection and appears with the correct count once a row is checked.
- **Visually verified against the reference** — ran the dev server (`npm run dev` + MSW) under a
  throwaway Playwright script (same approach FE-042's report used), navigated to
  `/solutions/cache/instances`, selected `cache-prod-01` (3 replicas) and `cache-prod-02` (2 replicas),
  opened the dialog: title, outage banner, rule text, both bulleted lines (`cache-prod-01 · 3 replicas ·
  expect ~10–20s unavailability per replica`, `cache-prod-02 · 2 replicas · …`), and the closing note all
  render correctly and match `docs/design/frontend/reference/app/instances.jsx`'s `Modal` structure.
  Clicked confirm: dialog closed, zero console errors, no toast/success text anywhere in the DOM.
  Single-instance selection (singular grid state, "Restart 1…"/"Restart 1") also screenshotted and
  correct.

## Status

DONE — implemented, tested (611/611 unit tests, up from 607), lint-clean, typecheck-clean, production
build succeeds, visually verified in a real browser against the reference. Not yet closed on GitHub —
pending review. The task remains `BLOCKED` for **execution** per its own `Ready` line (open item 1); only
the dialog/copy — the part marked `FIXTURE` — is in scope here, and that part is complete.
