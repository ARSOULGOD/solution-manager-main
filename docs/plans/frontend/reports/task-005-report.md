# FE-005 · Runtime configuration boot — Report

## What I implemented

Deploy-time configuration, fetched before the app renders, with a visible boot failure instead
of a silent default.

### Files created

- `frontend/public/config.json` — the shape design §13 specifies:
  `{ apiBasePath: "/api", idleTimeoutMinutes: 30, channel: "stable" }`. No plane hostname
  (`FE-D19`), no secret (`FE-D14`).
- `frontend/src/app/boot/loadConfig.ts` — `RuntimeConfig`/`RuntimeConfigError` types,
  `parseRuntimeConfig(input)` and `loadConfig(url = '/config.json')`. `loadConfig` fetches with
  `cache: "no-store"`; a non-`ok` response, a JSON parse failure, or a schema violation all throw
  `RuntimeConfigError` — nothing falls back to a default except `idleTimeoutMinutes`, which
  defaults to 30 per the brief. An unrecognised extra field in the body is silently ignored
  (destructuring only reads the three known keys).
- `frontend/src/app/boot/RuntimeConfig.tsx` — `RuntimeConfigBoot` (a component wrapping
  `children`, fetching once on mount via `useEffect`, rendering nothing while loading, a
  self-contained `BootErrorPage` — plain semantic HTML, `role="alert"`, no MUI dependency, styled
  like `NotFound.tsx` — on failure, or a `RuntimeConfigContext.Provider` around `children` once
  resolved) and `useRuntimeConfig()` (throws if called before boot resolves — a real invariant
  violation, not a recoverable case, since nothing should render behind `RuntimeConfigBoot`
  before it resolves).
- `frontend/src/app/boot/buildInfo.ts` — `getBuildInfo()` reads
  `import.meta.env.VITE_APP_VERSION`/`VITE_APP_COMMIT`, falling back to `'dev'`/`'unknown'` when
  CI hasn't injected them (nothing wires CI to set these yet — out of this task's Files list;
  task 049's support menu is the consumer).
- `frontend/src/vite-env.d.ts` — **not in the brief's literal Files list**, added because
  `import.meta.env.VITE_APP_VERSION` otherwise resolves through Vite's fallback
  `ImportMetaEnv` index signature (`[key: string]: any`), which trips
  `@typescript-eslint/no-unsafe-assignment` under this repo's `recommendedTypeChecked` lint
  config. Declaring the two keys as `string | undefined` is the standard Vite pattern for this
  and keeps `buildInfo.ts` free of `any`.
- `loadConfig.test.ts`, `RuntimeConfig.test.tsx`, `buildInfo.test.ts` — see Testing below.

### Files modified

- `frontend/src/app/main.tsx` — **not in the brief's literal Files list** (FE-004 modified this
  same file for an equivalent reason: task 004 doesn't list `main.tsx` either, and had to wire
  `AppProviders` into it). Wraps the render tree in `RuntimeConfigBoot`:
  `<RuntimeConfigBoot><AppProviders /></RuntimeConfigBoot>` — outside `AppProviders` so the
  resolved config is available to everything inside it, including the HTTP core (task 026) and
  idle lock (task 031) once they land and need `apiBasePath`/`idleTimeoutMinutes`.

## Design decisions worth recording

- **Where the fetch happens.** "Fetched before first render" is satisfied as *before the real
  app renders* (children of `RuntimeConfigBoot`), not literally before React's first paint —
  `RuntimeConfigBoot` itself has to mount to run the effect. The alternative (an `await
  loadConfig()` ahead of `createRoot(...).render(...)` in `main.tsx`) would work too, but the
  brief's Files list names `RuntimeConfig.tsx` as a component, which only makes sense under this
  reading.
- **Required vs. defaulted fields.** Only `idleTimeoutMinutes` defaults; `apiBasePath` and
  `channel` missing or wrong-typed both fail the boot. The brief singles out
  `idleTimeoutMinutes` for a default and says nothing else does, so a missing `apiBasePath`
  correctly renders the boot error page rather than silently degrading.
- **No `ErrorPanel` dependency.** FE-005's only declared dependency is FE-001; FE-006 (which
  creates `ErrorPanel`) had not been implemented yet at this point in the task register, and
  even done in the same session, `BootErrorPage` is deliberately self-contained/theme-free so
  boot can fail visibly even if theme setup itself were somehow implicated.

## Testing

- `loadConfig.test.ts`: `parseRuntimeConfig` accepts a well-formed body, defaults
  `idleTimeoutMinutes` to 30, ignores an unknown extra field, and rejects a non-object body, a
  missing `apiBasePath`, a missing `channel`, and a non-numeric `idleTimeoutMinutes`. `loadConfig`
  resolves on success, calls `fetch` with `{ cache: "no-store" }`, and rejects with
  `RuntimeConfigError` for a non-ok response (missing file), a JSON parse failure (malformed
  file), and a body that fails validation.
- `RuntimeConfig.test.tsx`: renders `RuntimeConfigBoot` with real `react-dom`/`act` (no RTL yet —
  test harness is task 008; follows the same local `IS_REACT_ACT_ENVIRONMENT` pattern as
  `router.test.tsx`), asserting children render with the resolved config on success, and that a
  fetch failure or a validation failure both render a `role="alert"` boot error page instead of
  the children.
- `buildInfo.test.ts`: asserts `getBuildInfo()` never returns a blank `version`/`commit`.

```
$ npm run lint && npm run typecheck && npm test && npm run build
```
All four green. 88 tests passing at the point this task landed (98 after FE-006 was added in the
same session).

Also inspected the production bundle directly: `dist/config.json` matches the source file, and a
`grep` for hostnames/env-var names across `dist/assets/*.js` found nothing beyond a react-router
internal (`http://localhost` used as a dummy `new URL()` base when there's no `window.location`)
— not a plane-hostname leak.

## Status

DONE

## Commit

`9f541e8..031f8bc` on `dev/frontend`. Register:
[`execution.md` §7](../execution.md#7-task-register--all-125), row `005`. Issue
[#25](https://github.com/parikshit-coe/solution-manager/issues/25) (closed).
