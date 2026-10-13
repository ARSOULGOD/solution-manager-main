# FE-101 · Production build assertions — Report

## What I implemented

Design §21 lists what a shipped console must not contain. FE-101 turns that list into a gate that
runs against a **real production build** and fails it — six *must-nots* and one *must*, plus the
thing the task's second acceptance bullet actually asks for and which is easy to skip: proof that
each assertion goes red when violated.

Task spec: `docs/plans/frontend/implementation.md` §FE-101. Refs `FE-D14`, `FE-D24`, and — reached
by the assertions themselves — `FE-D19`, `I6`, design §9.1/§9.3, §21.

| File | Summary |
|---|---|
| `frontend/scripts/production-assertions.mjs` | New. The seven assertions as a **pure** function over a described build — no `build()`, no `fs`, no `process.exit`. Purity is what makes the self-test possible at all. Exports `ASSERTIONS` (id, title, why, check) and `assertProductionBuild(subject)`. |
| `frontend/scripts/production-assertions.selftest.mjs` | New. Drives every assertion with a violation: six described bundles (one per rule, plus a clean baseline that must stay silent) **and one real Vite production build** of a fixture entry that imports the mock, the gallery, MSW and a fixture dataset on purpose. |
| `frontend/src/test/production-fixtures/ships-dev-code.ts` | New. That fixture entry. Nothing imports it; it exists to be built and caught, the same role `src/test/lint-fixtures/` plays for task 009's lint rules. |
| `frontend/scripts/verify-production-build.mjs` | New. `npm run verify:production`: self-test first, then a **real build written to a throwaway output directory** and walked file by file, then the runtime replaceability proof. |
| `frontend/e2e/config-replaceable.spec.ts` | New. Copies the built `dist/`, edits **one file**, serves the copy on its own port, and watches the console call the new prefix. Two tests: the retarget itself, and a guard that only `config.json` differs between `dist/` and the copy. |
| `frontend/vite.config.ts` | `dropDevOnlyAssetsFromBuild()` — an `apply: 'build'` plugin whose `closeBundle` removes `mockServiceWorker.js` from the output. See "Real defects found", item 1. |
| `frontend/src/app/router.tsx` | The gallery route spread is guarded by a literal `import.meta.env.DEV` at the call site as well as inside `buildGalleryRoutes`. See Ruling 2. |
| `frontend/src/features/designer/restartStrategies.ts` | **New home** for `RESTART_STRATEGIES` and its type, moved out of the fixture layer. See "Real defects found", item 2. |
| `frontend/src/shared/testdoubles/data/designer.ts` | The restart-strategy catalog removed (54 lines). |
| 11 files under `frontend/src/features/designer/` | Import rewrites; `RestartStrategyFixture` → `RestartStrategy` (it is no longer a fixture). |
| `frontend/package.json` | `verify:production` script. |
| `frontend/playwright.config.ts` | `config-replaceable.spec.ts` added to the `preview` project, with a header note on why it brings its own server. |
| `frontend/README.md` | New section: the seven assertions, what fails each, and the MSW-worker placement rule. |
| `.github/workflows/frontend.yml` | New `production assertions (FE-101)` job — its own named check with its own browser install, like `reflow` and `csp`. |

### The seven assertions

| Assertion | What fails the build |
|---|---|
| `no-auth-mock` | `shared/auth/devMockAdapter` shipping code, or the fixture identity in any emitted text (`FE-D24`) |
| `no-dev-gallery` | any module under `app/dev/` shipping code, a `/dev/gallery` reference, an emitted `Gallery-*.js` chunk |
| `no-msw` | MSW/`@mswjs`/`@bundled-es-modules` in the graph, its browser API in a chunk, **or its worker script anywhere in the built artefact** |
| `no-fixture-dataset` | anything under `shared/testdoubles/` or `src/test/` shipping code; fixture markers in emitted text |
| `no-secret` | PEM key, JWT, AWS/GitHub/Slack/Google key, basic-auth URL, credential-shaped literal in **our** modules, or a credential-shaped **key name** in `config.json` (`I6`) |
| `no-plane-hostname` | an absolute URL in any module built from `src/`, or an `apiBasePath` that is an origin rather than a path (`FE-D19`) |
| `config-replaceable` | `config.json` absent, content-hashed, emitted as a build asset, referenced from `index.html`, or inlined into a chunk |

## Real defects found and fixed

Both were found by running the gate for the first time, not by reading the code.

**1 · The production build shipped Mock Service Worker.** `dist/mockServiceWorker.js`, 9.6 KB of
request-interception machinery, in every build since task 039. `msw init public/` is the documented
install and `public/` is copied verbatim into `dist/`, so the worker rode along — invisible to a
bundle analysis, because `publicDir` is not part of the module graph at all. (The shipped CSP's
`worker-src 'none'` would have blocked registration, so this was a latent exposure rather than a
live one — but "another control happens to cover it" is not the guarantee `FE-D24` states.)

**The obvious fix was tried first and rejected on evidence — see Ruling 1.** What ships is a
`closeBundle` hook (`dropDevOnlyAssetsFromBuild` in `vite.config.ts`) that removes the file from the
build output. The worker stays in `public/`, where a service worker has to be, because it must be
served from the origin root (`DEFAULT_WORKER_URL = "/mockServiceWorker.js"`, verified in
`node_modules/msw/lib/browser/index.mjs:3611`).

**2 · The production bundle carried a fixture dataset.**
`src/features/designer/DesignerPage/DesignerForm.tsx:51` imported `RESTART_STRATEGIES` from
`@shared/testdoubles/data/designer` as a **value**, which pulled the whole fixture module —
invented diff rows, secret-mask entries, replica observations — into
`assets/InstanceCategoryRoute-*.js`. Ten sibling files import from the same module and none of them
ship, because every one of those is `import type`, which TypeScript erases. One value import was
the difference.

The catalog itself is legitimately shipped code — it is a vocabulary the Designer renders, not data
standing in for a backend's answer — so the fix is a move, not a deletion: it now lives at
`src/features/designer/restartStrategies.ts` with the `CONTRACT: TBD` marker it always deserved
(the apply protocol it describes is `DECISIONS.md` open item 3, still unspecified). The type is
renamed `RestartStrategy`, since `…Fixture` in a shipped module is exactly the confusion that put
it in the fixture layer to begin with.

## Rulings

**1 · The structural fix for MSW was wrong, and the e2e suite proved it.** The first version moved
`mockServiceWorker.js` to a `dev/` directory no build copies and served it from an `apply: 'serve'`
middleware — a guarantee by construction, which is normally the better kind. It broke the fixture
e2e suite: **11 failures**, clustered on Designer, journeys and 320px reflow, all reporting
`Failed to fetch dynamically imported module: …/InstanceCategoryRoute.tsx`, and — the tell — a
*different* set of 11 on each run.

That non-determinism sent me down two wrong paths before I measured anything: a stale dev server
(there were two orphaned ones on port 5173, and `playwright.config.ts` has
`reuseExistingServer: !CI`, so it was a real possibility — killing them changed nothing), then a
cold `node_modules/.vite` forcing dependency re-optimization (clearing it made things worse, not
better). What settled it was a controlled bisect:

| Tree | `npm run e2e` |
|---|---|
| `main` (changes stashed) | **166 passed, 0 failed** |
| Mine, worker in `dev/` + middleware | **11 failed** |
| Mine, worker back in `public/`, middleware off | **168 passed, 0 failed** |

Serving a service worker correctly to five browser contexts registering it concurrently is Vite's
static middleware's job, and it does it better than a fifteen-line handler. So the mechanism is a
cleanup step after all — and the usual objection to a cleanup step ("it is one config mistake away
from not running") is answered by this task's own gate: `verify:production` walks the **real built
artefact**, so a plugin that stops running turns the build red. A guard checked by the artefact it
guards is not one mistake away; the mistake fails the build.

**Cost, stated:** the guarantee is now "the build removes it" rather than "the build cannot reach
it". That is weaker in principle. It is what the evidence permits.

**2 · The gate builds and writes, rather than building in memory.** `verify-bundle.mjs` (FE-096) uses
`build({ write: false })` and that is right for a bundle budget — it only needs chunk sizes. It is
the wrong subject here, and it actively misled me twice:

- `publicDir` is not copied by an in-memory build, so the MSW worker — the single biggest thing this
  task found — was only visible because I walked `public/` separately. That was a proxy for the
  artefact, and a proxy is exactly what the `dev/` experiment then invalidated.
- Rendered module sizes differ. `src/app/dev/gallery.routes.ts` reported `renderedLength: 0` under
  `write: false`, so I concluded a call-site `import.meta.env.DEV` guard in `router.tsx` changed
  nothing and reverted it. Under a **written** build the same module reports **99 bytes** —
  `function buildGalleryRoutes() { return []; }`, a folded husk the bundler keeps because it cannot
  prove the call returns `[]`. With the call-site guard restored, it is back to 0. The guard was
  right; the instrument was wrong.

So the gate now builds into a throwaway directory and walks what was written. It never touches
`dist/`, so it cannot disturb the build the `preview` e2e server is serving.

**3 · The assertions measure shipped bytes, not graph membership.** Even with the call-site guard,
`gallery.routes.ts` still appears in `chunk.moduleIds` — with zero rendered bytes. Failing on
membership would make `no-dev-gallery` red for a module that contributes nothing to the artefact,
which is a gate nobody could get green. `modules()` therefore skips zero-byte modules, and the four
module-presence assertions read "ships code from" rather than "contains".

**4 · Two detection strategies, chosen per assertion.** Assertions 1–4 read the **module graph**,
not chunk text, because minification renames identifiers and drops comments — a grep for
`createDevMockAdapter` tests the minifier's mood, while a module id is what the bundler actually
decided to include. Marker strings are kept as a *second* net, never the first. Assertions 5–6 read
**rendered per-module text** (`chunk.modules[id].code`), which attributes every byte to its origin
module. That is what lets `no-plane-hostname` be strict about our own code while ignoring the
`http://localhost` React Router carries as an internal fallback origin: a rule of the form "no
absolute URL anywhere in the bundle" would be either false or unenforceable.

**5 · `no-secret` is narrowed twice, deliberately.** The credential-shaped-assignment scan runs only
over **our** modules and the artefact's own files, because minified dependency code assigns short
strings to variables named `token` constantly — a parser's lookahead, a CSS tokenizer — and a gate
that cries wolf gets switched off. It also requires the value to contain a **non-letter**:
`passwordLabel: 'Password'` is a real string in `shared/i18n/messages.en.ts`, and failing the build
on it would teach people to ignore the gate. Real credentials carry a digit, a symbol or
punctuation essentially without exception; English words do not. The *unambiguous* shapes (PEM
blocks, JWTs, `AKIA…`, `ghp_…`, `xox…`, `AIza…`, basic-auth URLs) run over everything, dependencies
included, because a key committed into a `node_modules` fixture is still a key served to every
browser. This is a recorded limit: it is not a general secret scanner and must not be described as
one.

**6 · `config.json` is checked for forbidden key *names*, before any value exists.** It is the one
file an operator edits by hand, which makes it the most likely place for someone to put a password.
`no-secret` fails on a key matching `secret|password|credential|token|apikey|privatekey` regardless
of its value — `I6` enforced at the point where the mistake would be made.

**7 · Replaceability is demonstrated, not inferred.** The static half proves the *negatives*: no
chunk was handed the config's values, the file is not hashed, `index.html` does not pin its name.
None of those is the claim. `e2e/config-replaceable.spec.ts` copies `dist/`, rewrites `apiBasePath`
to `/replaced-without-rebuild/api`, serves the copy from its own throwaway static server, and
asserts the login submit went to `/replaced-without-rebuild/api/cp/auth/login` **and** that nothing
went to `/api/`. It runs on the `preview` project only to inherit that project's `webServer` (which
is what guarantees `dist/` exists); it never touches `baseURL`, and it serves a *copy* so mutating
`dist/config.json` cannot change what `csp.spec.ts` and `smoke.spec.ts` are looking at.

**8 · The self-test runs first, and the clean baseline is part of it.** `verify:production` runs
`production-assertions.selftest.mjs` before it looks at the real build and treats a failure there as
fatal — the same argument `verify:palette` makes for running the contrast maths' own tests before
trusting the contrast gate. Three things are checked per assertion: it fires on a violation, it stays
silent on a clean build, and it does **not** fire on the other six assertions' violations
(independence). The real violating build covers the four tree-shaking guarantees, where the risk is
not the logic but the bundler's output shape changing under us — as Ruling 2 shows it can, between
two modes of the *same* bundler. The other three have no honest build fixture: you cannot commit a
credential to prove a credential is detected.

## Verification

```
npm run lint                 ✓ 0 problems
npm run typecheck            ✓ clean
npm test                     ✓ 321 files, 2145 tests
npm run build                ✓ dist/ no longer contains mockServiceWorker.js
npm run verify:bundle        ✓ initial JS 134.5 KiB gzip, ceiling 160.0 (unmoved)
npm run verify:palette       ✓
npm run check:forbidden-deps ✓
npm run lint:fixtures:assert ✓ 10 fixtures failed on the intended rule
npm run test:a11y            ⚠ 101 files, 351 tests — flaky here AND on main; see note below
npm run verify:csp           ✓ 17 tests
npm run e2e                  ✓ 168 tests
npm run verify:production    ✓ 12/12 self-test checks, 7/7 assertions, 2/2 e2e
```

**The e2e number is the one that matters here**, because it is what caught Ruling 1's regression and
what has to be clean for the fix to be believed:

| Tree | `npm run e2e` |
|---|---|
| `main`, changes stashed | 166 passed, 0 failed |
| FE-101 with the `dev/` worker + serve middleware | **11 failed** |
| FE-101 as delivered (worker in `public/`, build strips it) | 168 passed, 0 failed |

The 168 is 166 plus `config-replaceable.spec.ts`'s two new tests. The `fixture` project runs the
whole thing against the **dev server**, which is the only place MSW exists, so its 100+ fixture-backed
assertions are also the proof that nothing in this task disturbed the dev fixture path.

**`test:a11y` note — pre-existing flake, confirmed against `main`.** `test:a11y` failed on several
runs here: 2, then 5, then 3, then 2 tests, always `Test timed out`, never an axe violation, and
always in `InstancesPage.a11y.test.tsx` (once also `Gallery.a11y.test.tsx`). Both files carry
comments saying they sit at the edge of their budget under full-suite parallelism. **The same gate
run on `main` with this task's changes stashed fails three of the same tests**, so it is not this
task's. In isolation the file passes 8/8, and one full run here did pass 351/351 — the flake is
load-dependent, not deterministic.

I originally wrote this up as "machine load" on the strength of the file's own comment, which was
reasoning rather than evidence; the stashed `main` run is the evidence, and it says the same thing
more firmly. See follow-up 1.

## Acceptance

- ☑ **six assertions in CI** — seven, in fact: the six *must-nots* plus `config-replaceable`. Job
  `production assertions (FE-101)` in `.github/workflows/frontend.yml`.
- ☑ **each demonstrably fails when violated** — `production-assertions.selftest.mjs`, run as step 1
  of the gate itself, so the proof cannot rot separately from the check.

## Limits, stated

- **`no-secret` is a shape scanner, not a secret scanner.** A credential that does not look like one
  is not caught. Same recorded limit as `FE-D23`'s redaction scanner: it reduces the surface, it does
  not close it.
- **`no-plane-hostname` is strict on our modules only.** Today `src/` contains zero absolute URLs
  outside comments (which do not survive the build), so strictness costs nothing. The first
  legitimate documentation link inside a user-facing string will fail this gate — deliberately: that
  link ships a host, and the decision to ship it should be made rather than defaulted into.
- **MSW's absence is now "the build removes it", not "the build cannot reach it".** Weaker in
  principle than the `dev/` placement, and chosen on evidence (Ruling 1). What holds it up is that
  the gate reads the artefact: if `dropDevOnlyAssetsFromBuild` stops running, `no-msw` fails.
- **The gate builds a second time.** `verify:production` runs its own `vite build` into a temp
  directory, so CI pays for one extra build (~2 s) on top of the `build` job's. Deliberate: sharing
  `dist/` would couple this gate to whatever else last wrote there.
- **Fonts remain absent** — `public/fonts/` holds only a README, so `font-src 'self'` currently has
  nothing to serve. Pre-existing, `FE-002`'s gap, not a `no-*` violation and not fixed here.

## Follow-ups worth an issue (not this task's)

1. **`InstancesPage.a11y.test.tsx` times out under full-suite load, on `main` as well as here.**
   Reproduced with this task's changes stashed: 3 failed / 348 passed, same file, all timeouts.
   `Gallery.a11y.test.tsx` joined it on one run. Both already document being near their budget. CI
   runs the full suite, so `test:a11y` is a latent red build waiting on a slow runner — worth either
   raising those two files' `testTimeout` deliberately or splitting the DataGrid axe scan.
2. **The e2e `fixture` project is sensitive to service-worker timing.** Ruling 1's failure mode was
   ugly to diagnose because a worker registration problem surfaces as `Failed to fetch dynamically
   imported module` on an unrelated route. A guard that fails loudly when MSW is not controlling the
   page would have turned a two-hour bisect into one assertion.
