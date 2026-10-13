# FE-001 · Application scaffold, strict TypeScript, lint, CI — Report

## What I implemented

A Vite + React + TypeScript scaffold in `frontend/`, with ESLint (flat config), Prettier, and a
real 4-gate GitHub Actions CI workflow at `.github/workflows/frontend.yml`.

### Files created

- `frontend/package.json` — scripts (`dev`, `build`, `preview`, `typecheck`, `lint`, `test`,
  `format`, `format:fix`), pinned dependencies.
- `frontend/vite.config.ts` — Vite + Vitest config in one file; defines `resolve.alias` for the
  four path aliases, consumed by both the app build and Vitest.
- `frontend/tsconfig.json` — app project (`strict`, `noUncheckedIndexedAccess`,
  `exactOptionalPropertyTypes`, `noImplicitOverride`, `verbatimModuleSyntax`), path aliases,
  `include: ["src"]`.
- `frontend/tsconfig.node.json` — same strictness flags, scoped to `vite.config.ts`.
- `frontend/eslint.config.js` — flat config: `@eslint/js` recommended, `typescript-eslint`
  `recommendedTypeChecked`, `eslint-plugin-react-hooks` flat `recommended-latest`,
  `eslint-plugin-import`'s `import/order` rule (with `pathGroups` for the four aliases so they
  sort as "internal" rather than being treated as unscoped external packages),
  `@typescript-eslint/no-explicit-any: error`, `eslint-config-prettier` last to disable
  stylistic conflicts. `eslint.config.js`/`vite.config.ts` get `disableTypeChecked` since neither
  is inside `tsconfig.json`'s `include`.
- `frontend/.prettierrc` — single-quote, trailing-comma-all, 100-col.
- `frontend/index.html` — viewport meta is exactly
  `width=device-width, initial-scale=1` (no `maximum-scale`, no `user-scalable=no`), plus a skip
  link (`<a href="#main-content">`) as the first body element.
- `frontend/src/app/main.tsx` — entry point; imports `App` via the `@app/*` alias, mounts into
  `#root`.
- `frontend/src/app/App.tsx` — minimal root component: a `<main id="main-content">` (the skip
  link's target) with a heading. No routing, no MUI, no theme — none of that is in scope for this
  task.
- `.github/workflows/frontend.yml` — one job, matrixed over
  `gate: [lint, typecheck, test, build]`, each running the matching `npm run <gate>` on Node 22
  with `npm ci`. No `continue-on-error` anywhere. Path-filtered to `frontend/**` and the workflow
  file itself; runs on PRs and on push to `main`.

### Files created beyond the brief's literal list, and why

- `frontend/.prettierignore` — excludes `dist`, `coverage`, `node_modules`, and `README.md`.
  Without it, `prettier --check .` reformats the pre-existing `frontend/README.md` (a file this
  task didn't touch) and would eventually choke on build output. Trivial, and required to make
  the mandated `.prettierrc` actually usable — not a new feature.
- `frontend/src/shared/index.ts`, `src/features/index.ts`, `src/contracts/index.ts` — one-line
  placeholder barrels (`export const X_PLACEHOLDER = '...' as const`), each with a comment
  explaining they exist purely so the corresponding path alias has something to resolve.
- `frontend/src/app/aliases.test.ts` — imports through all four aliases (`@app/App`,
  `@shared/index`, `@features/index`, `@contracts/index`) and asserts each resolves. This is the
  concrete proof for the acceptance line "aliases resolve in Vite, tsc and Vitest": Vitest reuses
  `vite.config.ts`'s `resolve.alias` (so a passing run proves Vite's resolver), and the file is
  covered by `tsconfig.json`'s `include` (so a clean `typecheck` proves tsc's resolver). The
  `@app/*` alias is additionally proven end-to-end through the real build graph, since
  `main.tsx` (the actual `vite build` entry point) imports `App` via `@app/App` — `@shared/*`,
  `@features/*`, `@contracts/*` are not yet reachable from the entry graph (nothing uses them
  yet), so `vite build` proves `@app/*` specifically; Vitest + tsc prove all four. I did not
  wire `@shared`/`@features`/`@contracts` into `App.tsx` just to exercise them in the build — that
  would be inventing app structure ahead of the tasks that actually need it.
- I did **not** add `@testing-library/react`, `jsdom`, `@testing-library/jest-dom`, or
  `@vitest/coverage-v8`. Nothing in this task renders a component under test or needs a DOM
  environment or coverage reporting — `aliases.test.ts` runs fine under Vitest's default `node`
  environment. Adding that stack now would be unused dependency weight; it's a natural fit for
  whichever task first needs to test a rendered component.

## Version pins (recorded here since there's no PR)

I don't have training-data-era knowledge of what's current as of 2026-08-19, so I verified every
pin against the live npm registry (`npm view <pkg> dist-tags/versions/time --json`) rather than
guessing, per the repo's I2 invariant. Full reasoning below; short version:

| Package | Pin | Why |
|---|---|---|
| `react`, `react-dom` | `~19.2.8` | Tilde, not caret — the brief asks to pin the **minor**. 19.2 is the current mainline minor (still receiving patches; three minors — 19.0/19.1/19.2 — are patched in lockstep, confirming 19.2 is the maintained line, not a stray one). |
| `react-router` | `^7.18.2` | Brief asks to pin the **major**, so caret is correct here (allows 7.x updates, blocks 8.0.0). I checked `react-router`'s publish timeline: v8.0.0 shipped 2026-06-17 (~2 months before today), while v7 is still getting patches (7.18.2, 2026-07-28) in parallel. Given this task only pins the dependency — nothing in the scaffold actually imports/uses it yet — I chose the older, longer-proven major (v7) over the 2-month-old v8 to minimize risk for whichever task wires up routing. This is a judgment call under real uncertainty; flagging it explicitly as requested. |
| `typescript` | `^6.0.3` | Initially pinned `5.9.3` (last 5.x) out of caution, since `typescript-eslint`'s peer range looked like it excluded 6.x. Re-read the actual peer constraint (`>=4.8.4 <6.1.0`) and it **does** include 6.0.x. Confirmed empirically: Vite's own official `create-vite` `template-react-ts` (fetched live via `gh api`) now pins `typescript: "~6.0.2"`, paired with the same `vite@^8` / `@vitejs/plugin-react@^6` I'm using. TS 7.0 (a newer major, released 2026-07-08, ~6 weeks old) is **not** yet supported by `typescript-eslint` 8.67's peer range, so I stayed on the 6.0.x line, not `latest`. |
| `vite` | `^8.2.1` | Current major, ~3 months old as of today, still shipping patches; `@vitejs/plugin-react@^6` requires `vite ^8.0.0` so this pairing is forced once you're on the current plugin. |
| `eslint` | `^9.39.5` | Deliberately **not** `10.x` despite it being `latest` (10.8.1). `eslint-plugin-import`'s `peerDependencies` only lists `^2 || ... || ^9` — no `^10` — and I didn't want an unvalidated combination in a CI gate that must never have `continue-on-error`. ESLint 9's `9.39.5` is still an actively-patched line (`@eslint/js` ships a matching `maintenance: 9.39.5` tag), so this isn't a stale/abandoned version, just the one with full peer coverage. |
| `@vitejs/plugin-react` | `^6.0.5`, `typescript-eslint` | `^8.67.0`, `vitest` `^4.1.11`, `eslint-plugin-react-hooks` `^7.1.1`, `eslint-plugin-import` `^2.32.0`, `eslint-config-prettier` `^10.1.8`, `prettier` `^3.9.6`, `@types/*` matched to the runtime/React versions above | all taken from each package's `latest` dist-tag at time of writing, cross-checked for peer-dependency compatibility with the rest of the set (verified via `npm view <pkg> peerDependencies --json`, then confirmed with a real `npm install` producing 0 peer conflicts and 0 vulnerabilities). |

Node: pinned `engines.node` to `>=22.12.0 <23` and CI to `node-version: '22'`, matching the
sandbox's installed Node (22.23.0) exactly rather than using a `lts/*` floating tag — so local
runs and CI runs are on the same major/behavior, not just "whatever LTS resolves to that day".

## A real problem this surfaced: TS project references + `noEmit`

Originally wired `tsconfig.json` and `tsconfig.node.json` together via TypeScript project
references (`"references": [{ "path": "./tsconfig.node.json" }]`, `typecheck: "tsc -b"`), matching
the brief's two-file request. This broke: `tsc -b` requires every project that appears in another
project's `references` array to be `"composite": true` and non-`noEmit` — but `tsconfig.node.json`
only exists to typecheck `vite.config.ts`, and setting it composite meant it had to emit
declaration output somewhere, which is pointless overhead for two files with zero cross-imports.

I confirmed this isn't a "did it wrong" — Vite's own official template shows a **three**-file
version (`tsconfig.json` shell + `tsconfig.app.json` + `tsconfig.node.json`) where the root is an
empty `{"files": [], "references": [...]}` shell. I reproduced that pattern in an isolated
scratch dir and it built cleanly with no composite requirement — the requirement only appears
when the *referencing* project itself owns real source files (as our two-file `tsconfig.json`
does, per the brief). Since the brief asks for exactly two files, not three, and there's no actual
need for build-mode incrementality here, I dropped `"references"` entirely and made `typecheck`
run `tsc -p tsconfig.json && tsc -p tsconfig.node.json` — two independent, non-building type
checks. Simpler, and sidesteps the whole composite/emit entanglement.

Also hit `noUnusedLocals`/`baseUrl`-deprecation noise while iterating: TS 6.x deprecates `baseUrl`
(warns it stops working in 7.0), so `tsconfig.json`'s `paths` use `./`-prefixed relative values
instead of `baseUrl` + bare `paths`.

## Testing — actual command output

### `npm run lint` (clean)
```
> @solution-manager/frontend@0.0.0 lint
> eslint . --max-warnings=0

(no output, exit 0)
```

### `npm run typecheck` (clean)
```
> @solution-manager/frontend@0.0.0 typecheck
> tsc -p tsconfig.json && tsc -p tsconfig.node.json

(no output, exit 0)
```

### `npm run test`
```
> @solution-manager/frontend@0.0.0 test
> vitest run

 RUN  v4.1.11 /Users/banalapradeep/Documents/iesoftek/solution-manager/frontend

 Test Files  1 passed (1)
      Tests  4 passed (4)
   Start at  23:11:42
   Duration  160ms (transform 29ms, setup 0ms, import 45ms, tests 3ms, environment 0ms)
```

### `npm run build`
```
> @solution-manager/frontend@0.0.0 build
> vite build

vite v8.2.1 building client environment for production...
transforming...
✓ 15 modules transformed.
rendering chunks...
computing gzip size...
dist/index.html                  0.39 kB │ gzip:  0.27 kB
dist/assets/index-B5ZPFp7P.js  190.52 kB │ gzip: 60.00 kB

✓ built in 84ms
```

### Deliberate `any` fails lint

Created `src/app/__deliberate-any.ts`:
```ts
export function deliberateAny(value: any): any {
  return value;
}
```
```
> eslint . --max-warnings=0

/Users/.../frontend/src/app/__deliberate-any.ts
  1:38  error  Unexpected any. Specify a different type  @typescript-eslint/no-explicit-any
  1:44  error  Unexpected any. Specify a different type  @typescript-eslint/no-explicit-any

✖ 2 problems (2 errors, 0 warnings)
```
Exit code: **1**. File removed immediately after the check.

### Deliberate type error fails typecheck

Created `src/app/__deliberate-type-error.ts`:
```ts
const n: number = 'this is a string, not a number';
export { n };
```
```
> tsc -p tsconfig.json && tsc -p tsconfig.node.json

src/app/__deliberate-type-error.ts(1,7): error TS2322: Type 'string' is not assignable to type 'number'.
```
Exit code: **2**. File removed immediately after the check.

### Final clean re-run (post-cleanup, all four gates + Prettier)
Re-ran `lint`, `typecheck`, `test`, `build`, and `npx prettier --check .` one more time after
removing the fixtures and the `.prettierignore` addition — all five exited 0 (full output in the
tool transcript; identical to the clean runs pasted above, plus
`All matched files use Prettier code style!`).

## Files changed

- `frontend/package.json` (new)
- `frontend/package-lock.json` (new, generated by `npm install`)
- `frontend/vite.config.ts` (new)
- `frontend/tsconfig.json` (new)
- `frontend/tsconfig.node.json` (new)
- `frontend/eslint.config.js` (new)
- `frontend/.prettierrc` (new)
- `frontend/.prettierignore` (new, not in the brief's literal list — see above)
- `frontend/index.html` (new)
- `frontend/src/app/main.tsx` (new)
- `frontend/src/app/App.tsx` (new)
- `frontend/src/app/aliases.test.ts` (new, not in the brief's literal list — see above)
- `frontend/src/shared/index.ts`, `frontend/src/features/index.ts`,
  `frontend/src/contracts/index.ts` (new, not in the brief's literal list — see above)
- `.github/workflows/frontend.yml` (new)
- Removed the stale, gitignored `frontend/node_modules/` left over from an earlier experiment
  before running `npm install` fresh.

## Self-review findings

- Caught and reverted an unintended side effect: running `prettier --check .` before adding
  `.prettierignore` would have reformatted the pre-existing `frontend/README.md` (unrelated to
  this task) had I run `--write` broadly. I ran `--write` on just `README.md` once to see the
  diff, confirmed it was a large, out-of-scope table reformat, and reverted it with
  `git checkout -- README.md` before adding the ignore file instead.
- Caught the TS project-references/composite issue (see above) before it shipped — the first
  `tsc -b` attempt failed outright, which is exactly the kind of thing "run the checks, don't
  assume" is supposed to catch.
- Verified `import/order` actually fires on real files, not just in theory — first `npm run lint`
  run genuinely failed on `main.tsx` and `aliases.test.ts`'s import ordering, which I then fixed
  in the source and pathGroups config.
- Left `dist/` untracked/removed after each build; it's already covered by the repo-root
  `.gitignore` (`dist/`, `node_modules/`), so no frontend-local `.gitignore` was needed.

## Issues or concerns

- **`react-router` major (v7 vs v8)** is a genuine judgment call, not a fact I could verify to a
  single right answer — flagged above with reasoning. If the team has a stronger opinion (e.g.
  "always track the newest major since nothing depends on it yet"), it's a one-line change with
  zero blast radius today since nothing imports `react-router` yet.
- **ESLint 9 vs 10**: same category — chose 9.x purely for `eslint-plugin-import` peer safety.
  If a later task wants ESLint 10, either `eslint-plugin-import` will need to have shipped a `^10`
  peer range by then, or it gets swapped for `eslint-plugin-import-x` (a maintained fork) — that's
  a config-only change, not a rule-name change for anything except `import/order`'s prefix.
- The skip link (`<a href="#main-content" class="skip-link">` in `index.html`) is functionally
  present and correctly targeted at `<main id="main-content">` in `App.tsx`, but is **not visually
  hidden-until-focus** — no CSS exists yet (MUI/theme is explicitly out of scope for this task).
  It's keyboard-reachable and correct today, just unstyled.
- `@shared/*`, `@features/*`, `@contracts/*` aliases are proven to resolve in tsc and Vitest, but
  not yet exercised by the production `vite build` graph, since nothing in the app imports through
  them yet (only `@app/*` is on the real entry path via `main.tsx`). This is expected for a
  scaffold with no feature code — noted so it isn't mistaken for an oversight.

## Status

DONE

## Commit

`d693205..db4aee1` on `dev/frontend`. Register: [`execution.md` §7](../execution.md#7-task-register--all-125), row `001`.
