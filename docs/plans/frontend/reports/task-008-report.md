# FE-008 · Test harness — Report

## What I implemented

The five test kinds Phase 1+ tasks all depend on, runnable locally and in CI from day one, so no
later task can defer its own tests: unit, component, a11y, route (via the existing memory-router
pattern), e2e, and a `contracts` project ready for the shared JSON fixture suite tasks 046/071
will create.

### Config

- `frontend/vite.config.ts` — the `test` block moved out (see below); otherwise unchanged.
- `frontend/vitest.config.ts` (**new**) — `mergeConfig(viteConfig, defineConfig({ test: { projects: [...] } }))`.
  A dedicated `vitest.config.ts` makes Vitest ignore `vite.config.ts`'s own config entirely unless
  merged back in explicitly — verified via the Vitest docs before writing this, not assumed.
  Two projects (Vitest 4's `test.projects`, the replacement for the removed `vitest.workspace.ts`):
  `unit` (jsdom, `src/test/setup.ts`, `src/**/*.test.{ts,tsx}`) and `contracts` (node,
  `contracts/fixtures/**/*.test.ts`). `contracts` matches zero files today — verified empirically
  that `npm test` still exits 0 (Vitest only fails the whole run if *every* project is empty); no
  `passWithNoTests` needed, and it isn't a valid per-project option in Vitest 4 anyway (root-only —
  hit as a real type error, not assumed).
- `frontend/playwright.config.ts` (**new**) — against the **preview** server (design's real
  deployment shape), not the dev server. `webServer.command` runs `npm run build && npm run
  preview -- --port 4173 --strictPort` so `npm run e2e` is self-contained regardless of job
  ordering; `reuseExistingServer: !CI` for fast local reruns.
- `frontend/tsconfig.node.json` — added `vitest.config.ts`, `playwright.config.ts`, `e2e` to
  `include` (Node-context, like `vite.config.ts` already was), and `allowImportingTsExtensions`
  (needed once `vitest.config.ts` imports `./vite.config.ts` with an explicit extension — Vite's
  own `configLoader: 'native'` deprecation warning asked for that extension).
- `frontend/eslint.config.js` — extended the existing "Node-context config files, type-aware
  rules off" file list to also cover `vitest.config.ts`, `playwright.config.ts`, `e2e/**/*.ts` (the
  same reason `vite.config.ts` was already there: not part of the `src`-only type-checked ESLint
  project service). Real type-checking of these files still happens via `tsc -p tsconfig.node.json`.
- `.github/workflows/frontend.yml` — added an `e2e` job (checkout → setup-node → `npm ci` →
  `npx playwright install --with-deps chromium` → `npm run e2e`), separate from the existing
  lint/typecheck/test/build matrix since it alone needs a browser-install step. Not in the task's
  literal Files list, same situation as FE-004/FE-005/FE-010's non-listed files: `execution.md`
  §6.1's Gate A command list includes `npm run e2e`, and that's not actually proven in CI without
  this.
- `.gitignore` — added `frontend/test-results/`, `frontend/playwright-report/`,
  `frontend/blob-report/` (Playwright's local output) and `coverage/` (pre-existing gap, same
  section).

### Harness files

- `frontend/src/shared/testdoubles/server.ts` — `setupServer()` from `msw/node`, no handlers.
  Task 033's fixture layer registers repository-interface handlers; feature tests add per-test
  ones via `server.use(...)`.
- `frontend/src/test/setup.ts` — the `unit` project's `setupFiles` entry: `@testing-library/
  jest-dom/vitest` matchers, `jest-axe`'s `toHaveNoViolations` via `expect.extend`, RTL `cleanup()`
  in `afterEach` (see the real bug below), and the MSW server lifecycle — `listen({
  onUnhandledRequest: "error" })` in `beforeAll`, `resetHandlers` in `afterEach`, `close` in
  `afterAll`.
- `frontend/src/test/vitest-matchers.d.ts` + `frontend/src/test/jest-axe.d.ts` — jest-axe ships no
  types, and `@types/jest-axe` augments Jest's `jest.Matchers` (which Vitest's `Assertion` doesn't
  extend) and drags in `@types/jest`'s ambient globals as a dependency — so these type the two
  things actually imported from `jest-axe` directly, and separately augment Vitest's own
  `Matchers` interface (the pattern Vitest's own docs recommend for `expect.extend()`). Split into
  two files, not one — see the real bug below.
- `frontend/src/test/renderWithProviders.tsx` — mounts theme → CssBaseline → query → auth →
  `MemoryRouter`, the same nesting order `app/providers.tsx` already documents from design §8.1.
  Accepts `mode`, `route`, `queryClient` overrides; builds a fresh no-retry `QueryClient` per call
  by default. `MemoryRouter` stands in for the app's real `RouterProvider` deliberately — route-
  table behaviour (guards, deep links) is `router.test.tsx`'s job against the real routes (design
  §19's separate "Routes" layer), not this component-layer helper's.
  **Auth and permissions**: the task's own Implementation line asks for "per-test overrides for
  permissions and colour mode." Colour mode is real today (`createSmTheme`) and is wired. Auth and
  the permission model don't exist yet (tasks 030, 032) — rather than invent either now, the auth
  slot is a no-op placeholder mirroring `app/providers.tsx`'s own (comment points at task 030),
  and no permission override exists at all yet — adding one now would be exactly the kind of
  invented shape the plan's `FIXTURE`-task discipline warns against, on a task that isn't even
  marked `FIXTURE`.
- `frontend/e2e/smoke.spec.ts` — navigates to `/`, asserts the page title, the real `HomeRoute`
  stub's "Home" heading, and zero browser console errors. No MSW browser worker: nothing makes a
  plane API call yet (`fetch('/config.json')` in `RuntimeConfigBoot`, task 005, hits a real static
  file both in dev and in `preview`), so there's nothing to mock. The Implementation bullet's "MSW
  browser worker" is the target shape once components make real calls — that lands with task 033's
  fixture layer, not this one; task 008's own Files list doesn't include a worker/service-worker
  file either.

### Two real bugs this surfaced

1. **RTL wasn't cleaning up between tests.** `renderWithProviders`'s own trivial "dark mode" test
   failed with "Found multiple elements with role heading" and the axe test failed with
   `landmark-no-duplicate-main` — both from the *previous* test's DOM never being unmounted. RTL's
   auto-cleanup only registers if the test framework injects a *global* `afterEach`
   (`@testing-library/react`'s own docs, fetched and confirmed before fixing, not assumed); this
   repo's `globals: false` means it never did. Fixed by calling `cleanup()` explicitly in
   `setup.ts`'s `afterEach`, alongside `server.resetHandlers()`. Both failing tests pass now with
   no other change.
2. **The jest-axe module augmentation silently failed to compile until split into two files.** One
   `.d.ts` combining `import 'vitest'; declare module 'vitest' {...}` (augmentation of an existing
   typed module) with `declare module 'jest-axe' {...}` (declaring a whole new ambient module,
   since jest-axe ships none) still produced `TS7016: Could not find a declaration file for module
   'jest-axe'` — because a file with a top-level `import` is module-mode, and `declare module 'x'`
   inside a module-mode file is *augmentation* syntax, not a fresh ambient declaration; there was
   nothing to augment. Splitting into `vitest-matchers.d.ts` (module-mode, augments `vitest`) and
   `jest-axe.d.ts` (script-mode — no top-level import/export — so `declare module 'jest-axe'`
   declares a genuinely new module) fixed it. Confirmed empirically (`npm run typecheck` clean)
   after the split, not just reasoned about.

## Testing

The harness testing itself, per the task's own Testing line:

- **Trivial component test** — `renderWithProviders.test.tsx`: mounts a component through the
  full provider stack in both colour modes.
- **Trivial a11y test** — same file: `axe(container)` / `toHaveNoViolations()`.
- **Trivial e2e** — `e2e/smoke.spec.ts`, described above.
- **"An unmocked fetch fails a test"** — `shared/testdoubles/server.test.ts`: `await
  expect(fetch('https://unhandled.example.test/nope')).rejects.toThrow()`, exercising the
  `onUnhandledRequest: "error"` wiring from `setup.ts` (no per-test setup needed — it's global).

```
$ npm run lint && npm run typecheck && npm test -- --run && npm run build && npm run e2e
```
All five green — the exact Gate A sequence (`execution.md` §6.1), run end to end, not sub-command
by sub-command. 120 unit/component/a11y tests passing across 20 files (7 new in this task); 1 e2e
test passing against a real production preview build; production `npm run build` unchanged at 978
modules (same as FE-010's baseline — confirms none of the new devDependencies, which are all
test-only, leaked into the shipped bundle). Also ran the e2e suite a second time with `CI=true`
(no `reuseExistingServer`, `forbidOnly`, 2 retries) to prove the CI code path, not just the local
one. `npm run format` flags only the four pre-existing `shared/theme/` files this task didn't
touch — every new/modified file here is Prettier-clean.

## Status

DONE

## Commit

`0cd08ff..d288cab` on `dev/frontend`. Register:
[`execution.md` §7](../execution.md#7-task-register--all-125), row `008`. Issue
[#28](https://github.com/parikshit-coe/solution-manager/issues/28).
