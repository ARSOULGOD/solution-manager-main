# frontend

The console: schema-driven renderer, plane-split API clients, version/restart UX, declared-inventory management.

**Stack:** React 19 + Vite + TypeScript strict + MUI 9 · **Status:** **all 125 plan tasks built** (phases 0–8), plus follow-on tasks `FE-126`–`FE-148`, on `dev/frontend`; **fixture-backed, no server**. **UNBASELINED.**

📍 **Picking this up?** Read [`../docs/design/frontend/design.md` §28](../docs/design/frontend/design.md#28-as-built-map-2026-09-03) first — the as-built map: what exists, which module implements which design section, and the known gaps.

```bash
npm ci
npm run dev        # console against MSW fixtures; drop contracts/packages/*/package.json on the Solutions catalog
npm run lint && npm run typecheck && npm test && npm run build
```

### The verification gates

Each is a required CI job. Run them before claiming a change is done — several exist because something
shipped green for weeks without them.

| Command | Proves | Task |
|---|---|---|
| `verify:production` | what the shipped **artefact** must not contain — see below | `FE-101` |
| `verify:csp` | `default-src 'self'`, every relaxation recorded, zero egress at runtime | `FE-100` |
| `verify:palette` | every colour meets WCAG 2.2 AA in both themes, and the contrast maths' own tests | `FE-097` |
| `verify:perf` | dedup, visibility gating, refresh flooring/pause, legend, debounce, render budgets | `FE-103` |
| `verify:reflow` | 320 px reflow + real-axe over every screen, both themes (SC 1.4.10) | `FE-110` |
| `verify:bundle` | initial JS payload stays under its gzip ceiling, ECharts and the Data Grid stay lazy | `FE-096` |
| `test:a11y` | `jest-axe` over every screen state ⚠️ **flaky under full-suite load** — see below | `FE-098` |
| `lint:fixtures:assert` | every guardrail lint rule still fails on a file that violates it (13 fixtures) | `FE-009` |
| `check:forbidden-deps` | no MUI X Pro/Premium in `package.json` or the lockfile | `FE-020` |
| `e2e` | the seven journeys, CSP screens, reflow — `preview` (production build) + `fixture` (dev server) | `FE-099` |

⚠️ **`test:a11y` is a known flake, and it is not yours.** `InstancesPage.a11y.test.tsx` times out under
full-suite parallelism — reproduced on `main` with all of Phase 8 stashed (3 failed / 348 passed). It is a
timeout, never an axe violation, and it passes in isolation. Needs a deliberate `testTimeout` on that file
or the DataGrid scan split; tracked in the plan's Review section under "Found, not fixed".

## Where the seam is, and how to replace a fixture with a real client

Nothing in this console knows whether a fixture or a real server answered. The seam is one layer, and it is
the same layer for all twelve features:

```
Component ──► useXxx() ──► XxxRepository (interface)
                                └── createXxxRepository(client)   ← calls the REAL plane client
                                      ├── today:  intercepted by MSW (src/shared/testdoubles/)
                                      └── later:  nothing intercepts it — the handlers just stop being registered
```

There is deliberately **one** implementation per repository, not a fixture one and a real one. "Fixture vs
real" is entirely a question of whether MSW is running — never a second code path a component could pick the
wrong one of.

**So replacing a fixture with a real backend is subtractive, not additive:**

1. Point `config.json`'s `apiBasePath` at the gateway. Nothing is rebuilt — see `config-replaceable` below.
2. Stop registering the MSW handlers. In DEV that is the `import.meta.env.DEV` block in
   [`src/app/main.tsx`](src/app/main.tsx); in tests it is `src/test/setup.ts`. Production already registers
   nothing (`FE-D24`, asserted by `verify:production`).
3. Replace the hand-written types in `src/shared/api/contracts/` **wholesale** with generated ones when an
   OpenAPI document exists (`FE-D7` — a fixture is never promoted to a contract). Repository files change
   their import; their call sites do not.
4. Grep `CONTRACT: TBD` — that marker enumerates the plan's own debt, every field standing in for something
   genuinely unspecified.

⚠️ **Nothing under `src/shared/testdoubles/` may be imported as a VALUE by shipped code.** An `import type`
is erased; a value import pulls the whole fixture module into the bundle. That is how the restart-strategy
catalog shipped until `FE-101` caught it (`verify:production`'s `no-fixture-dataset`).

## Accessibility

Target **WCAG 2.2 AA**. Two checklists, and both are needed — the automated pass catches structural defects
and cannot walk a focus trap, a keyboard chart cursor, or a jump-to-field scroll:

- [`../docs/checklists/a11y-checklist.md`](../docs/checklists/a11y-checklist.md) — per PR touching a screen
  or shared component. Its "Why this exists" section records the two real defects `FE-110`'s first real run
  caught: a **broken skip link on every authenticated screen** (invisible to `jest-axe`, which never renders
  `index.html`) and three keyboard-inaccessible scroll regions.
- [`../docs/checklists/a11y-manual-keyboard-script.md`](../docs/checklists/a11y-manual-keyboard-script.md) —
  run per release, for the paths axe cannot reach.

`npm run test:a11y` and `npm run verify:reflow` are the automated halves; `verify:reflow` runs **real**
`axe-core` in a browser at six widths in both themes, which is a different and stricter instrument than
`jest-axe`'s jsdom shim.

## Serving this build (Content-Security-Policy · air-gap)

`FE-100` / `FE-D41`. The policy is written once, in [`index.html`](index.html), and shipped verbatim:

```
default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self';
font-src 'self'; connect-src 'self'; base-uri 'self'; form-action 'self';
object-src 'none'; frame-src 'none'; worker-src 'none'
```

**SM packaging must serve the identical policy as a `Content-Security-Policy` response header**, and add the directives a `<meta>` delivery ignores:

| Header-only | Value | Why the `<meta>` cannot carry it |
|---|---|---|
| `frame-ancestors` | `'none'` | Ignored in `<meta>`. This is the clickjacking control — a static SPA has no other way to set it. |
| `report-uri` / `report-to` | *(when a backend endpoint exists)* | Ignored in `<meta>`. Nothing collects reports today (design §20 / `FE-102`). |

Two more obligations for whoever builds `runtime/`:

- **Serve `style-src` a nonce and drop the exception.** `'unsafe-inline'` is there only because a statically served SPA has no per-response document to carry a nonce for Emotion — the exception in [`csp-exceptions.json`](csp-exceptions.json) records the way out.
- **No egress.** `REQ-003` is air-gap: no analytics, no beacon, no CDN, no Google Fonts (fonts are self-hosted from `public/fonts`). `npm run verify:csp` fails the build on a relaxation with no recorded reason, on `eval(` in a shipped chunk, on an analytics/CDN origin in the bundle, and on any request leaving the origin while the app runs.

`npm run dev` is served the document **without** the tag (a `vite.config.ts` plugin strips it): Vite's inline React-Refresh preamble and `ws:` HMR would otherwise have to be written into the shipped policy. `e2e/csp-screens.spec.ts` re-applies the shipped policy to the dev document — plus exactly three named dev allowances — so every real screen is still walked under it.

## What the shipped artefact must not contain (`npm run verify:production`)

`FE-101`, design §21. Seven assertions run against a **real production build** — six things the artefact must not carry and one it must:

| Assertion | What fails the build |
|---|---|
| `no-auth-mock` | `shared/auth/devMockAdapter` shipping code, or the fixture identity appearing anywhere (`FE-D24`) |
| `no-dev-gallery` | any module under `app/dev/` shipping code, a `/dev/gallery` reference, or an emitted `Gallery-*.js` chunk |
| `no-msw` | MSW in the module graph, its browser API in a chunk, **or its service-worker script anywhere in the built artefact** |
| `no-fixture-dataset` | anything under `shared/testdoubles/` or `src/test/` shipping code — a `import type` is erased, a value import is not |
| `no-secret` | a PEM key, JWT, cloud key or credential-shaped literal in a chunk or in `config.json` (`I6`, `FE-D14`) |
| `no-plane-hostname` | an absolute URL in any of **our** modules, or an `apiBasePath` that is an origin rather than a path (`FE-D19`) |
| `config-replaceable` | `config.json` missing, content-hashed, referenced from `index.html`, or inlined into a chunk (`FE-D14`) |

Three things make this a check rather than a claim:

- **It reads the artefact, not a model of it.** The gate runs a real `vite build` into a throwaway directory and walks **what was written** — emitted chunks, copied `public/` assets, and whatever `closeBundle` hooks changed. An in-memory build (`write: false`, what `verify:bundle` uses) never copies `publicDir`, which is exactly where the shipped MSW worker was hiding, and it reports different rendered module sizes.
- **The instrument is proved first.** `scripts/production-assertions.selftest.mjs` drives all seven with violations — six described bundles, plus a **real** production build of [`src/test/production-fixtures/ships-dev-code.ts`](src/test/production-fixtures/ships-dev-code.ts), which imports the mock, the gallery, MSW and a fixture dataset without a `import.meta.env.DEV` guard. A clean baseline must stay silent, so an assertion that fires on everything fails too.
- **Replaceability is demonstrated, not inferred.** [`e2e/config-replaceable.spec.ts`](e2e/config-replaceable.spec.ts) copies the built `dist/`, edits **one file**, serves the copy, and watches the console call the new prefix. Nothing is rebuilt in between.

⚠️ **MSW's service worker stays in `public/`, and the build strips it.** A service worker must be served from the origin root, so `msw init public/` puts it where `publicDir` will copy it straight into `dist/` — which is how it shipped. `vite.config.ts`'s `dropDevOnlyAssetsFromBuild` removes it in `closeBundle`. Moving the file to a `dev/` directory and serving it from an `apply: 'serve'` middleware was tried first and reverted: it broke 11 fixture e2e tests under parallel service-worker registration (`docs/plans/frontend/reports/task-101-report.md`, Ruling 1). `no-msw` walking the built artefact is what keeps the cleanup hook honest — if it stops running, the build goes red.

## Before writing code here

This component does not own the platform architecture. Read, in order:

| What | Where |
|---|---|
| Cross-cutting decisions | [`../docs/design/DECISIONS.md`](../docs/design/DECISIONS.md) |
| Overall architecture | [`../docs/design/architecture.md`](../docs/design/architecture.md) |
| **This component's design** | [`../docs/design/frontend/design.md`](../docs/design/frontend/design.md) |
| This component's decisions | [`../docs/design/frontend/decisions.md`](../docs/design/frontend/decisions.md), ID family `FE-D*` |
| Dynamic UI | [`../docs/design/dynamic-ui/design.md`](../docs/design/dynamic-ui/design.md) |
| Dynamic Metrics | [`../docs/design/dynamic-metrics/design.md`](../docs/design/dynamic-metrics/design.md) |
| Approved plan | [`../docs/plans/frontend/implementation.md`](../docs/plans/frontend/implementation.md) (task content) · [`execution.md`](../docs/plans/frontend/execution.md) §7 (status) |
| What each task produced | [`../docs/plans/frontend/reports/`](../docs/plans/frontend/reports/) |
| Setup checklist | [`../docs/checklists/new-module.md`](../docs/checklists/new-module.md) |

⚠️ **The baseline is UNBASELINED** — `SM-D*` decisions are PROPOSED and the design has open blocking items. The code here was written against `PROPOSED` decisions under the plan's own amendment route; **that does not ratify them**, and `docs/design/frontend/design.md` §28.4 lists where the code has moved ahead of the specs that own it. See [`../docs/tasks/codex-review-triage.md`](../docs/tasks/codex-review-triage.md).

**If the design is wrong or missing, raise it — do not invent architecture here.** Component-level decisions get an ID in the `FE-D*` family and are recorded in the design folder, not in code comments.
