# FE-040 · Load-a-package dialog — Report

## What I implemented

The super-admin-only "Load a package" trigger and its verification-then-load dialog (task spec:
`docs/plans/frontend/implementation.md` §FE-040; refs `UI-D19`, `UI-D11`, design §9, UI §A.5 load
package dialog). FE-039 shipped the catalog without this trigger deliberately (its own Ruling 2) —
this task is exactly the "once the dialog exists" follow-up.

| File | Summary |
|---|---|
| `src/features/inventory/solutions/LoadPackageDialog.tsx` | New. Drop zone + hidden file input (keyboard-operable: `role="button"`, `tabIndex=0`, Enter/Space). Selecting a file fires one `repository.load()` call (`useMutation`) whose result is rendered inline as the "signature-verification result": signed → ok `Chip` + fingerprint; unsigned-and-admitted → warn `Chip` + explanation that it'll be marked conspicuously afterward; unsigned-and-not-admitted → err `Chip`, Load stays disabled; network failure → inline `Alert`. "Load package" is enabled only once `isAdmitted(result)` — `result.verified \|\| (result.devMode && import.meta.env.DEV)` — the same static, build-time-literal `import.meta.env.DEV` branch `AuthProvider.tsx`/`devMockAdapter.ts` already gate on, not the server's `devMode` flag alone. |
| `src/features/inventory/solutions/SolutionsPage.tsx` | Wires it up: `identity.superAdmin` (via `useAuth()`, same gate `features/home/FirstRun.tsx` established — **not** the RBAC `Can` gate, since `package.load` sits in the permission catalog as `status: 'absent'`, never grantable, which would omit the control for a super-admin too) controls the header `PageHeader` action and the empty-state action (button vs. "ask a super-admin" `Alert`, mirroring the reference's simultaneous header+empty-state rendering). On a successful load: closes the dialog, invalidates `queryKeys.solutions.list()`, and `pushToast`s an ok toast carrying the server's own correlation id. |
| `src/features/inventory/solutions/solutionsRepository.ts` | Added `load()` + `PackageLoadResult`. Sends file **metadata only** (`name`/`size`) — never bytes; the real upload wire format is `§16 Q10` TBD, and inventing multipart semantics here would assert a contract nobody ratified. |
| `src/features/inventory/solutions/icons.tsx` | Added `UploadIcon`/`LockIcon` — same glyphs as `features/home/icons.tsx`'s versions; a feature cannot import another feature's module (boundaries, task 009), so this is its own copy. |
| `src/shared/testdoubles/handlers/cp.ts` | Extended the already-seeded `POST /cp/solutions/load` handler (task 033 had stubbed a fixed signed-only response) to branch on the submitted filename — `dev`/`nightly`/`unsigned` → the unsigned/dev-mode result — so the admission path is reachable from a real drop/pick in the dev server, not only from a directly-injected fake repository. |
| `src/shared/i18n/messages.en.ts` | Added the `solutionsLoad*` keys (dialog title, drop-zone copy, three verification-result renderings, info banner, submit/submitting labels, success toast). |
| `src/shared/api/types.ts`, `http.ts`, `planeClientFactory.ts`, `index.ts` | See Ruling 1 — added the `requestWithCorrelation`/`postWithCorrelation` seam. |

Three new test files: `LoadPackageDialog.test.tsx` (7 tests — keyboard operability, signed
verification, dev-mode admission, network failure, choose-another reset, `onLoaded` payload,
cancel), `LoadPackageDialog.a11y.test.tsx` (4 — drop-zone and verified-result states, light/dark).
Extended `SolutionsPage.test.tsx` (+3: header-trigger-opens-dialog, non-super-admin omits the
trigger and shows the ask-admin alert, successful load closes+refreshes+toasts) and
`SolutionsPage.a11y.test.tsx`/`SolutionsPage.a11y.test.tsx`'s empty-state stub (repository shape
change only). Extended `http.test.ts` (+2) and `planeClients.test.ts` (+1) for the new
correlation-carrying seam.

## Rulings

1. **`ToastInput.correlationId` is required (`FE-D13`: "never client-fabricated"), but nothing in
   `shared/api` surfaced a correlation id on a *successful* response** — `request()`/`PlaneClient`
   discarded it, only ever attaching it to a thrown `ApiError`. No earlier task needed one, because
   this is the first real `pushToast` call site in the app (`grep`-verified: every other reference
   was the barrel export or `Toaster`'s own test/gallery file). Rather than fabricate an id
   client-side — which the header comment on `ApiError.correlationId` explicitly forbids ("the
   SERVER's id — always authoritative, never the client-sent one") — I added
   `requestWithCorrelation<T>()` to `http.ts` (refactoring `request()` into a thin wrapper over it,
   so every existing call site is unaffected) and a parallel `PlaneClient.postWithCorrelation()`.
   This touches three files outside this task's own `Files` list, same posture FE-039's Ruling 1
   took for its MSW-bootstrap gap: fix the real blocker properly rather than route around it, and
   flag it here. Covered by two new `http.test.ts` cases and one `planeClients.test.ts` case.
2. **The dialog's two file-metadata fields (`fileName`/`fileSize`), not the file's bytes, are what
   `solutionsRepository.ts`'s `load()` sends.** The real upload wire format (multipart? chunked?)
   is `§16 Q10` TBD; sending real bytes through a JSON-only request path (`PlaneClient.post` always
   `JSON.stringify`s the body) would mean inventing a contract, not honoring the flagged-TBD one.
   The already-seeded MSW handler (task 033) only ever read a JSON body anyway, which is itself a
   signal this was the intended shape for now.
3. **Admission is gated on `import.meta.env.DEV`, not the server's `devMode` field alone** — a
   server that merely *reports* a package as unsigned is not the same statement as *this console
   instance* running in development mode (`UI-D19`'s literal wording: "admitted only in an explicit
   development mode"). The "rejected outside dev mode" branch cannot be exercised by a Vitest run
   (Vitest always sets `import.meta.env.DEV = true` — `devMockAdapter.ts`'s own header note) — it's
   a one-line boolean short-circuit, correct by inspection, the same posture `main.tsx`'s dev-only
   MSW bootstrap already takes for its own untestable-in-Vitest branch (FE-039 Ruling 1).
4. **Both the header action and the empty-state action render simultaneously for a super-admin
   when the catalog is empty** — ported directly from the reference (`solutions.jsx`'s `right` is
   computed unconditionally and rendered alongside the `demo === "empty"` branch's own action), not
   invented. Verified in `SolutionsPage.test.tsx` by scoping queries to `[data-page-header-actions]`
   rather than assuming a single match.
5. **Verified live in the dev server that a "Load package" click closes the dialog and triggers a
   `GET /cp/solutions` refetch** (screenshotted all three dialog states: empty drop zone, signed
   verified, unsigned dev-mode-admitted — all visually match the reference's `LoadModal`) **but the
   success toast itself is not visible on screen** — `<Toaster />` is mounted in `AppShell.tsx`
   (task 036), and `AppShell` is not yet wired into `router.tsx` (`router.tsx`'s own comment: "task
   036 supersedes this" — a pre-existing gap, not something this task introduces or should route
   around). This is why the toast assertion in `SolutionsPage.test.tsx` reads
   `useToastStore.getState().toasts` directly rather than a DOM query — the correct level to prove
   `pushToast` fired correctly given the shell isn't mounted yet.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run typecheck   # clean
npm run lint         # clean (0 warnings, --max-warnings=0)
npx vitest run       # 130 files, 549 tests, all passing (up from 128/532 before this task)
npm run build         # production build succeeds; SolutionsRoute chunk 15.98 kB / 4.89 kB gzip
npx playwright test   # 5/5 e2e tests passing, no regression
```

Task-specific acceptance items, each backed by a concrete check:

- **Control omitted without the verb** — `SolutionsPage.test.tsx`'s
  `renderAsIdentity(..., false)` test (a local `AuthProvider adapterOverride`, the same seam
  `LoginForm.test.tsx` established, since `renderWithProviders`'s fixed `devMockAdapter` always
  reports `superAdmin: true`) asserts `queryByRole('button', {name: 'Load a package'})` is `null`
  and the "Ask a Solace super-admin to load one." alert renders instead.
- **Signature result shown before load** — `LoadPackageDialog.test.tsx`'s signed/unsigned tests
  assert the verification chip renders (and Load is enabled/disabled accordingly) before any
  `onLoaded` call.
- **Dev-mode admission is explicit** — the dev-mode-admitted test asserts "Unsigned · admitted in
  development mode" renders and is textually distinct from "Signature verified · Solace-signed".
- **Non-super-admin sees no control; unsigned path renders the dev-mode treatment; keyboard-
  operable drop zone** — all three directly tested (see above; keyboard test spies on
  `HTMLInputElement.prototype.click` and confirms Enter on the focused drop zone triggers it).
- **axe** — `LoadPackageDialog.a11y.test.tsx`, light/dark × drop-zone/verified-result states.
- **Visually verified against the reference** — screenshotted the running dev server (`/solutions`,
  1280×900) through all three dialog states (empty, signed-verified, unsigned-dev-admitted); layout,
  copy and chip treatment match `docs/design/frontend/reference/app/solutions.jsx`'s `LoadModal`.
  Confirmed via a debug script that clicking "Load package" closes the dialog and triggers a fresh
  `GET /cp/solutions` (the query-invalidation wiring) with zero console errors.
- **Production build stays tree-shaken** — re-ran FE-039 Ruling 1's own check
  (`grep -rl "mockServiceWorker|setupWorker|msw/browser|devMockAdapter" dist/assets/*.js`) after
  this task's changes: still no matches.

## Status

DONE — implemented, tested, lint-clean, typecheck-clean, full unit suite green (549 tests across
130 files), e2e green (5 tests, no regression), production build succeeds and confirmed
tree-shaken, visually verified against the reference by screenshot. Not yet marked `✅` in
`task-list.md`/`execution.md` or closed on GitHub — pending review, particularly Ruling 1 (the
`shared/api` correlation-id seam, which touches files outside this task's own `Files` list) and
Ruling 5 (the pre-existing AppShell-not-wired gap this task's toast now depends on but did not
create).
