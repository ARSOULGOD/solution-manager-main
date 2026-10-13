# FE-026–035 · API seam, auth, permissions — Report

## What I implemented

Completed Phase 2 tasks **026–035** on `dev/frontend`, executed in the order the dispatch specified: 026 → 027 → 028 → 029 → 030 → 031 → 032 → 033 → 034 → 035.

| Task | Files created | Summary |
|---|---|---|
| 026 · HTTP core | `shared/api/http.ts`, `correlation.ts`, `types.ts` (+ `http.test.ts`) | `request<T>()`: JSON in/out, unknown fields never stripped, `credentials: 'same-origin'`, `X-Correlation-Id` sent + server id authoritative, `RequestCoordinator` for abort-before-next-issued, `ApiError`/`ErrorCode`/`shouldRetry` (see Ruling 1), no request/response body ever logged |
| 027 · Plane clients | `planeClientFactory.ts`, `cpClient.ts`, `dpClient.ts`, `consoleClient.ts`, `index.ts` (+ tests); `eslint.config.js` extended; `src/test/lint-fixtures/app/vite-plane-host.ts` | `cp`/`dp`/`console` clients over one origin; absolute-URL guard shared via `assertRelativePath`; new `no-restricted-syntax` rule forbids `VITE_*_URL`, proven by a lint fixture |
| 028 · Error codec, retry | `errorCodes.ts`, `ApiError.ts`, `retry.ts` (+ tests) | `SPECIFIED`/`TBD` registry with typed copy per code; `retryDelay()` bounded backoff; `MUTATION_RETRY = false` |
| 029 · QueryClient defaults | `queryClient.ts`, `queryKeys.ts` (+ tests); `providers.tsx` swapped placeholder for real client | `refetchOnWindowFocus:false`, shared retry predicate, `keepPreviousData`, `structuralSharing:true`, mutations never retried; one key factory seeded for all 12 repository seams |
| 030 · Auth adapter | `shared/auth/AuthAdapter.ts`, `localSessionAdapter.ts`, `devMockAdapter.ts`, `AuthProvider.tsx` (+ tests); `http.ts` extended with `onUnauthorized`; `providers.tsx` and `renderWithProviders.tsx` swapped in the real provider | Cookie session, no token in `localStorage`, tenant display-only, DEV-only mock behind a static branch (confirmed absent from `dist/` — see Testing) |
| 031 · Route guards, idle lock | `RequireAuth.tsx`, `useIdleTimer.ts` (+ tests) | Unauthenticated/401/idle all redirect to `/login` with a distinct `arrival`; draft-preservation mechanism (`FE-D25`) — `sessionStorage`, secret-stripped, discard action |
| 032 · Permission model | `authz/permissions.ts`, `usePermissions.ts`, `Can.tsx`, `catalog.ts`, `permissions.typetest.ts` (+ tests) | Catalog ported from the reference's `admin_data.jsx`; `Can` omit-vs-disable, never confused; `requireGrantable()` rejects a non-grantable id at compile time |
| 033 · Fixture layer | `shared/testdoubles/data/{instances,solutions,designer,metrics,rbac,targets}.ts`, `handlers/{cp,dp,console,index}.ts`, `README.md`; `server.ts` now registers default handlers; 11 `…Repository.ts` files under `src/features/*` (+ colocated tests) | Every repository seam except Auth (already `AuthAdapter`, task 030) — see Ruling 6 |
| 034 · Contract seam, codegen | `src/generated/README.md`, `scripts/generate-types.mjs`, `shared/api/contracts/common.ts` | Codegen script fails loudly (verified: exit 1, informative message); `Paged<T>` de-duplicated out of two repository files into the seam |
| 035 · Streaming download | `shared/api/download.ts` (+ test) | Hidden-iframe navigation, never `fetch`, never a `Blob` — distinct from `DataTable`'s client-side CSV |

## Rulings

Judgment calls made where the plan's text and the per-task file lists pointed in slightly different directions, or where a detail was left to be filled in:

1. **`ApiError`/`ErrorCode`/`shouldRetry` live in `shared/api/types.ts` (026), not `ApiError.ts` (028).** §6.0.1 names `types.ts` explicitly for these; task 028's file list separately asks for `ApiError.ts`. Putting the class in 028's file would force `http.ts` (026) to import forward from a later task to construct the error it throws — backwards. `ApiError.ts`/`retry.ts` (028) now re-export from `types.ts` under the stable `@shared/api/ApiError`/`@shared/api/retry` paths; `errorCodes.ts` adds the genuinely-new SPECIFIED/TBD registry and typed copy.
2. **`RequestCoordinator`** (http.ts) implements "a superseded request is aborted before the next is issued" as a keyed abort-controller registry — the plan names the *rule* but not a mechanism; this is the natural place for it since it's purely about `AbortSignal` plumbing.
3. **`AuthRepository` (§6.0.5) is satisfied by `AuthAdapter` (task 030), not a separate `authRepository.ts`.** Task 030's own file list and method names (`getIdentity`, `login`, `logout`, `onSessionExpired`, `getAccessToken?`) are more specific than §6.0.5's sketch (`identity`, `login`, `logout`) and were built first — I followed 030's spec verbatim and treated §6.0.5's `AuthRepository` as already covered. `shared/testdoubles/handlers/cp.ts`'s auth handlers back `localSessionAdapter` directly; `localSessionAdapter.test.ts` exercises them.
4. **`PlaneClient.resolve(path)` added** (not in the original §6.0.5/027 signature) — needed by task 035's streaming download and task 034's `exportRangeUrl()` to build a same-origin URL *without* issuing a request. Runs the identical absolute-URL guard as every other method, so the invariant has one implementation, not a third copy.
5. **DEV-mock tree-shaking uses a static `if (import.meta.env.DEV)` branch**, not a dynamic `import()`. Vite inlines the flag as a literal at build time and the module has no side effects, so dead-code elimination removes both branch and import. Empirically verified: `grep -r "Devon Ramirez" dist/assets/*.js` after `npm run build` returns no match. Task 101 owns the formal automated assertion; this is defence-in-depth plus a manual spot-check now.
6. **FE-033's repository architecture collapses "fixture vs HTTP" into one implementation per seam.** §9.1's diagram shows `fixtureXxxRepository` and `httpXxxRepository` as two boxes; but every per-task Files list (checked FE-041, FE-039, etc.) creates exactly one `…Repository.ts`. I read this as: one implementation that always calls the real plane client; "fixture vs real" is purely whether MSW is intercepting, never a second code path a component could pick the wrong one of. Documented in `shared/testdoubles/README.md`.
7. **Repository files import their payload types from `shared/testdoubles/data/*` via `import type` (erased at build, zero runtime coupling)** rather than duplicating type definitions, since `contracts/view-model` and `contracts/metrics` (tasks 046/070) don't exist yet. Each such import is `import type` only. Flagged in `shared/testdoubles/README.md` rule 3 for replacement once the real contracts land (`FE-D7`: replaced wholesale, never extended).
8. **`shared/testdoubles/handlers/*.ts` define small LOCAL request-shape interfaces (e.g. `IncomingQueryRequest`) instead of importing from `@features/monitoring/metricsRepository`.** `shared` may not import from `@features/*` (boundaries policy, task 009) — confirmed by lint failures when I tried the direct import. Only the JSON wire shape is shared between handler and repository, never the TS type.
9. **`shared/testdoubles/data/designer.ts` bakes `visible`/`enabled` as static booleans** matching each scenario's default values, rather than the reference's live `when` predicate functions — a predicate can't cross the MSW/JSON boundary, and `project()` (task 046) doesn't exist yet to compute them from raw input. Documented in the file's header as a scoped simplification, not a general mechanism.
10. **`solutionsRepository.loadPackage()` sends file metadata only, not the file's bytes.** `shared/api/request()` (026) only carries JSON bodies; a `FormData` body would silently serialize to `{}` via `JSON.stringify`. Rather than ship code that quietly drops the upload, the method sends `{fileName, size}` with a `CONTRACT: TBD` comment — real transport (multipart vs. pre-signed) is explicitly unspecified per §9.2 anyway.
11. **`Can.test.tsx`/`usePermissions.test.tsx` rely on `devMockAdapter`'s fixed identity** (`granted: ['target.use', 'instance.bindTarget']`) rather than a custom auth override, since that identity already exercises both the omit and render paths (`target.use` granted, `metrics.query` not) without extra plumbing.
12. **`AuthProvider` gained a test-only `adapterOverride` prop.** Vitest always runs with `import.meta.env.DEV === true`, so the production `selectAdapter` branch always resolves to `devMockAdapter`, which never fires `onSessionExpired` by design — leaving the 401 → unauthenticated wiring otherwise untestable through the real component. The prop is documented as never set in application code.
13. **The idle-lock draft mechanism (031) ships as hooks only (`useDraftAutosave`, `useDraftRestore`), no banner component.** Designer (task 046+) doesn't exist yet in Phase 2; the round trip (write → lock → sign in → restore) is proven at the hook level in `useIdleTimer.test.ts`, and the "explicit banner with Discard" requirement is satisfied by the hook returning `{ draft, discard }` for Designer to render when it lands — never a silent restore.
14. **`Paged<T>` is the only type fully centralized into `shared/api/contracts/` for task 034**, de-duplicating it out of `instancesRepository.ts` and `metricsRepository.ts`. The other ~10 `CONTRACT: TBD` shapes stay colocated in their owning repository file (each with its own header) rather than a wholesale move — given 11 working, tested files already existed with inline types from task 033, a full relocation was judged unnecessary churn beyond what task 034 asks for (`src/shared/api/contracts/*.ts` "colocated hand types", which `common.ts` satisfies).
15. **`generate-types.mjs` looks for `../contracts/openapi.{yaml,json}`** (the repo-root `contracts/` folder from design §4.1) — no path is specified anywhere in the plan, so this is the module's own placeholder location, stated in its own comment.

## Testing

Every command below was actually run from `frontend/`, output inspected, not assumed:

```
npm run lint                    # clean throughout; re-run after every task
npm run typecheck               # clean throughout
npx vitest run                  # full suite: 113 test files, 466 tests, all passing (final run)
```

Task-specific `Verify` lines from the plan, run exactly as specified and passing:

```
npm run lint && npm run typecheck && npm test -- src/shared/auth --environment jsdom   # FE-031: 5 files, 35 tests
npm run lint && npm run typecheck && npm test -- src/shared/authz                      # FE-032: 4 files, 18 tests
```

Additional targeted runs during development (all passing before moving to the next task):

- `npx vitest run src/shared/api/` — FE-026–029, FE-034, FE-035 (http, correlation, types, plane clients, errorCodes, retry, queryClient, queryKeys, contracts/common, download)
- `npx vitest run src/shared/auth/` — FE-030, FE-031
- `npx vitest run src/shared/testdoubles src/features` — FE-033 (14 files, 50 tests: data-module assertions, all 11 repositories against the default MSW handlers)
- `npm run lint:fixtures && npm run lint:fixtures:assert` — proves the new `vite-plane-host.ts` fixture fails on `no-restricted-syntax` as intended (9 fixtures now, all on their intended rule)
- `node scripts/generate-types.mjs` — confirmed exit code 1 with the "no OpenAPI document" message, listing both candidate paths it checked
- `npm run build` — production build succeeds (995 modules, one bundle); `grep -r "Devon Ramirez" dist/assets/*.js` returns no match, confirming `devMockAdapter`'s fixture identity is absent from the production bundle

Acceptance checklist items verified concretely (not just asserted), one example each:

- **026** — `RequestCoordinator.next()` test: calling it twice for the same key asserts the first signal's `aborted` is `true` before the second is returned. `logging` describe block spies on `console.error` and asserts a deliberately-included secret string never appears in any call.
- **027** — `planeClients.test.ts`'s `it.each` proves all three clients reject both an absolute and a protocol-relative URL.
- **028** — `retry.test.ts` asserts `shouldRetry(0, new ApiError({status:422}))` is `false` and `errorCodes.test.ts` asserts an unknown code's message falls through to the server's own text rather than being swallowed.
- **029** — `queryClient.test.ts` reads `client.getDefaultOptions().mutations?.retry` and asserts it is exactly `false`.
- **030** — `localSessionAdapter.test.ts`'s `onSessionExpired() fires when ANY request comes back 401` test triggers a real 401 through MSW and asserts the listener fired.
- **031** — `RequireAuth.test.tsx` distinguishes `arrival=session` (triggered via the adapter's `onSessionExpired` callback) from `arrival=idle` (triggered via fake timers) in the redirected URL's query string.
- **032** — `permissions.typetest.ts` is a real compile-time gate: `npm run typecheck` passing today with three `@ts-expect-error` lines is the proof they fire (an unused directive is itself a `tsc` error).
- **033** — `instances.test.ts` asserts every `healthVocab`/`versionVocab` key and four of five `notFoundVocab` keys (see Ruling on `restart_expected`) is reachable from the 12-row fixture.
- **034** — `node scripts/generate-types.mjs` run directly, output captured above.
- **035** — `download.test.ts` spies on `globalThis.fetch` and asserts it is never called.

## Status

DONE — all ten tasks implemented, tested, lint-clean, typecheck-clean, full suite green, production build succeeds. `docs/plans/frontend/task-list.md` rows 026–035 flipped to `✅` (register only — no GitHub issues opened or closed, no commits made, per this batch's constraints). Everything remains **unstaged/staged in the working tree** for the user to review and commit.

Two things worth the user's attention before commit, not blockers:

1. **Ruling 6** (fixture-vs-HTTP repository collapse) is an architectural interpretation of an ambiguity between §9.1's diagram and every per-task Files list — worth a second pair of eyes before Phase 3 tasks (which consume these same repository files) proceed.
2. **`solutionsRepository.loadPackage()`** (Ruling 10) does not actually upload file bytes yet — `shared/api/request()` has no binary-body support. This is faithful to the plan (upload transport is explicitly `CONTRACT: TBD`), but real file upload will need either an `http.ts` extension or a presigned-URL flow before task 040 (Load-a-package dialog) can be more than a fixture.
