# FE-009 · Architecture guardrails in lint — Report

## What I implemented

Design rules from §4.4 as build/CI failures, so a later task cannot quietly
import ECharts outside `ChartRenderer`, pull in MUI X Pro, paint a raw hex,
or wire a deep cross-feature import.

### Config

- `frontend/eslint.config.js` — `eslint-plugin-boundaries` v7 zones
  `app | feature | shared | contracts` (each real tree plus a
  `src/test/lint-fixtures/<zone>` mirror so a fixture is classified the same
  way a production file would be). `partialMatch: false` keeps repo-root
  `contracts/` distinct from the `src/contracts/` generated-types placeholder.
  Policies: `app` → shared/contracts/features; `feature` → shared/contracts,
  its own files, and any feature's public `index.ts`; `shared` gets no grant
  onto `feature` (absent allow = deny under `default: "disallow"`).
- `no-restricted-imports` for echarts (allowlist is
  `src/features/monitoring/chart/ChartRenderer.tsx`, not the folder),
  `@shared/api` from `shared/ui`, and the three Pro/Premium packages. Flat
  config **replaces** this rule wholesale when two blocks overlap — an
  earlier draft silently dropped the echarts and `shared/ui` restrictions
  because the Pro block came last. Each overlapping block now restates every
  restriction that must still apply.
- `no-restricted-syntax` for `#rrggbb` / `rgb(` / `hsl(` (and alpha variants)
  outside `src/shared/theme`.
- `contracts/**` (and the contracts fixture mirror) may not import `react`,
  `@mui/*`, or `@app`/`@shared`/`@features`.
- `eslint-import-resolver-typescript` so `@app/*` aliases resolve as local
  files; without it, boundaries classifies unresolvable aliases as external
  and the zone rules never fire. Pin `^4.4.5` from npm `latest` at time of
  writing, peers-checked against ESLint 9.

### Scripts and CI

- `frontend/scripts/check-forbidden-deps.mjs` — greps `package.json` and
  `package-lock.json` for `@mui/x-data-grid-pro`, `-premium`, and
  `@mui/x-charts-pro`. Import-time enforcement is the ESLint rule; this
  catches a package that is installed but never imported.
- `frontend/scripts/assert-lint-fixtures.mjs` — not in the brief's Files
  list. `npm run lint:fixtures` **must** exit non-zero (`execution.md` §12);
  a CI job that only asserts non-zero could go green on a parse error in one
  file while another rule is dead. This script is the green check that each
  of the seven fixtures still fails on the named rule.
- `.github/workflows/frontend.yml` — matrix gates `check:forbidden-deps` and
  `lint:fixtures:assert` (quoted, because YAML would otherwise parse the
  colon). Same situation as FE-008 adding an `e2e` job: Gate A names these
  commands and they are not proven in CI without the workflow change.
- `frontend/tsconfig.json` — `exclude: ["src/test/lint-fixtures"]` so the
  deliberately-broken fixtures do not fail `tsc`.

### Fixtures (`src/test/lint-fixtures/`)

| File | Rule it must trip |
|---|---|
| `feature/inventory/deep-import.ts` | `boundaries/dependencies` |
| `shared/imports-feature.ts` | `boundaries/dependencies` |
| `app/imports-echarts.ts` | `no-restricted-imports` (echarts) |
| `shared-ui/calls-api.ts` | `no-restricted-imports` (`@shared/api`) |
| `app/imports-pro.ts` | `no-restricted-imports` (Pro/Premium) |
| `app/raw-colour.ts` | `no-restricted-syntax` |
| `contracts/imports-react.ts` | `no-restricted-imports` (react / `@app`) |

Cross-feature and shared→feature fixtures use relative imports inside the
mirror tree. An `@features/*` alias would resolve to `src/features/`, which
does not exist yet, and the plugin would treat the import as external.

I2: `eslint-plugin-boundaries` v7 policy shape (`policies` / entity
selectors) and the TypeScript resolver requirement were taken from
https://www.jsboundaries.dev/docs/quick-start/ and
https://www.jsboundaries.dev/docs/guides/typescript-support/ (fetched
2026-08-20), not from memory of the v4/v5 `element-types` rule.

## Testing

```
$ npm run lint && npm run typecheck && npm run check:forbidden-deps && npm test -- --run && npm run build
$ npm run lint:fixtures:assert   # exits 0 — seven files, intended rule each
$ npm run lint:fixtures          # exits 1 — 13 errors across those files
```

Default lint / typecheck / 120 tests / production build (978 modules, same
as FE-008's baseline) all green. `lint:fixtures` is the required non-zero
run; `lint:fixtures:assert` is what CI actually needs to stay green.

`npm run format` still flags only the four pre-existing `shared/theme/`
files this task did not touch — every new/modified file here is
Prettier-clean.

## Status

DONE

## Commit

`a98e139..190999c` on `dev/frontend`. Register:
[`execution.md` §7](../execution.md#7-task-register--all-125), row `009`. Issue
[#29](https://github.com/parikshit-coe/solution-manager/issues/29).
