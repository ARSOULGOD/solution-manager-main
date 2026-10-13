/**
 * Test-only config, kept separate from vite.config.ts (task 008, design
 * §19). A dedicated vitest.config.ts makes Vitest ignore vite.config.ts's
 * own config entirely, so this file `mergeConfig`s it back in rather than
 * duplicating `plugins`/`resolve.alias`.
 *
 * Three projects:
 * - `unit` — jsdom, MSW node server + jest-dom + jest-axe wired in
 *   src/test/setup.ts. Covers pure engines, components and routes (design
 *   §19's first three test layers). Excludes `*.a11y.test.tsx` — those run
 *   under the `a11y` project instead, so `npm test` (which runs every
 *   project) executes each file exactly once rather than twice.
 * - `a11y` (task 098) — same environment/setup as `unit`, narrowed to
 *   `*.a11y.test.tsx`. Exists so those files are collectively nameable and
 *   independently runnable (`npm run test:a11y`) instead of only ever
 *   running mixed into the full `unit` project — see `scripts/test-a11y.mjs`
 *   for why that's a wrapper script rather than a raw `vitest` CLI line.
 * - `contracts` — node, runs the JSON fixture suite shared with the Java
 *   backend (`UI-D15`/`DM-D20`, `FE-D12`, tasks 046/071) plus the plain
 *   unit tests for `contracts/` modules that have no Java counterpart yet
 *   (task 070's `buildQuery`/`canonicalQueryHash`). No
 *   `passWithNoTests` — that option doesn't exist per-project in Vitest 4's
 *   `projects` model (root-only) — but `npm test` still exits 0 today with
 *   this project matching zero files, because the aggregate run only fails
 *   if *every* project is empty. Verified empirically; re-check if that
 *   changes in a future Vitest major.
 */
import { defineConfig, mergeConfig } from 'vitest/config';

import viteConfig from './vite.config.ts';

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      // ── Worker pool (issue #131) ──────────────────────────────────────
      //
      // Vitest defaults `maxWorkers` to the CPU count, which OVERSUBSCRIBES
      // this suite: a jsdom worker running an axe scan is not one core's
      // worth of work — it is that plus V8 GC threads and jsdom parsing — so
      // ten workers on ten cores thrash rather than saturate. Measured, both
      // runs `--reporter=verbose` on the same tree (2026-09-03, 10 cores):
      //
      //   | test                        | 10 workers | 5 workers |
      //   |-----------------------------|-----------:|----------:|
      //   | InstancesPage grid light    |    14.96 s |    7.74 s |
      //   | InstancesPage card light    |    15.29 s |    8.23 s |
      //   | InstancesPage card dark     |    12.97 s |    6.76 s |
      //   | Gallery assembled dark      |    48.64 s |   25.41 s |
      //   | **whole suite, wall clock** | **299.19s**| **299.26s**|
      //
      // Per-test latency roughly halves and the suite takes exactly as long,
      // because the total work is unchanged and the pool was simply fighting
      // itself. That is what makes this the fix rather than another timeout
      // raise: it attacks the contention that inflates every budget, and it
      // costs nothing.
      //
      // A percentage, not a literal, so a 4-core CI runner gets 2 workers
      // rather than the 5 that suit this machine — the ratio is what matters.
      maxWorkers: '50%',
      projects: [
        {
          extends: true,
          test: {
            name: 'unit',
            environment: 'jsdom',
            globals: false,
            css: true,
            setupFiles: ['./src/test/setup.ts'],
            include: ['src/**/*.test.{ts,tsx}'],
            exclude: ['src/**/*.a11y.test.tsx'],
            // A single `axe()` pass over a rendered MUI tree costs ~0.3 s
            // idle, but 10-20x that when the worker pool is saturated —
            // enough to blow Vitest's 5 s default. That failure is not
            // benign: a timed-out case leaves axe mid-run and every later
            // case in the same file dies with "Axe is already running", so
            // one slow test took out ~20. Raised well clear of the loaded
            // worst case while still short enough to catch a real hang.
            testTimeout: 20_000,
          },
        },
        {
          extends: true,
          test: {
            name: 'a11y',
            environment: 'jsdom',
            globals: false,
            css: true,
            setupFiles: ['./src/test/setup.ts'],
            include: ['src/**/*.a11y.test.tsx'],
            // Same reasoning as `unit`'s own `testTimeout` above — every
            // file here runs axe at least once.
            testTimeout: 20_000,
          },
        },
        {
          extends: true,
          test: {
            name: 'contracts',
            environment: 'node',
            globals: false,
            include: [
              'contracts/fixtures/**/*.test.ts',
              'contracts/metrics/**/*.test.ts',
              'contracts/packages/**/*.test.ts',
              'contracts/solution-package/**/*.test.ts',
              'contracts/view-model/**/*.test.ts',
            ],
          },
        },
      ],
    },
  }),
);
