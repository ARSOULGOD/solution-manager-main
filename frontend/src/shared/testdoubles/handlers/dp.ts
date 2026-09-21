/**
 * MSW handlers for the data-plane routes (task 033, design §13):
 * configuration-of-record, apply, health, per-instance inventory. Backs
 * `instancesRepository`, `configRepository`, `applyRepository`,
 * `actionsRepository`, `governanceRepository` — see each for what is
 * SPECIFIED vs INVENTED.
 */
import { HttpResponse, http } from 'msw';

import type { HealthKey } from '@shared/vocab';

import { buildDeclaredInstance, readDeclareInput } from '../data/declaredInstance';
import { DIFF_DRAFT, ROLLOUT_REPLICAS } from '../data/designer';
import { CACHE_INSTANCES } from '../data/instances';
import { draftValuesFor, observedStateFor, packageForSolution } from '../data/packages';

function findInstance(id: string) {
  return CACHE_INSTANCES.find((i) => i.id === id);
}

// The inverse of `governance/healthRollup.ts`'s `ROLLUP_TO_HEALTH_KEY` —
// duplicated here rather than imported, because `shared/testdoubles` may
// not depend on a feature. `InstanceRow.healthRollup` is `HealthKey`
// (task 013's vocab); the wire response is `HealthRollupState`
// (`governanceRepository.ts`'s contract) — this is the one seam that
// translates between them for the fixture.
const HEALTH_KEY_TO_ROLLUP: Record<
  HealthKey,
  'GREEN' | 'RED' | 'RESTART_EXPECTED' | 'INACTIVE_REPLICA' | 'NOT_APPLICABLE'
> = {
  healthy: 'GREEN',
  not_responding: 'RED',
  restart_expected: 'RESTART_EXPECTED',
  inactive_replica: 'INACTIVE_REPLICA',
  not_applicable: 'NOT_APPLICABLE',
};

// In-memory only, per `instanceId:component` — unlike the declare/undeclare
// handlers above (deliberately STATELESS so `CACHE_INSTANCES`'s count never
// depends on test order), this one is safe to hold state: no test exercises
// the real MSW-backed `setLogLevel()` + `logLevels()` pair together today,
// and a POST here should be visible on the next GET or the DEBUG-raise
// flow's "DEBUG · audited" chip (task 066) never lights up against the
// fixture, only in a stubbed unit test.
type LogLevelValue = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
const logLevelState = new Map<string, LogLevelValue>();

function logLevelKey(instanceId: string, component: string): string {
  return `${instanceId}:${component}`;
}

// ---- Config generation, and the `409` that falls out of it -------------
//
// The save route used to accept anything and always succeed, which made a
// concurrent save (`FE-D18` clause A, `FE-099` journey 2) impossible to
// reach: there was no state for a second actor to move. It now models what
// optimistic concurrency actually is — the client sends the generation it
// loaded, and a save whose generation is no longer current is rejected.
//
// That is deliberately NOT a flag or a dedicated "conflicting instance".
// A conflict is not a property of a record; it is what happens when two
// actors interleave. Modelled this way, journey 2 reproduces it honestly:
// load the Designer, have a second actor POST a save, then save from the
// UI with the now-stale generation.
//
// 'gen-41' is the baseline the designer GET and the history fixture both
// use, so the generation story stays internally consistent.
const BASE_GENERATION = 'gen-41';
const configGenerations = new Map<string, string>();

function currentGeneration(instanceId: string): string {
  return configGenerations.get(instanceId) ?? BASE_GENERATION;
}

/**
 * Drops every advanced generation. `src/test/setup.ts` calls this after
 * each test: `server.resetHandlers()` restores *handlers*, not the module
 * state they close over, and a generation left advanced by one test would
 * make the next one's first save look like a conflict.
 */
export function resetConfigGenerations(): void {
  configGenerations.clear();
}

export const dpHandlers = [
  // ---- Instance inventory ----
  http.get('*/dp/solutions/:solutionId/instances', ({ params }) => {
    const items = CACHE_INSTANCES.filter((i) => i.solutionId === params['solutionId']);
    return HttpResponse.json({ items, total: items.length });
  }),
  /**
   * Declare (task 115). CONTRACT: TBD — §16 Q10 defines no declare API and
   * no field set; the route and body are placeholders (see
   * `@shared/api/contracts/instances`).
   *
   * STATELESS on purpose: it echoes the row it would have created and
   * registers nothing. The default fixture set is what the reference HTML
   * shows, and a handler that accumulated rows would make every suite's
   * `CACHE_INSTANCES` count depend on test order. The DEV handlers
   * (`app/dev/devPackageHandlers.ts`) are where a declaration persists for
   * a browser session.
   */
  http.post('*/dp/solutions/:solutionId/instances', async ({ params, request }) => {
    let body: unknown;
    try {
      body = await request.json();
    } catch {
      body = undefined;
    }
    const input = readDeclareInput(body);
    if (input === undefined) {
      return HttpResponse.json({ code: 'invalid' }, { status: 400 });
    }
    if (CACHE_INSTANCES.some((i) => i.name.toLowerCase() === input.name.toLowerCase())) {
      return HttpResponse.json({ code: 'duplicate' }, { status: 409 });
    }
    return HttpResponse.json(buildDeclaredInstance(String(params['solutionId']), input), {
      status: 201,
    });
  }),
  /**
   * Undeclare (task 117). STATELESS, for the same reason the declare POST
   * above is: the default fixture set is what the reference HTML shows,
   * and a handler that actually removed rows would make every suite's
   * `CACHE_INSTANCES` count depend on test order. 404 for an id no fixture
   * carries, so the not-found path is exercisable.
   */
  http.delete('*/dp/instances/:instanceId', ({ params }) => {
    const instance = findInstance(String(params['instanceId']));
    if (instance === undefined) {
      return new HttpResponse(null, { status: 404 });
    }
    return new HttpResponse(null, { status: 204 });
  }),
  http.get('*/dp/instances/:instanceId', ({ params }) => {
    const instance = findInstance(String(params['instanceId']));
    if (instance === undefined) {
      return new HttpResponse(null, { status: 404 });
    }
    return HttpResponse.json(instance);
  }),

  // ---- Designer (Q19 resolved, FE-D33 — the browser projects; CONTRACT: TBD on Q8/Q9) ----
  http.get('*/dp/instances/:instanceId/designer', ({ params }) => {
    const instance = findInstance(String(params['instanceId']));
    if (instance === undefined) {
      return new HttpResponse(null, { status: 404 });
    }
    const pkg = packageForSolution(instance.solutionId);
    if (pkg === undefined) {
      return new HttpResponse(null, { status: 404 });
    }
    // 'gen-41' matches the config/history top entry and ROLLOUT_REPLICAS'
    // current generation below, so the fixture's generation story is
    // internally consistent across load, save-conflict, and rollout.
    return HttpResponse.json({
      package: pkg,
      packageVersion: instance.packageVersion,
      values: draftValuesFor(instance),
      observed: observedStateFor(instance),
      configGeneration: currentGeneration(String(params['instanceId'])),
    });
  }),
  http.post('*/dp/instances/:instanceId/config', async ({ params, request }) => {
    const instanceId = String(params['instanceId']);
    let submitted: string | undefined;
    try {
      submitted = ((await request.json()) as { generation?: string }).generation;
    } catch {
      submitted = undefined;
    }

    const current = currentGeneration(instanceId);
    if (submitted !== undefined && submitted !== current) {
      // Q9 leaves the `409` body shape TBD — no actor, no timestamp — so
      // this sends only what the contract can honestly carry today.
      // `ConflictDialog` renders the diff it is given; it does not read
      // this body (see its own header).
      return HttpResponse.json({ code: 'generation_conflict', current }, { status: 409 });
    }

    const next = `gen-${String(Date.now())}`;
    configGenerations.set(instanceId, next);
    return HttpResponse.json({ generation: next });
  }),
  http.get('*/dp/instances/:instanceId/config/history', () =>
    HttpResponse.json([
      { generation: 'gen-41', savedAt: '2026-08-19T10:00:00Z', savedBy: 'Devon Ramirez' },
      { generation: 'gen-40', savedAt: '2026-08-18T09:30:00Z', savedBy: 'Mira Chen' },
    ]),
  ),
  http.post('*/dp/instances/:instanceId/config/secrets/:fieldKey', ({ params }) =>
    HttpResponse.json({
      ref: `secret-ref://${String(params['instanceId'])}/${String(params['fieldKey'])}`,
    }),
  ),
  http.post('*/dp/instances/:instanceId/config/accept-runtime', () =>
    HttpResponse.json({ rows: DIFF_DRAFT }),
  ),
  http.post(
    '*/dp/instances/:instanceId/config/restore-runtime',
    () => new HttpResponse(null, { status: 204 }),
  ),

  // ---- Apply — BLOCKED (open item 1). Always INDETERMINATE, never a success verdict. ----
  http.post('*/dp/instances/:instanceId/apply', () =>
    HttpResponse.json({
      kind: 'INDETERMINATE',
      reason: 'No generation ordering or idempotency key exists for dp-remote (open item 1).',
      observedAt: new Date().toISOString(),
      replicas: ROLLOUT_REPLICAS,
    }),
  ),
  http.get('*/dp/instances/:instanceId/apply/observe', () => HttpResponse.json(ROLLOUT_REPLICAS)),
  http.post('*/dp/instances/:instanceId/apply/rollback', () =>
    HttpResponse.json({
      kind: 'INDETERMINATE',
      reason: 'Rollback outcome cannot be confirmed from the HTTP response alone.',
      observedAt: new Date().toISOString(),
      replicas: ROLLOUT_REPLICAS,
    }),
  ),

  // ---- Actions ----
  http.post('*/dp/instances/:instanceId/actions/:actionKey', ({ params }) =>
    HttpResponse.json({ ok: true, message: `${String(params['actionKey'])} succeeded.` }),
  ),

  // ---- Governance (CONTRACT: TBD — §16 Q13, the whole log seam) ----
  http.get('*/dp/instances/:instanceId/health', ({ params }) => {
    const instance = findInstance(String(params['instanceId']));
    const healthKey = instance?.healthRollup;
    // A 'notfound' instance has no declared health block to report either —
    // there's nothing more honest to return than the same fallback an
    // unrecognised id gets.
    const rollup =
      healthKey !== undefined && healthKey !== 'notfound' ? HEALTH_KEY_TO_ROLLUP[healthKey] : 'RED';
    return HttpResponse.json({
      rollup,
      availability: rollup === 'NOT_APPLICABLE' ? 'not-declared' : 'declared',
      replicas: (instance?.replicas ?? []).map((r) => ({ id: r.name, health: r.health })),
    });
  }),
  http.get('*/dp/instances/:instanceId/config-ladder', () =>
    HttpResponse.json({
      steps: [
        { key: 'desired', label: 'Desired', reached: true },
        { key: 'rendered', label: 'Rendered', reached: true },
        { key: 'persisted', label: 'Persisted', reached: true },
        { key: 'restart-observed', label: 'Restart-observed', reached: true },
        { key: 'effective-verified', label: 'Effective-verified', reached: false },
      ],
    }),
  ),
  http.get('*/dp/instances/:instanceId/log-levels/:component', ({ params }) => {
    const instanceId = String(params['instanceId']);
    const component = String(params['component']);
    const level = logLevelState.get(logLevelKey(instanceId, component)) ?? 'INFO';
    return HttpResponse.json({ component, level, supported: true });
  }),
  http.post('*/dp/instances/:instanceId/log-levels/:component', async ({ params, request }) => {
    const instanceId = String(params['instanceId']);
    const component = String(params['component']);
    let body: unknown;
    try {
      body = await request.json();
    } catch {
      body = undefined;
    }
    const level =
      body !== null && typeof body === 'object' && 'level' in body
        ? (body as { level: LogLevelValue }).level
        : undefined;
    if (level !== undefined) {
      logLevelState.set(logLevelKey(instanceId, component), level);
    }
    return new HttpResponse(null, { status: 204 });
  }),
  http.get('*/dp/instances/:instanceId/logs/:component', () =>
    HttpResponse.json({
      lines: [
        { ts: new Date().toISOString(), level: 'INFO', message: 'Started successfully.' },
        { ts: new Date().toISOString(), level: 'WARN', message: 'Slow response from broker.' },
      ],
      nextBefore: null,
      hasOlder: false,
    }),
  ),
];
