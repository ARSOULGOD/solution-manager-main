/**
 * Integration plan 002, Task 1 — contract-conformance audit.
 *
 * `contracts/openapi/console.yaml` is the normative wire contract. This test statically scans every
 * `cpClient` / `dpClient` call in `src/` (test files and test doubles excluded) and classifies each
 * (method, path) as:
 *
 * - in the contract, or
 * - one of three explicitly-allowed, individually-justified exceptions below.
 *
 * Anything else is **drift** and fails the build. The exception tables are a ratchet in both
 * directions: a *new* unlisted call fails, and a listed exception that no longer matches a real
 * call (because someone fixed it) fails too, so the tables cannot go stale.
 *
 * No behaviour is checked here, only that the paths the frontend asks for exist in the contract it
 * is supposed to follow. Shape conformance (request/response bodies) is a per-repository concern
 * handled as each operation is wired (plan 002, Tasks 2-7).
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

// Vitest's root is `frontend/` (see vitest.config.ts); `import.meta.url` is not a file: URL under jsdom.
const SRC = resolve(process.cwd(), 'src');
const CONTRACT = resolve(process.cwd(), '../contracts/openapi/console.yaml');

type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
interface Endpoint {
  method: Method;
  path: string;
}
interface Call extends Endpoint {
  file: string;
}

const key = (e: Endpoint): string => `${e.method} ${e.path}`;

// --- the contract ----------------------------------------------------------

/**
 * Minimal reader for `console.yaml`'s `paths:` block — no YAML dependency on purpose (none is a
 * direct dependency of the frontend). The file is machine-generated with fixed 2/4/6-space
 * indentation and is validated by `contracts/openapi/validate-openapi.py`, so this stays honest;
 * the `expect(...).toBeGreaterThan` guard below fails loudly if the layout ever changes.
 */
function readContract(): Array<Endpoint & { operationId: string }> {
  const out: Array<Endpoint & { operationId: string }> = [];
  let path: string | undefined;
  let method: Method | undefined;
  for (const line of readFileSync(CONTRACT, 'utf8').split('\n')) {
    // Any top-level path line resets `path`/`method` — not just `/cp`/`/dp` ones. Without this,
    // `path` stays sticky past a `/console/v1/...` block (monitoring, no `/cp`/`/dp` prefix) and
    // every operationId after it gets misattributed to the last real `/cp`/`/dp` path (found
    // 2026-10-08: `setDashboardLayout`/`setWidgetHidden` were landing on
    // `/dp/instances/{}/log-levels/{}` because that was the last path line before `console.yaml`'s
    // tail-end `/console/v1/*` section). `/console/v1/*` operations are still excluded from
    // `contract` below — they just no longer corrupt the path tracked for real `/cp`/`/dp` ones.
    const anyPath = /^ {2}(\/\S*):\s*$/.exec(line);
    if (anyPath) {
      path = undefined;
      method = undefined;
    }
    const p = /^ {2}(\/(?:cp|dp)\/\S*):\s*$/.exec(line);
    if (p) {
      path = p[1];
      method = undefined;
      continue;
    }
    if (anyPath) continue;
    const m = /^ {4}(get|post|put|patch|delete):\s*$/.exec(line);
    if (m && path) {
      method = (m[1] ?? '').toUpperCase() as Method;
      continue;
    }
    const o = /^ {6}operationId:\s*(\w+)/.exec(line);
    if (o && path && method) {
      out.push({ method, path: path.replace(/\{[^}]+\}/g, '{}'), operationId: o[1] ?? '' });
    }
  }
  return out;
}

// --- the frontend's calls --------------------------------------------------

const BASE = { cpClient: '/cp', dpClient: '/dp' } as const;
const VERB: Record<string, Method> = {
  get: 'GET',
  post: 'POST',
  put: 'PUT',
  patch: 'PATCH',
  delete: 'DELETE',
  postWithCorrelation: 'POST',
  patchWithCorrelation: 'PATCH',
};
const CALL =
  /\b(cpClient|dpClient)\s*\.\s*(get|post|put|patch|delete|postWithCorrelation|patchWithCorrelation)\s*(?:<(?:[^<>]|<[^<>]*>)*>)?\(\s*([`'"])([\s\S]*?)\3/g;

/** `/x/${id}/y${query}` → `/x/{}/y`. A `${…}` after a non-slash is a query suffix, not a segment. */
function normalise(raw: string): string {
  const noQuery = raw.split('?')[0] ?? raw;
  const cut = noQuery.search(/[^/]\$\{/);
  const path = cut === -1 ? noQuery : noQuery.slice(0, cut + 1);
  return path.replace(/\$\{[^}]*\}/g, '{}');
}

function walk(dir: string, acc: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      if (name === 'testdoubles' || name === 'node_modules') continue;
      walk(full, acc);
    } else if (/\.tsx?$/.test(name) && !/\.(test|stories)\.tsx?$/.test(name)) {
      acc.push(full);
    }
  }
  return acc;
}

function readCalls(): Call[] {
  const calls: Call[] = [];
  for (const file of walk(SRC)) {
    const text = readFileSync(file, 'utf8');
    for (const m of text.matchAll(CALL)) {
      const client = m[1] as keyof typeof BASE;
      const method = VERB[m[2] ?? ''];
      if (!method) continue;
      calls.push({ method, path: BASE[client] + normalise(m[4] ?? ''), file: relative(SRC, file) });
    }
  }
  return calls;
}

// --- the allowed exceptions ------------------------------------------------

/** A path prefix `console.yaml` has no operation for. These screens stay fixture-backed. */
const NO_CONTRACT_OPERATION: ReadonlyArray<{ prefix: string; why: string }> = [
  { prefix: '/cp/admin/', why: 'Admin/RBAC screens: console.yaml defines no admin operations (only the 38-verb catalog, no CRUD routes).' },
];

/**
 * Calls whose path the contract *does* define differently — real drift, recorded not fixed (Task 1
 * changes no behaviour). Each names the contract operation it should become and where it is fixed.
 * Remove the row when the call is corrected; the test fails if a row goes stale.
 */
const KNOWN_DRIFT: ReadonlyArray<Endpoint & { shouldBe: string; fixedIn: string }> = [
  // GET /cp/solutions/{}/package (D-4) resolved 2026-10-09: console.yaml now declares
  // `getSolutionPackage` at this exact path, and `solutionsRepository.ts`'s `getPackage()` was
  // fixed to unwrap the real `SolutionPackageDocument` envelope. Row removed.
  {
    method: 'POST',
    path: '/dp/instances/{}/actions/{}',
    shouldBe: 'no contract operation: package-declared instance actions are not in console.yaml',
    fixedIn: 'plan 002 Task 5 (needs a contract ruling)',
  },
];

/**
 * Contract operations the frontend never calls. Remove the row when a caller appears.
 *
 * Updated 2026-10-08 for integration plans 004/005/008/010/011: the contract grew from 32 to 64
 * operations (backend plans 012/013 — target registration, fleet schedules, hierarchy writes,
 * entitlements). Those 19 new `/cp`+`/dp` operations have no frontend caller yet because the
 * plans that build them (006/007/009/012) haven't executed. This is not drift — it's the
 * documented, plan-tracked state of a staggered rollout.
 */
const UNCALLED_CONTRACT_OPERATIONS: ReadonlyArray<{ operationId: string; why: string }> = [
  { operationId: 'stopInstance', why: 'no stop UI yet (OR-9/OR-18, plan 009 Task 3)' },
  { operationId: 'submitFleetApply', why: 'no fleet-apply UI yet (OR-47, plan 009 Task 4)' },
  { operationId: 'observeFleetApply', why: 'no fleet-apply UI yet (OR-47, plan 009 Task 4)' },
  { operationId: 'registerTarget', why: 'target registration has no UI yet (plan 006)' },
  { operationId: 'scanHostKeys', why: 'target registration has no UI yet (plan 006)' },
  { operationId: 'getTargetConnection', why: 'target registration has no UI yet (plan 006)' },
  { operationId: 'editTargetConnection', why: 'target registration has no UI yet (plan 006)' },
  { operationId: 'rotateTargetCredential', why: 'target registration has no UI yet (plan 006)' },
  { operationId: 'testTargetConnection', why: 'target registration has no UI yet (plan 006)' },
  { operationId: 'retireTarget', why: 'target registration has no UI yet (plan 006)' },
  { operationId: 'addPlacement', why: 'placement add/retire has no UI yet (plan 007 Task 3)' },
  { operationId: 'retirePlacement', why: 'placement add/retire has no UI yet (plan 007 Task 3)' },
  { operationId: 'listApplySchedules', why: 'scheduled-apply UI has no UI yet (plan 009 Task 2)' },
  { operationId: 'cancelApplySchedule', why: 'scheduled-apply UI has no UI yet (plan 009 Task 2)' },
  { operationId: 'createSubOrganization', why: 'hierarchy-write UI has no UI yet (plan 012)' },
  { operationId: 'renameOrganization', why: 'hierarchy-write UI has no UI yet (plan 012)' },
  { operationId: 'renameSubOrganization', why: 'hierarchy-write UI has no UI yet (plan 012)' },
  { operationId: 'createEnvironment', why: 'hierarchy-write UI has no UI yet (plan 012)' },
  { operationId: 'renameEnvironment', why: 'hierarchy-write UI has no UI yet (plan 012)' },
  { operationId: 'listEntitlements', why: 'entitlements UI has no UI yet (plan 012)' },
  { operationId: 'setEntitlement', why: 'entitlements UI has no UI yet (plan 012)' },
  // setDashboardLayout/setWidgetHidden removed 2026-10-08: they were never legitimately
  // "uncalled" — a parser bug (fixed above) misattributed them to a stale /dp path. Plan 011
  // wired both for real; the contract reader correctly excludes /console/v1/* now.
];

/** The Dynamic Metrics surface has no OpenAPI contract in-tree at all; it is not `console.yaml`'s. */
const SEPARATE_SURFACE_NOTE =
  'consoleClient (`/console/v1/*`, Dynamic Metrics) is excluded from this scan: it has no OpenAPI document in contracts/.';

// --- the audit -------------------------------------------------------------

describe('frontend ↔ console.yaml conformance', () => {
  const contract = readContract();
  const contractKeys = new Set(contract.map(key));
  const calls = readCalls();

  it('reads a plausible contract and a plausible set of calls (guards the parsers)', () => {
    expect(contract.length).toBeGreaterThan(25);
    expect(calls.length).toBeGreaterThan(40);
    expect(SEPARATE_SURFACE_NOTE).toContain('consoleClient');
  });

  const inContract = (c: Call): boolean => contractKeys.has(key(c));
  const noContractOperation = (c: Call): boolean => NO_CONTRACT_OPERATION.some((x) => c.path.startsWith(x.prefix));
  const knownDrift = (c: Call): boolean => KNOWN_DRIFT.some((d) => key(d) === key(c));

  it('every call is in the contract or an explicitly allowed exception (no new drift)', () => {
    const drift = calls
      .filter((c) => !inContract(c) && !noContractOperation(c) && !knownDrift(c))
      .map((c) => `${key(c)}   (${c.file})`);
    expect([...new Set(drift)]).toEqual([]);
  });

  it('every KNOWN_DRIFT row still matches a real call and is still absent from the contract (no stale rows)', () => {
    for (const d of KNOWN_DRIFT) {
      expect(contractKeys.has(key(d)), `${key(d)} is now in the contract — remove it from KNOWN_DRIFT`).toBe(false);
      expect(calls.some((c) => key(c) === key(d)), `${key(d)} is no longer called — remove it from KNOWN_DRIFT`).toBe(true);
    }
  });

  it('every NO_CONTRACT_OPERATION prefix still matches a real call and no contract route (no stale rows)', () => {
    for (const x of NO_CONTRACT_OPERATION) {
      expect(calls.some((c) => c.path.startsWith(x.prefix)), `${x.prefix} is no longer called`).toBe(true);
      expect(
        contract.some((e) => e.path.startsWith(x.prefix)),
        `${x.prefix} is now in the contract — remove it from NO_CONTRACT_OPERATION`,
      ).toBe(false);
    }
  });

  it('every contract operation is called by the frontend or listed as uncalled (and no listed one is called)', () => {
    const calledKeys = new Set(calls.map(key));
    const uncalled = contract.filter((e) => !calledKeys.has(key(e))).map((e) => e.operationId).sort();
    expect(uncalled).toEqual(UNCALLED_CONTRACT_OPERATIONS.map((u) => u.operationId).sort());
  });
});
