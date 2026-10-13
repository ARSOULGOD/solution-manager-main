# FE-010 · Dev-only component gallery — Report

## What I implemented

The `/dev/gallery` route and its container page — the surface Phase 1 tasks (`011`–`025`) will
each register their own component section into, and that the automated a11y sweep (task 098)
will later walk.

### Files created

- `frontend/src/app/dev/Gallery.tsx` — the page component (default export `Gallery`). Renders a
  heading, a theme toggle (reads/writes `useUiStore`, so the page is usable standalone before
  `AppShell`'s own toggle exists — task 036), and a `sections: GallerySection[]` registry
  rendered as one `<section>` per entry. Starts **empty** — nothing under `src/shared/ui/` exists
  yet — with an explicit "No components registered yet" message rather than silently rendering
  nothing (`FE-D10`'s "empty is never a silent success" applies here too, informally). The
  `GallerySection` type and the `sections` array are what each Phase 1 task's own file imports
  and pushes into, per the Phase 1 preamble ("register the component in the gallery").
- `frontend/src/app/dev/gallery.routes.ts` — `buildGalleryRoutes()`, called from `router.tsx`.

### Files modified

- `frontend/src/app/router.tsx` — **not in the brief's literal Files list** (same situation as
  FE-004/FE-005 with `main.tsx`): spreads `...buildGalleryRoutes()` into `appRoutes`, before the
  wildcard `NotFound` route.
- `frontend/src/app/router.test.tsx` — added a `matchRoutes` case and a deep-link render case for
  `/dev/gallery`, following the file's existing pattern for every other route.

## A real defect this surfaced, and how it was found

My first implementation hoisted `const Gallery = lazy(() => import('./Gallery'));` to module
scope in `gallery.routes.ts`, with the DEV check only guarding which `RouteObject[]` was
*returned*. I verified against the brief's own acceptance line ("absent from the production
bundle") by actually running `npm run build` and inspecting `dist/` — and a `Gallery-*.js` chunk
was there, size ~0.6kB. **The dynamic `import()` call was unconditional; only the returned route
array was conditional**, and Vite/Rollup discover `import()` specifiers during module-graph
construction, independent of whether the surrounding code path is reachable.

Second attempt kept the `isDev: boolean = import.meta.env.DEV` parameter (added for testability)
but moved the `lazy()` call inside the `if`. Rebuilt — **the chunk still shipped**. A runtime
function parameter defeats Vite's per-module `import.meta.env.DEV` → literal `false` replacement:
the bundler can't prove a parameter's value across the call site, so it can't dead-code-eliminate
the branch.

**Fix:** dropped the parameter entirely. `buildGalleryRoutes()` takes no arguments; the check is
a literal `if (!import.meta.env.DEV) { return []; }` with the `lazy()` call textually inside the
`else` path. Rebuilt: `978` modules transformed (was `979`), no `Gallery-*.js` chunk, and
`grep -rl "dev/gallery\|Component gallery\|No components registered" dist/` matches nothing.
This is recorded as a code comment in `gallery.routes.ts` so nobody reintroduces the parameter
for "testability" and silently breaks the guarantee again.

Losing the parameter meant losing the corresponding "returns `[]` when not DEV" unit test — that
branch is now proven by the build-inspection evidence below instead, which is the real acceptance
criterion anyway; a unit test can't observe bundler-level tree-shaking.

## Testing

- `Gallery.test.tsx`: renders the heading and empty-registry message; clicking the toggle button
  flips `useUiStore`'s `mode` and the rendered "Current theme: …" text, using real `react-dom`/
  `act` (no RTL yet — task 008).
- `gallery.routes.test.ts`: `buildGalleryRoutes()` returns one route (`/dev/gallery`) under
  Vitest, where `import.meta.env.DEV` is `true`.
- `router.test.tsx`: `/dev/gallery` resolves via `matchRoutes`; a deep link to it renders
  "Component gallery" through the real `Suspense`/lazy-load path.
- **Production-bundle inspection** (the acceptance line this task actually cares about):
  `npm run build` → `978` modules, no `Gallery-*.js` chunk in `dist/assets/`; a `grep` across
  `dist/` for `dev/gallery` / `Component gallery` / `No components registered` finds nothing.
- **Real browser, both themes** — no e2e harness exists yet (task 008), so I set up a one-off
  Playwright check outside the repo (`npm install playwright@1.62.1 --no-save` in a scratch
  directory, Chromium already cached locally — nothing added to `frontend/package.json`):
  started the Vite dev server, navigated to `/dev/gallery`, confirmed the heading/empty-message/
  light-mode styling, clicked "Switch to dark", confirmed the text and the full visual re-theme
  (screenshots taken in both modes), and confirmed zero console/page errors throughout.

```
$ npm run lint && npm run typecheck && npm test && npm run build
```
All four green. 103 tests passing across the suite (5 new in this task).

## Status

DONE

## Commit

`5c55a7f..458aecc` on `dev/frontend`. Register:
[`execution.md` §7](../execution.md#7-task-register--all-125), row `010`. Issue
[#30](https://github.com/parikshit-coe/solution-manager/issues/30) (closed).
