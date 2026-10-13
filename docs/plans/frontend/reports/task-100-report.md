# FE-100 · CSP and air-gap verification — Report

## What I implemented

Design §21 asks for `default-src 'self'` and an air-gapped deployment that works with no egress
(`REQ-003`); design §26 item 10 left *"CSP vs ECharts — recorded exception if `unsafe-eval` is
required"* open. This task ships the policy, answers item 10, and turns both into gates that fail
the build (task spec: `docs/plans/frontend/implementation.md` §FE-100; deps 096).

| File | Summary |
|---|---|
| `frontend/index.html` | The policy itself, as a `<meta http-equiv="Content-Security-Policy">` — **the single source of truth**, with a comment saying what the dev server does with it and what packaging must add. |
| `frontend/vite.config.ts` | `stripCspInDev()` — a `apply: 'serve'` plugin that removes the tag from the **dev** document only. |
| `frontend/csp-exceptions.json` | New. The exception registry: one entry (`style-src 'unsafe-inline'`), one recorded *non*-need (`'unsafe-eval'`), an empty `codeExceptions`. |
| `frontend/scripts/verify-csp.mjs` | New. The static half + a spawn of the runtime half. Policy shape, exceptions discipline, the built `index.html` still carrying the policy, no `eval(` in a shipped chunk, no analytics/CDN/Google-Fonts origin in the bundle or the dependency set. |
| `frontend/e2e/csp.spec.ts` | New. The **production preview build** under the **exact** shipped policy: the policy is delivered, boot + sign-in + a failing submit raise zero violations and zero foreign requests, and a real ECharts chart renders under it. |
| `frontend/e2e/csp-screens.spec.ts` | New. All twelve real screens on the fixture server under the shipped policy plus three named dev allowances. |
| `frontend/e2e/csp/harness.ts` | New. What both specs share: the policy reader, the violation recorder, the foreign-request tracker, the negative control. |
| `frontend/playwright.config.ts` · `package.json` · `.github/workflows/frontend.yml` | `csp.spec.ts` → `preview`, `csp-screens.spec.ts` → `fixture`; `npm run verify:csp`; a `csp + air-gap (FE-100)` CI job (own browser install, like `reflow`). |

## The policy

```
default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self';
font-src 'self'; connect-src 'self'; base-uri 'self'; form-action 'self';
object-src 'none'; frame-src 'none'; worker-src 'none'
```

Written strictest-first and relaxed **only** where a measurement forced it. `img-src` has no `data:`,
`connect-src` no websocket, `worker-src` is `'none'` (MSW's service worker is dev-only, `FE-D24`) —
each of those was a candidate relaxation that turned out not to be needed, and the sweep across all
twelve screens is what says so.

## Design §26 item 10, answered: ECharts needs no `unsafe-eval`

Two independent proofs, because the question has been open on recall alone:

1. **Static.** The production bundle contains **zero** `eval(` and **zero** `new Function(`.
   `echarts@6.1.0`'s only `new Function` is a `JSON.parse` fallback
   (`typeof JSON !== 'undefined' ? JSON.parse(source) : new Function(...)`) and minification drops
   it. `verify-csp.mjs` now fails the build if either construct reappears in a shipped chunk.
2. **Runtime.** `e2e/csp.spec.ts` serves a same-origin harness page carrying the **shipped policy
   verbatim**, imports the **real built echarts chunk** (`dist/assets/echarts-*.js`) and renders a
   real line chart into a canvas. Zero violations, chart measured at 640 px.

The preview build has no backend, so no chart screen is reachable by navigation — that is why the
harness imports the chunk directly rather than pretending a chart screen exists. The exception
registry records the *non*-need, so item 10 is not re-opened from memory later.

## The one relaxation, and why it is recorded rather than quiet

`style-src 'unsafe-inline'`. Measured, not assumed: with `style-src 'self'` the production build
raises `style-src-elem` violations from the MUI chunk on first paint — MUI (`D7` 🔒, `FE-D4`) styles
through Emotion, which inserts `<style>` **elements** at runtime. Both strict alternatives need a
server that renders the document (a per-response nonce for Emotion's cache; hashes are impossible
for rules generated at runtime), and this console ships as a static SPA with SM packaging unwritten
(design §21). `script-src` keeps neither `'unsafe-inline'` nor `'unsafe-eval'`, so injected CSS
cannot execute. The entry carries a reason, `FE-D41`, a risk note and the way out.

**The generalised rule is the actual deliverable.** The task says an `unsafe-eval` must be *"a
recorded exception with a written reason — not a silent relaxation"*. Rather than special-case that
one source, `verify-csp.mjs` fails on **any** source that is not `'self'`/`'none'` without a
registry entry that has a reason, an `FE-D*` id and a follow-up — and equally on an entry that
outlives the source it justified. It holds for every future directive.

## Where the policy is delivered, and what packaging still owes

A static SPA has no response-header layer, so `<meta>` is the only seam this repo owns.
`frontend/README.md` now carries the packaging obligations: serve the identical policy as a header,
add `frame-ancestors 'none'` (a `<meta>` delivery ignores it — that is the clickjacking control),
and, when a document-serving layer exists, hand Emotion a nonce and delete the exception.

## Dev gets the policy removed, not loosened

Vite's dev server injects an inline React-Refresh preamble and opens a `ws:` HMR socket; both would
have to be written into the shipped string. The plugin strips the tag from the dev document instead
— the delta is removed from the build that never ships rather than added to the one that does.

That would leave the dev-only screens untested, so `csp-screens.spec.ts` re-applies the **shipped**
policy to the dev document with exactly three allowances, **derived from that same string** at
runtime rather than copy-pasted:

| Addition | Dev-only because |
|---|---|
| `script-src 'unsafe-inline'` | the React-Refresh preamble; a build emits external module scripts only |
| `connect-src ws:` | HMR; there is no HMR in a build |
| `worker-src 'self'` | MSW's `mockServiceWorker.js` (`FE-D24`); the shipped policy keeps `'none'` |

Everything else — `img-src`, `font-src`, `object-src`, `frame-src`, `base-uri`, `form-action`, and
`connect-src` for anything that is not the HMR socket — is enforced there exactly as it ships. A
directive that disappears from `index.html` fails the file rather than leaving a stale allowance.

## Verification

Every check below was run; the negatives were run by breaking the thing on purpose and restoring it.

| Check | Result |
|---|---|
| `npm run verify:csp` | **pass** — static checks + 17 runtime tests |
| `npm run e2e` (whole suite) | **166 passed** |
| `npm test` | **321 files, 2145 tests passed** |
| `npm run lint` · `npm run typecheck` | clean |
| `prettier --check` on every file touched | clean (5 pre-existing warnings elsewhere left alone) |

**Six negative tests — each gate demonstrably fails when violated:**

| Broken on purpose | Gate output |
|---|---|
| `script-src 'unsafe-eval'` added to the policy | ``` `script-src 'unsafe-eval'` is a relaxation with no entry in csp-exceptions.json ``` |
| the `<meta>` deleted from `index.html` | `index.html carries no Content-Security-Policy meta tag (FE-100).` |
| a registry entry for a source the policy no longer has | ``` records `img-src data:`, which the policy no longer contains. Delete the entry. ``` |
| an entry with `"reason": "needed by MUI"`, `"decision": "because"` | needs a decision id of the form `FE-D<n>` · needs a reason that says what breaks without it |
| `eval('1')` reachable in `main.tsx` | ``` assets/index-*.js contains `eval(` … Do not add `unsafe-eval` silently. ``` |
| a `fonts.googleapis.com` URL in `main.tsx` | ``` references fonts.googleapis.com. An air-gapped install cannot reach it (REQ-003). ``` |

**Both runtime harnesses prove themselves before they prove anything else.** A violation listener
that never fires, or a policy that was never delivered, makes every other assertion in this suite
pass by doing nothing — so each spec first loads an image from a forbidden origin and requires the
`img-src` violation to appear (`reflow.selftest.spec.ts`'s discipline). The dev sweep additionally
asserts the document it measured actually carries the policy.

## Rulings

1. **The policy lives in `index.html`, not in a config module.** One place, human-readable, and the
   shipped HTML *is* the source of truth — a generator would add a second place to read and let the
   artefact drift from the file that claims to describe it. Injection at build time was the
   alternative; it keeps `index.html` tidy at the cost of the policy being invisible in the source.
2. **Strip in dev, never loosen.** See above. Consequence: dev runs unprotected, which
   `csp-screens.spec.ts` compensates for.
3. **`frame-ancestors` is deliberately absent from the `<meta>`.** Browsers ignore it there and log
   a console warning; carrying it would look like protection while providing none. It is recorded as
   a packaging obligation instead.
4. **`report-only` rejected.** A policy that reports is a policy that is not enforced, and nothing in
   this repo collects reports (design §20 leaves error shipping to a backend endpoint that does not
   exist — `FE-102`).
5. **External origin *strings* in the bundle are printed, not failed.** Framework error messages
   carry documentation URLs (`mui.com`, `react.dev`, `reactrouter.com`, `github.com`,
   `css-tricks.com`) and `www.w3.org` is the SVG namespace. Failing on those would be a gate that
   teaches people to add allowlist entries. The hard failures are the ones that mean egress: a known
   analytics/RUM/CDN/Google-Fonts host, a `<script>`/`<link>` at an absolute URL, a CSS `@import`
   or `url()` at an absolute URL, an egress-capable dependency in `package.json`/the lockfile. The
   *proof* of "zero third-party requests" is the runtime assertion, not the grep.
6. **The specs also run inside `npm run e2e`**, not only inside `verify:csp`. They are part of the
   suite; the dedicated job exists so the gate can fail on its own name in CI.

## Follow-ups (not done here)

- **SM packaging (`runtime/`)**: the header, `frame-ancestors 'none'`, and the Emotion nonce that
  retires the one exception. Recorded in `frontend/README.md` and on the exception entry itself.
- **`dist/mockServiceWorker.js`** is still copied into the production build from `public/`. Nothing
  registers it there (`FE-D24`) and `worker-src 'none'` would block it if anything tried, but it has
  no business shipping — **`FE-101`'s "no MSW in a production bundle" assertion owns it**, and it is
  named here so it is not discovered twice.
- **Font binaries are still absent** (`public/fonts/README.md` — sourcing them is a follow-up from
  `FE-002`). `font-src 'self'` is correct for them today and will stay correct when they land, since
  they are self-hosted; nothing here depends on their arrival.
