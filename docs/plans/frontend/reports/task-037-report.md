# FE-037 · Login screen — Report

## What I implemented

Local sign-in with four failure/arrival states distinguished, and room for one SSO button later (task spec:
`docs/plans/frontend/implementation.md` §FE-037; refs `D13` 🔒 deferred, `FE-D24`, design §14/§15, UI §A.3).

| File | Summary |
|---|---|
| `src/features/auth/LoginPage.tsx` | Chromeless page: decorative brand mesh SVG background (token-coloured, `aria-hidden`), a floating white card, heading, `LoginArrivalAlert`, `LoginForm`, footer copy stating the *real* configured idle-timeout minutes. Visual spec ported verbatim from the checked-in reference (see Ruling 8) — navy page background, exact 404px/38·36·30-padding/14px-radius/`e8`-shadow card, exact 18-node mesh generator — not the generic `shared/ui` `Paper` surface I'd used in the first pass. |
| `src/features/auth/LoginArrivalAlert.tsx` | Renders the `session` (warn) or `idle` (info) alert from `?arrival=`, or nothing. The idle copy's "your edits were saved" promise is `FE-D25` — built specifically so this sentence is true. |
| `src/features/auth/LoginForm.tsx` | The state machine: `idle · submitting · rejected · unreachable`. Username/password fields (`autoComplete`, reveal toggle), submit button, in-flight guard. `rejected` renders one message under password and marks username invalid too, with no message — both fields read as errored together so styling alone can't leak which one was wrong. |
| `src/features/auth/index.ts` | Public barrel — `LoginPage` only (the boundary an app-layer call site imports through). |
| `src/app/routes/LoginRoute.tsx` | Swapped the `RouteStub` placeholder for the real `LoginPage`, supplying `idleTimeoutMinutes` from `useRuntimeConfig()` — a feature file cannot import `@app/*` (boundaries), so the app-layer route is the required call site (same pattern `RequireAuth.tsx`, task 031, already documents for itself). |
| `e2e/login.spec.ts` | Four Playwright tests against the production preview build: form renders with no console errors, session/idle arrivals render their alerts, and a real submit against the (currently backend-less) `/auth/login` ends in a visible error state rather than hanging. |

Two small, directly-necessary extensions:

| File | Change |
|---|---|
| `shared/ui/TextField/{TextField.tsx,TextField.types.ts}` | Added `autoComplete?: string`, forwarded to MUI's native prop. Without it, a login form has no `autoComplete="username"`/`"current-password"` — a real WCAG 2.2 SC 1.3.5 (Identify Input Purpose) gap, and design §18's own bar names AA for forms. |
| `src/app/router.test.tsx` | `LoginRoute` now depends on `RuntimeConfigBoot` (config) and `AuthProvider` (identity) — neither was wired into this file's bare `<ThemeProvider><RouterProvider>` test tree, so every deep-link render in it would have thrown. Added a `fetch` stub for `/config.json` and wrapped the tree in `RuntimeConfigBoot` + `AuthProvider` (same `adapterOverride`-free construction `renderWithProviders.tsx` uses — DEV mock, never touches the network). Updated the `/login` content assertion from the placeholder's `'Login'` to the real page's `'Sign in'` heading, and gave that one test a `10_000ms` override (same reasoning as the file's existing gallery-test override) since it now resolves two extra async dependencies before the lazy chunk's content appears. |
| `src/shared/theme/createSmTheme.ts` | Fixed a pre-existing, unrelated bug found while visually comparing the login screen against the reference (see Ruling 9): `index.html`'s static skip link (task 001) had no CSS at all, so it rendered as a permanently-visible link top-left on every route. Added `.skip-link`/`.skip-link:focus` to `MuiCssBaseline`'s global `styleOverrides`, off-screen until focused — same treatment `AppShell`'s own in-app skip link (task 036) already uses. |

## Rulings

1. **The idle-timeout minutes are a prop, not a hook call, in `LoginPage`.** `useRuntimeConfig()` lives in
   `src/app/boot/RuntimeConfig.tsx` (app zone); boundaries grant `feature -> shared/contracts` only, no policy
   for `feature -> app`. `RequireAuth.tsx`'s own header already documents this exact constraint for
   `idleTimeoutMinutes` ("the app-layer call site supplies it") — `LoginPage` follows the identical shape:
   `LoginRoute.tsx` reads config and passes `idleTimeoutMinutes` down.
2. **The post-login redirect target (`'/'`) is a hardcoded literal, not `routes.home()`.** `routes.ts` is also
   app-zone (`src/app/routes.ts`); a feature can't import it. `SidebarNav.tsx` and `Breadcrumbs.tsx` (task 024)
   already hardcode the same literal paths for the identical reason — I matched that precedent rather than
   inventing a new one.
3. **`rejected` collapses every `ApiError` into one message**, regardless of `status`/`code` — never distinguishing
   a 404 "no such user" from a 401 "wrong password." This is the account-enumeration requirement read literally:
   the UI has no *mechanism* to express the distinction, not just a copy choice. Tested directly (`LoginForm.test.tsx`:
   a 404/`USER_NOT_FOUND` and a 401/`BAD_PASSWORD` scenario render identical text and identical `aria-invalid`
   state on both fields).
4. **`unreachable` is "anything that isn't an `ApiError`"** — a genuine network failure (`http.ts`, task 026,
   rethrows the raw fetch error rather than wrapping it), not a specific status code. The login endpoint's
   contract is `CONTRACT: TBD` (`localSessionAdapter.ts`'s own header) project-wide, so there's no server-specified
   status to branch on beyond "the request came back" vs "it didn't."
5. **No client-side required-field blocking beyond MUI's native `required` attribute** (no `noValidate`). The task
   doesn't specify a fifth "validation" state, and native browser required-field blocking is standard,
   zero-code, accessible behaviour for exactly this case.
6. **The brand mesh background is a small deterministic SVG, not a fabricated logo image.** No logo asset exists
   anywhere in this repo (checked `docs/design/frontend/reference/` — only the reference `.jsx` files cite
   `../assets/solace-logo-full.svg`; the asset itself was never checked in). Rendering a real Solace brand asset I
   don't have would be inventing one; the heading uses text ("Solution Manager" / "Solace Console", the same
   strings `AppShell`'s drawer already uses) instead. Flagging for the user in case a real logo asset exists
   outside this repo and should be added.
7. **The e2e "login journey" test does not assert a successful sign-in.** No backend exists anywhere in this
   monorepo yet (Console REST is unwritten; the same honesty `e2e/smoke.spec.ts`'s own header states — "no
   component makes a plane API call yet"). The e2e suite instead proves the real, achievable mechanism: the page
   renders against the production preview build, arrivals render from the query string, and a genuine submit
   against the absent endpoint reaches a visible terminal state (rejected or unreachable, deliberately not
   asserted as one specific outcome — that's an artifact of how the static preview server answers an unmatched
   POST route, not a business rule this test should pin down) rather than hanging silently.
8. **The reference (`docs/design/frontend/reference/`) is the visual source of truth for pixel-level values,
   not an approximation to eyeball.** The user flagged the first pass's background colour, padding and margins
   as visibly off; the root cause was reaching for the generic `shared/ui` `Paper` component (border, 24px
   uniform padding, theme-default background) instead of reading `.login-wrap`/`.login-card`/`.login-mesh` in
   `reference/theme.css` and `reference/app/login.jsx`'s mesh generator directly. Corrected to use those exact
   values via existing `tokens.console.*`/`tokens.semantic.bg1` (no new tokens invented) — see the `LoginPage.tsx`
   row above. Lesson for later Phase 3/4 screens: check the reference CSS file for the screen's class names
   before laying out a page, not just the JSX for structure.
9. **Fixed the always-visible `index.html` skip link (task 001) while verifying the login screen visually** —
   unrelated to FE-037's own scope, but directly in view while confirming the reference match, and a real,
   pre-existing a11y/visual defect (see `createSmTheme.ts` row above). Confirmed via Playwright: hidden by
   default, appears with a focus ring when directly focused.

## Testing

Every command run from `frontend/`, output inspected:

```
npm run lint          # clean
npm run typecheck     # clean
npm run test           # 119 files, 496 tests, all passing
npm run build          # production build succeeds, 1556 modules; LoginRoute chunk 140KB / 43KB gzip
npx playwright test    # 5/5 e2e tests passing (4 new + the existing smoke test)
```

Task-specific acceptance items, each backed by a concrete test:

- **All six states as component tests** — `LoginForm.test.tsx` covers idle, submitting (button disabled +
  label swap), rejected (inline error, both fields invalid), unreachable (banner, not confused with rejected),
  plus the in-flight duplicate-submit guard and a successful-login navigation test; `LoginPage.test.tsx` /
  `LoginArrivalAlert.test.tsx` cover the two arrivals (session, idle) and the no-arrival case, including the
  idle copy's plural/singular minute form.
- **Axe** — `LoginPage.a11y.test.tsx`, idle and session-arrival states, light and dark mode.
- **e2e login journey** — `e2e/login.spec.ts` (see Ruling 7 for what "journey" honestly means here today).
- **No enumeration** — `LoginForm.test.tsx`'s dedicated test (Ruling 3), comparing a not-found-user scenario
  against a wrong-password scenario byte-for-byte on rendered text and both fields' `aria-invalid`.
- **No unimplemented SSO control** — the slot is a comment, not a disabled/hidden button; nothing renders there.
- **Idle copy matches the mechanism that actually ships (task 031)** — the copy and the underlying
  `useDraftAutosave`/`useDraftRestore` hooks both exist (`FE-D25`); no Designer screen consumes them yet
  (pre-existing, from the FE-026–035 report's own Ruling 13), which is a gap in Designer, not in this promise's
  truthfulness at the login-copy level.

## Status

DONE — implemented, tested, lint-clean, typecheck-clean, full unit suite green (496 tests), e2e green (5
tests), production build succeeds, and the page's visual output re-verified against the reference by
screenshot after the Ruling 8/9 fixes (idle state, session-arrival state, unfocused/focused skip link). Not
yet marked `✅` in `task-list.md`/`execution.md` or closed on GitHub (#49) — pending review, particularly
Ruling 6 (no logo asset — confirm whether a real one exists outside this repo) and Ruling 7 (what "e2e login
journey" can honestly mean before a backend exists).
