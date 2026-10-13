// @ts-check
import js from '@eslint/js';
import eslintConfigPrettier from 'eslint-config-prettier';
import boundaries from 'eslint-plugin-boundaries';
import importPlugin from 'eslint-plugin-import';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

// Hex / rgb() / hsl() / hsla() literals — string or template-literal form.
// `shared/theme` is where these are allowed to exist at all (task 009,
// design §25 "raw hex outside shared/theme").
const RAW_COLOUR_PATTERN =
  '/#([0-9a-f]{3}|[0-9a-f]{4}|[0-9a-f]{6}|[0-9a-f]{8})\\b|\\b(rgb|rgba|hsl|hsla)\\(/i';

const RAW_MEDIA_PATTERN = '/@media\\b/i';

const NO_RAW_COLOUR = [
  {
    selector: `Literal[value=${RAW_COLOUR_PATTERN}]`,
    message: 'No raw colour outside shared/theme — use a theme token (design §8.1).',
  },
  {
    selector: `TemplateElement[value.raw=${RAW_COLOUR_PATTERN}]`,
    message: 'No raw colour outside shared/theme — use a theme token (design §8.1).',
  },
];

// No VITE_*_URL env var may name a plane host (FE-D19, task 027 — a task
// 009-style extension). The SPA gets its API base path from config.json at
// BOOT (`apiBasePath`), never from a build-time Vite env var, because a
// build-time value can't differ per deploy and would leak plane topology
// into the shipped bundle. Matches both member access
// (`import.meta.env.VITE_DP_URL`) and destructuring (`const { VITE_DP_URL
// } = import.meta.env`).
const VITE_URL_PATTERN = '/^VITE_.*_URL$/i';
const NO_VITE_PLANE_HOST = [
  {
    selector: `MemberExpression[property.name=${VITE_URL_PATTERN}]`,
    message:
      "No VITE_*_URL env var may name a plane host (FE-D19, task 027) — read config.json's apiBasePath at boot instead.",
  },
  {
    selector: `Property[key.name=${VITE_URL_PATTERN}]`,
    message:
      "No VITE_*_URL env var may name a plane host (FE-D19, task 027) — read config.json's apiBasePath at boot instead.",
  },
];

// Two prototype-only affordances that must never ship (task 036 acceptance:
// "no demo control"): a screen's own Demo state selector and any per-screen
// Scenario selector both key off this one identifier in the design's
// reference shell.jsx. Production state comes from the server — this
// catches either one being carried over into real app code, wherever it's
// declared (const, destructure, JSX prop), while leaving the fixture layer
// (`shared/testdoubles`) free to use it for its own dev-only purpose.
const NO_DEMO_STATES = [
  {
    selector: 'Identifier[name="DEMO_STATES"]',
    message:
      'DEMO_STATES is a prototype-only affordance (task 036, design shell.jsx reference) — production state comes from the server, never a picker. Keep it out of shipped code outside shared/testdoubles.',
  },
];

// `FE-D17` (task FE-052): the Designer save/apply/diff/config-hash payload
// is the rendered field set from `project()`, never RHF's own
// `getValues()` — that still carries inactive discriminator-branch keys
// the MI's config file must never see. `useDesignerDraft.ts` is the one
// place allowed to call it (it derives the reset-by-merge payload from
// it); everywhere else it would be a fresh chance to reintroduce the bug
// this decision exists to prevent.
const NO_GET_VALUES = [
  {
    selector: 'MemberExpression[property.name="getValues"]',
    message:
      "getValues() is never the save payload (FE-D17) — use project()'s rendered field set. Only src/features/designer/form/useDesignerDraft.ts may call it.",
  },
];

// FE-102 / `FE-D13` / `I6`: two rules that between them keep a secret out of
// a log, and keep every log line going through one place.
//
// `NO_SECRET_LOG_FIELD` is the half a type cannot cover. `logger`'s field
// type is primitives-only (`shared/observability/logger.ts`), so an object
// payload — a config value set, a metric series, a response body — is
// already a compile error. What still compiles is a primitive under a
// secret-shaped NAME: `logger.error('login failed', { password })`. This
// rejects it at the call site, before the runtime redactor has to be
// trusted to recognise it.
const SECRET_FIELD_PATTERN =
  '/^(.*(secret|password|passwd|pwd|credential|token|apikey|api_key|privatekey|private_key|authorization|cookie).*)$/i';
const NO_SECRET_LOG_FIELD = [
  {
    selector: `CallExpression[callee.object.name="logger"] ObjectExpression > Property[key.name=${SECRET_FIELD_PATTERN}]`,
    message:
      'A secret-shaped field must never be logged (I6, FE-D13). The value is redacted at runtime, but a log line that WANTS a credential in it is the bug — log the correlationId instead and let the server correlate.',
  },
  {
    selector: `CallExpression[callee.object.name="logger"] ObjectExpression > Property[key.value=${SECRET_FIELD_PATTERN}]`,
    message:
      'A secret-shaped field must never be logged (I6, FE-D13). The value is redacted at runtime, but a log line that WANTS a credential in it is the bug — log the correlationId instead and let the server correlate.',
  },
];

// Design §20: *"production error shipping waits on a backend log endpoint —
// do not invent a SaaS RUM product for air-gap on-prem."* `REQ-003` means an
// install may have no egress at all, so a logger that grew a transport would
// make the console broken rather than observable. Scoped to
// `shared/observability/**`, because that is the only module where adding one
// would look reasonable. `verify:csp` covers the other half — an egress-capable
// DEPENDENCY landing in package.json.
const NO_LOG_TRANSPORT = [
  {
    selector:
      'CallExpression[callee.name=/^(fetch)$/], NewExpression[callee.name="XMLHttpRequest"], MemberExpression[property.name=/^(sendBeacon|WebSocket|EventSource)$/]',
    message:
      'The logger ships nothing (design §20, REQ-003). Production error shipping waits on a BACKEND log endpoint — when it exists it is added here behind config.json, never as a direct call and never a SaaS RUM SDK.',
  },
];

// `NO_RAW_CONSOLE` keeps `shared/observability/logger.ts` the only place a
// console method is called. A raw `console.error(err)` hands the browser a
// live, expandable reference to the whole error — `cause` chain, response
// body, component state — which is exactly the leak `logger`'s
// primitives-only field type exists to prevent, reintroduced one call at a
// time. It also keeps `debug`/`info` behind the DEV fold: a raw call has no
// level discipline and ships.
const NO_RAW_CONSOLE = [
  {
    selector: 'MemberExpression[object.name="console"]',
    message:
      'Use `logger` from @shared/observability, not console directly (FE-102, design §20). Only shared/observability/logger.ts may call console.',
  },
];

const NO_RAW_MEDIA = [
  {
    selector: `Literal[value=${RAW_MEDIA_PATTERN}]`,
    message:
      'No raw @media outside shared/theme and shared/ui/layout — use a layout primitive or theme.breakpoints (task 105).',
  },
  {
    selector: `TemplateElement[value.raw=${RAW_MEDIA_PATTERN}]`,
    message:
      'No raw @media outside shared/theme and shared/ui/layout — use a layout primitive or theme.breakpoints (task 105).',
  },
];

// Later flat-config blocks replace `no-restricted-imports` wholesale for
// overlapping `files` globs — they do not merge. Each block below that sets
// the rule must therefore restate every restriction that should still apply.
const MUI_PRO_RESTRICTED_PATHS = [
  {
    name: '@mui/x-data-grid-pro',
    message: 'Community tier only in v1 (FE-D11) — column pinning/Excel export are not funded.',
  },
  {
    name: '@mui/x-data-grid-premium',
    message: 'Community tier only in v1 (FE-D11) — column pinning/Excel export are not funded.',
  },
  {
    name: '@mui/x-charts-pro',
    message:
      'Community tier only in v1 (FE-D11); charts render through ChartRenderer/ECharts (FE-D16).',
  },
];

const ECHARTS_RESTRICTED_PATTERN = {
  group: ['echarts', 'echarts/*'],
  message:
    'echarts may only be imported by src/features/monitoring/chart/ChartRenderer.tsx (FE-D16).',
};

const SHARED_UI_API_RESTRICTED_PATTERN = {
  group: ['@shared/api', '@shared/api/*'],
  message: 'shared/ui is presentational — it must not call the API layer (design §4.4).',
};

export default tseslint.config(
  {
    // `src/test/lint-fixtures/**` is deliberately excluded from the default
    // lint run — every file in it exists to VIOLATE a guardrail rule below.
    // `npm run lint:fixtures` (--no-ignore) proves each one still does.
    // `public/mockServiceWorker.js` is a generated, do-not-modify vendor
    // file (`npx msw init`, task 039) — not project source. It stays in
    // `public/` because a service worker must be served from the origin
    // root; `vite.config.ts` strips it from the BUILD instead, and FE-101's
    // `no-msw` assertion walks the built artefact to prove it did.
    ignores: [
      'dist/**',
      'coverage/**',
      'node_modules/**',
      'src/test/lint-fixtures/**',
      'public/**',
      'contracts/solution-package/generated/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked,
  reactHooks.configs.flat['recommended-latest'],
  {
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
      globals: {
        ...globals.browser,
      },
    },
    plugins: {
      import: importPlugin,
    },
    settings: {
      // Needed so eslint-plugin-boundaries classifies `@app/*` / `@shared/*`
      // / `@features/*` / `@contracts/*` as local files, not unresolvable
      // externals (jsboundaries.dev TypeScript guide, verified 2026-08-20).
      'import/resolver': {
        typescript: {
          alwaysTryTypes: true,
          project: './tsconfig.json',
        },
        node: {
          extensions: ['.ts', '.tsx', '.js', '.mjs'],
        },
      },
    },
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',

      'import/order': [
        'error',
        {
          groups: ['builtin', 'external', 'internal', 'parent', 'sibling', 'index'],
          pathGroups: [
            { pattern: '@app/**', group: 'internal' },
            { pattern: '@shared/**', group: 'internal' },
            { pattern: '@features/**', group: 'internal' },
            { pattern: '@contracts/**', group: 'internal' },
          ],
          pathGroupsExcludedImportTypes: ['builtin'],
          'newlines-between': 'always',
          alphabetize: { order: 'asc', caseInsensitive: true },
        },
      ],
    },
  },
  {
    // §4.1's import graph (implementation.md), CI-enforced per task 009:
    // app -> features; feature -> shared/contracts/own files freely,
    // another feature's public barrel (index.ts) only; shared -> never
    // features; contracts -> nothing in the tree (enforced separately
    // below, since that also covers external packages `dependencies`
    // doesn't check by default). Each element is declared twice: its real
    // location, and its `src/test/lint-fixtures/<zone>` mirror, so a
    // fixture there is classified the same way a real file would be.
    // `partialMatch: false` (full path from repo root) keeps `contracts`
    // from also matching the unrelated `src/contracts/` generated-types
    // placeholder (task 001) — a suffix match would conflate the two.
    files: ['src/**/*.{ts,tsx}', 'contracts/**/*.ts'],
    plugins: { boundaries },
    settings: {
      'boundaries/elements': [
        { type: 'app', pattern: 'src/app/**', partialMatch: false },
        {
          type: 'app',
          pattern: 'src/test/lint-fixtures/app/**',
          partialMatch: false,
        },
        {
          type: 'feature',
          pattern: 'src/features/(*)/**',
          capture: ['feature'],
          partialMatch: false,
        },
        {
          type: 'feature',
          pattern: 'src/test/lint-fixtures/feature/(*)/**',
          capture: ['feature'],
          partialMatch: false,
        },
        { type: 'shared', pattern: 'src/shared/**', partialMatch: false },
        {
          type: 'shared',
          pattern: 'src/test/lint-fixtures/shared/**',
          partialMatch: false,
        },
        { type: 'contracts', pattern: 'contracts/**', partialMatch: false },
        {
          type: 'contracts',
          pattern: 'src/test/lint-fixtures/contracts/**',
          partialMatch: false,
        },
      ],
    },
    rules: {
      'boundaries/dependencies': [
        'error',
        {
          default: 'disallow',
          policies: [
            // app -> shared / contracts / features (design §7: app may
            // import features; it also owns providers that consume shared).
            {
              from: { element: { type: 'app' } },
              allow: {
                to: {
                  element: { types: { anyOf: ['shared', 'contracts', 'feature'] } },
                },
              },
            },
            // feature -> shared / contracts, any depth.
            {
              from: { element: { type: 'feature' } },
              allow: { to: { element: { types: { anyOf: ['shared', 'contracts'] } } } },
            },
            // feature -> its OWN files, any depth (captured feature name matches).
            {
              from: { element: { type: 'feature' } },
              allow: {
                to: {
                  element: {
                    type: 'feature',
                    captured: { feature: '{{from.element.captured.feature}}' },
                  },
                },
              },
            },
            // app / feature -> ANY feature's public barrel only (index.ts).
            // Catches the cross-feature case the policy above doesn't
            // (different captured feature name) without needing to name
            // every feature pair.
            {
              from: { element: { types: { anyOf: ['app', 'feature'] } } },
              allow: {
                to: { element: { type: 'feature', fileInternalPath: 'index.ts' } },
              },
            },
            // shared -> contracts (FE-D34, task 122): contracts/ is a leaf
            // — it imports nothing, not React, not the DOM, not
            // `@shared/*` (enforced separately below) — so this creates no
            // cycle, and mirrors the app -> contracts / feature -> contracts
            // policies above. Needed so the fixture layer can read
            // `contracts/packages/*.json` and call `project()` directly,
            // rather than duplicating a package's projected output by hand.
            {
              from: { element: { type: 'shared' } },
              allow: { to: { element: { type: 'contracts' } } },
            },
            // shared gets no policy granting it `feature` — stays
            // disallowed by `default`. Not restated as a rule: an absent
            // allow IS the enforcement here. Proved by
            // `src/test/lint-fixtures/shared/imports-feature.ts` (task 009).
          ],
        },
      ],
    },
  },
  {
    // Default src surface: Pro/Premium forbidden everywhere; echarts
    // forbidden except ChartRenderer (FE-D16). shared/ui is handled in a
    // later block that restates these plus the API restriction.
    files: ['src/**/*.{ts,tsx}'],
    ignores: [
      'src/features/monitoring/chart/ChartRenderer.tsx',
      'src/shared/ui/**',
      'src/test/lint-fixtures/shared-ui/**',
    ],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: MUI_PRO_RESTRICTED_PATHS,
          patterns: [ECHARTS_RESTRICTED_PATTERN],
        },
      ],
    },
  },
  {
    // ChartRenderer is the single allowed echarts importer. Pro still
    // forbidden here — a chart must not pick up Data Grid Pro by accident.
    files: ['src/features/monitoring/chart/ChartRenderer.tsx'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: MUI_PRO_RESTRICTED_PATHS,
        },
      ],
    },
  },
  {
    // Leaves do not fetch (§4.4): shared/ui is presentational only.
    files: ['src/shared/ui/**/*.{ts,tsx}', 'src/test/lint-fixtures/shared-ui/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: MUI_PRO_RESTRICTED_PATHS,
          patterns: [ECHARTS_RESTRICTED_PATTERN, SHARED_UI_API_RESTRICTED_PATTERN],
        },
      ],
    },
  },
  {
    // No raw colour outside the token layer (`FE-D5`), no raw @media
    // outside the layout layer (task 105), no VITE_*_URL plane-host env var
    // anywhere (`FE-D19`, task 027), no DEMO_STATES outside the fixture
    // layer (task 036), and no `getValues()` outside `designer/form`
    // (`FE-D17`, task 052). Combined in one `no-restricted-syntax` block: a
    // later block with the same rule would replace this wholesale (same
    // lesson as no-restricted-imports).
    // `shared/testdoubles/data` also carries raw hex — a package's OWN
    // brand colour (task 033's `SOLUTIONS` fixture), arbitrary business
    // data from a future backend, not a styling decision this rule polices
    // — and is exactly where a real DEMO_STATES fixture belongs too.
    files: ['src/**/*.{ts,tsx}'],
    ignores: [
      'src/shared/theme/**',
      'src/shared/ui/layout/**',
      'src/shared/testdoubles/data/**',
      'src/test/matchMedia.test.ts',
      // The logger is the one module allowed to call `console` — that is
      // its entire job (FE-102). Re-policed below with every OTHER rule,
      // so it is exempt from one restriction, not from the block.
      'src/shared/observability/**',
    ],
    rules: {
      'no-restricted-syntax': [
        'error',
        ...NO_RAW_COLOUR,
        ...NO_RAW_MEDIA,
        ...NO_VITE_PLANE_HOST,
        ...NO_DEMO_STATES,
        ...NO_GET_VALUES,
        ...NO_SECRET_LOG_FIELD,
        ...NO_RAW_CONSOLE,
      ],
    },
  },
  {
    // `shared/observability/**` calls `console` by design — it is the single
    // place allowed to (FE-102). Every other restriction still applies,
    // including NO_SECRET_LOG_FIELD: the logger must not name a secret field
    // in its own code either.
    files: [
      'src/shared/observability/**/*.{ts,tsx}',
      // The fixture mirror, so `lint:fixtures` sees these rules too — same
      // pattern the boundaries elements use for their own mirrors.
      'src/test/lint-fixtures/observability/**/*.{ts,tsx}',
    ],
    ignores: ['src/shared/observability/**/*.test.{ts,tsx}'],
    rules: {
      'no-restricted-syntax': [
        'error',
        ...NO_RAW_COLOUR,
        ...NO_RAW_MEDIA,
        ...NO_VITE_PLANE_HOST,
        ...NO_DEMO_STATES,
        ...NO_GET_VALUES,
        ...NO_SECRET_LOG_FIELD,
        ...NO_LOG_TRANSPORT,
      ],
    },
  },
  {
    // Layout primitives may emit @media/@container; they still must not
    // invent colours, a plane-host env var, or a demo-state selector.
    files: ['src/shared/ui/layout/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-syntax': [
        'error',
        ...NO_RAW_COLOUR,
        ...NO_VITE_PLANE_HOST,
        ...NO_DEMO_STATES,
        ...NO_SECRET_LOG_FIELD,
        ...NO_RAW_CONSOLE,
      ],
    },
  },
  {
    // `useDesignerDraft.ts`'s one `getValues()` call (module header) — every
    // other restriction from the block above still applies here.
    files: ['src/features/designer/form/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-syntax': [
        'error',
        ...NO_RAW_COLOUR,
        ...NO_RAW_MEDIA,
        ...NO_VITE_PLANE_HOST,
        ...NO_DEMO_STATES,
        ...NO_SECRET_LOG_FIELD,
        ...NO_RAW_CONSOLE,
      ],
    },
  },
  {
    // The matchMedia test-polyfill's own test (task 106) has to assert
    // against real `@media (min-width:…)` strings — the exact shape
    // `theme.breakpoints.up`/`.down` produce — to prove the polyfill
    // parses them correctly. Still must not invent colours, a plane-host
    // env var, or a demo-state selector.
    files: ['src/test/matchMedia.test.ts'],
    rules: {
      'no-restricted-syntax': [
        'error',
        ...NO_RAW_COLOUR,
        ...NO_VITE_PLANE_HOST,
        ...NO_DEMO_STATES,
        ...NO_SECRET_LOG_FIELD,
        ...NO_RAW_CONSOLE,
      ],
    },
  },
  {
    // contracts/** is shared with Java CI and imports nothing app-side —
    // no React, no MUI, nothing under src/ (task 046 acceptance). This
    // covers the external-package half of that rule; the local-import
    // half is also covered by the boundaries policies above (`contracts`
    // has no `allow` granting it access to app/feature/shared).
    files: ['contracts/**/*.ts', 'src/test/lint-fixtures/contracts/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: [
                'react',
                'react-dom',
                'react/*',
                '@mui/*',
                '@app/*',
                '@shared/*',
                '@features/*',
                '../src/*',
                '../src/**',
              ],
              message:
                'contracts/ is shared with the Java backend and imports nothing app-side (task 046 acceptance).',
            },
          ],
        },
      ],
    },
  },
  {
    // Node-context config files and e2e specs are not part of the
    // type-checked app project (tsconfig.json only includes "src"); lint
    // them with type-aware rules turned off rather than pulling them into
    // that program. (tsconfig.node.json still type-checks them for real —
    // see `npm run typecheck`.)
    files: [
      'eslint.config.js',
      'vite.config.ts',
      'vitest.config.ts',
      'playwright.config.ts',
      'e2e/**/*.ts',
      'scripts/**/*.mjs',
    ],
    extends: [tseslint.configs.disableTypeChecked],
    languageOptions: {
      globals: {
        ...globals.node,
        // FE-110's reflow checker authors `page.evaluate` callbacks in
        // these same `.ts` files — real code that runs in the browser,
        // not the Node test runner, so both global sets apply here.
        ...globals.browser,
      },
    },
  },
  {
    // Lint-fixtures exist to violate one guardrail each. When
    // `lint:fixtures` re-includes them via --no-ignore, drop type-aware
    // and unused-binding noise so the only errors are the intended ones.
    files: ['src/test/lint-fixtures/**/*.{ts,tsx}'],
    extends: [tseslint.configs.disableTypeChecked],
    rules: {
      'import/order': 'off',
      'no-unused-vars': 'off',
      '@typescript-eslint/no-unused-vars': 'off',
    },
  },
  eslintConfigPrettier,
);
