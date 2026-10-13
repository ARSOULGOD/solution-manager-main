/**
 * MSW handlers for the data-plane routes (task 033, design §13):
 * configuration-of-record, apply, health, per-instance inventory. Backs
 * `instancesRepository`, `configRepository`, `applyRepository`,
 * `actionsRepository`, `governanceRepository` — see each for what is
 * SPECIFIED vs INVENTED.
 */
import { HttpResponse, http } from 'msw';

import type { Instance } from '@shared/api/contracts/instances';
import type { HealthKey } from '@shared/vocab';


import { readDeclareInput } from '../data/declaredInstance';
import { ROLLOUT_REPLICAS } from '../data/designer';
import { nodeId } from '../data/hierarchy';
import { CACHE_INSTANCES, FILE_MESH_INSTANCES } from '../data/instances';
import { draftValuesFor, observedStateFor, packageForSolution } from '../data/packages';
import { buildWireDesigner } from '../data/wireViewModel';

const ALL_INSTANCES = [...CACHE_INSTANCES, ...FILE_MESH_INSTANCES] as const;

function findInstance(id: string) {
  return ALL_INSTANCES.find((i) => i.id === id);
}

/**
 * The real-contract-shaped view of a fixture row (integration plan 007,
 * 2026-10-09), for the `listInstances`/`getInstance`/`declareInstance`
 * handlers only — every OTHER handler in this file (designer, apply,
 * health, config-ladder, log-levels) keys off `findInstance`'s row
 * directly and is untouched; this is additive, not a fixture migration.
 * `environmentId` is `hierarchy.ts`'s own deterministic `nodeId(org,
 * subOrg, env)`, so `HierarchyRepository.environmentPath()` (which
 * traverses the real `/cp/hierarchy/*` handlers) resolves it back to the
 * same names a test asserts against.
 */
function toRealInstance(row: (typeof ALL_INSTANCES)[number]): Instance {
  return {
    stateVersion: 1,
    instanceId: row.id,
    name: row.name,
    solutionId: row.solutionId,
    environmentId: nodeId(row.org, row.subOrg, row.env),
    declaredPackageVersion: row.packageVersion,
    acquisition: { mode: 'direct-http' },
    placements: row.replicas.map((replica, index) => ({
      placementId: `${row.id}-placement-${String(index)}`,
      targetId: `${row.id}-target`,
      containerName: replica.name,
      probeStrength: 'readiness',
    })),
  };
}

// The inverse of `governance/healthRollup.ts`'s `HEALTH_STATE_VOCAB` —
// duplicated here rather than imported, because `shared/testdoubles` may
// not depend on a feature. `InstanceRow.healthRollup` is `HealthKey`
// (task 013's vocab); the wire response is the nine-value `HealthState`
// (`governanceRepository.ts`'s contract, `OR-39`) — this is the one seam
// that translates between them for the fixture.
const HEALTH_KEY_TO_STATE: Record<
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
// the real MSW-backed `setLogLevel()` + `getLogLevel()` pair together today,
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
// `41` is the baseline the designer GET and the history fixture both
// use, so the generation story stays internally consistent. Plan 008 Tasks
// 1/2: the real `Designer.generation`/`StagedConfig.generation` are `int64`,
// never the fixture-era string — `stateVersion` now advances alongside it,
// since the real `stageConfig` keys optimistic concurrency off
// `baseStateVersion`, not the generation.
const BASE_GENERATION = 41;
const BASE_STATE_VERSION = 1;
const configGenerations = new Map<string, number>();
const configStateVersions = new Map<string, number>();

function currentGeneration(instanceId: string): number {
  return configGenerations.get(instanceId) ?? BASE_GENERATION;
}

function currentStateVersion(instanceId: string): number {
  return configStateVersions.get(instanceId) ?? BASE_STATE_VERSION;
}

/**
 * Drops every advanced generation. `src/test/setup.ts` calls this after
 * each test: `server.resetHandlers()` restores *handlers*, not the module
 * state they close over, and a generation left advanced by one test would
 * make the next one's first save look like a conflict.
 */
export function resetConfigGenerations(): void {
  configGenerations.clear();
  configStateVersions.clear();
}

export const dpHandlers = [
  // ---- Instance inventory (integration plan 007, real console.yaml shapes) ----
  http.get('*/dp/solutions/:solutionId/instances', ({ params }) => {
    const items = ALL_INSTANCES.filter((i) => i.solutionId === params['solutionId']).map(
      toRealInstance,
    );
    return HttpResponse.json({ items });
  }),
  /**
   * Declare, real route (`POST /dp/environments/{environmentId}/instances`,
   * `OR-52`). STATELESS on purpose: it echoes the instance it would have
   * created and registers nothing. The default fixture set is what the
   * reference HTML shows, and a handler that accumulated rows would make
   * every suite's instance count depend on test order. The DEV handlers
   * (`app/dev/devPackageHandlers.ts`) are where a declaration persists for
   * a browser session.
   */
  http.post('*/dp/environments/:environmentId/instances', async ({ params, request }) => {
    let body: unknown;
    try {
      body = await request.json();
    } catch {
      body = undefined;
    }
    const input = readDeclareInput(body);
    if (input === undefined) {
      return HttpResponse.json({ code: 'VALIDATION_FAILED' }, { status: 422 });
    }
    if (CACHE_INSTANCES.some((i) => i.name.toLowerCase() === input.name.toLowerCase())) {
      return HttpResponse.json({ code: 'CONFLICT' }, { status: 409 });
    }
    const declared: Instance = {
      stateVersion: 1,
      instanceId: input.name,
      name: input.name,
      solutionId: input.solutionId,
      environmentId: String(params['environmentId']),
      declaredPackageVersion: input.declaredPackageVersion,
      acquisition: input.acquisition,
      placements: [
        {
          placementId: `${input.name}-placement-0`,
          ...(input.connectionId !== undefined
            ? { connectionId: input.connectionId }
            : { targetId: input.targetId }),
          containerName: input.placement.containerName,
          probeStrength: 'readiness',
        },
      ],
    };
    return HttpResponse.json(declared, { status: 201 });
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
    return HttpResponse.json(toRealInstance(instance));
  }),

  // ---- Designer (plan 008 Tasks 1/2: the real `getDesigner`/`stageConfig` wire shapes) ----
  // `buildWireDesigner` runs the SAME local `project()` engine every other fixture path already
  // uses, scoped per component instance (`OR-34`: a real backend does the same), so
  // `configRepository.loadDesigner()`'s reverse mapping has a REAL wire shape to round-trip
  // against, not a hand-authored shortcut.
  http.get('*/dp/instances/:instanceId/designer', ({ params }) => {
    const instanceId = String(params['instanceId']);
    const instance = findInstance(instanceId);
    if (instance === undefined) {
      return new HttpResponse(null, { status: 404 });
    }
    const pkg = packageForSolution(instance.solutionId);
    if (pkg === undefined) {
      return new HttpResponse(null, { status: 404 });
    }
    const wire = buildWireDesigner({
      pkg,
      packageVersion: instance.packageVersion,
      draftValues: draftValuesFor(instance),
      observed: observedStateFor(instance),
      componentTypes: instance.componentTypes,
      stateVersion: currentStateVersion(instanceId),
      generation: currentGeneration(instanceId),
    });
    return HttpResponse.json(wire);
  }),
  http.post('*/dp/instances/:instanceId/config', async ({ params, request }) => {
    const instanceId = String(params['instanceId']);
    let body: { baseStateVersion?: number } | undefined;
    try {
      body = (await request.json()) as { baseStateVersion?: number };
    } catch {
      body = undefined;
    }

    const current = currentStateVersion(instanceId);
    if (body?.baseStateVersion !== current) {
      // console.yaml `stageConfig` `409`: "a newer `stateVersion` exists. Refetch and re-stage."
      return HttpResponse.json({ code: 'CONFLICT' }, { status: 409 });
    }

    const nextStateVersion = current + 1;
    const nextGeneration = currentGeneration(instanceId) + 1;
    configStateVersions.set(instanceId, nextStateVersion);
    configGenerations.set(instanceId, nextGeneration);
    return HttpResponse.json({
      stateVersion: nextStateVersion,
      generation: nextGeneration,
      unchanged: false,
    });
  }),
  // Plan 008 Task 3: the real shape is console.yaml's `ConfigHistory`
  // envelope (`Versioned` + `entries[].{generation: int64, at, by,
  // configHash?}`), not the old bare array of `{generation: string,
  // savedAt, savedBy}`. `configRepository.history()` unwraps `entries`.
  http.get('*/dp/instances/:instanceId/config/history', () =>
    HttpResponse.json({
      stateVersion: 41,
      entries: [
        { generation: 41, at: '2026-08-19T10:00:00Z', by: 'Devon Ramirez' },
        { generation: 40, at: '2026-08-18T09:30:00Z', by: 'Mira Chen' },
      ],
    }),
  ),
  // `setConfigSecret`'s 200 is a `StagedConfig` (`stateVersion` + `generation`
  // only) — `I6` forbids echoing the value, and the ratified one-call shape
  // (console.yaml's own note, "Step 7 sitting 2026-09-30, batch 1, point 17")
  // carries no `ref` field at all; refetch the designer to see the field's
  // new `secretRef`.
  http.post('*/dp/instances/:instanceId/config/secrets/:fieldKey', () =>
    HttpResponse.json({ stateVersion: 42, generation: 42 }),
  ),
  // `acceptRuntimeConfig` (console.yaml): real body `{baseStateVersion, placementId}`, real
  // response `AcceptedRuntimeConfig` — never the old `{rows}` diff shape (no contract basis,
  // `AcceptRuntimeDialog`'s own header). Stateless: nothing here was actually read back from a
  // host, so `notAccepted` is always empty and `unchanged` always false — a fixture honestly
  // has nothing to report as unmappable.
  http.post('*/dp/instances/:instanceId/config/accept-runtime', async ({ request }) => {
    const body = (await request.json()) as { baseStateVersion?: number; placementId?: string };
    if (typeof body.baseStateVersion !== 'number' || typeof body.placementId !== 'string') {
      return HttpResponse.json({ code: 'VALIDATION_FAILED' }, { status: 422 });
    }
    return HttpResponse.json({
      stateVersion: body.baseStateVersion + 1,
      generation: 1,
      unchanged: false,
      placementId: body.placementId,
      fileBytesDifferFromRendered: false,
      truncated: false,
      notAccepted: [],
    });
  }),
  // `restoreRuntimeConfig` (console.yaml): real body `{placementId, strategy?}`, real response
  // `202 ApplyAccepted` — never the old bare `204`.
  http.post('*/dp/instances/:instanceId/config/restore-runtime', async ({ request }) => {
    const body = (await request.json()) as { placementId?: string };
    if (typeof body.placementId !== 'string') {
      return HttpResponse.json({ code: 'VALIDATION_FAILED' }, { status: 422 });
    }
    return HttpResponse.json({ applyId: `restore-${body.placementId}` }, { status: 202 });
  }),

  // ---- Apply — accepted request followed by the real observation envelope. ----
  http.post('*/dp/instances/:instanceId/apply', () =>
    HttpResponse.json({ stateVersion: 2, applyId: 'apply-fixture-1' }, { status: 202 }),
  ),
  http.get('*/dp/instances/:instanceId/apply/observe', () =>
    HttpResponse.json({
      items: [
        {
          stateVersion: 3,
          applyId: 'apply-fixture-1',
          operation: 'apply',
          placementId: 'placement-0',
          generation: 1,
          requestedAt: new Date().toISOString(),
          state: 'STAGED',
          terminal: true,
          steps: [],
        },
      ],
    }),
  ),
  http.post('*/dp/instances/:instanceId/apply/rollback', () =>
    HttpResponse.json({
      kind: 'INDETERMINATE',
      reason: 'Rollback outcome cannot be confirmed from the HTTP response alone.',
      observedAt: new Date().toISOString(),
      definite: false,
      replicas: ROLLOUT_REPLICAS,
    }),
  ),

  // ---- Actions ----
  http.post('*/dp/instances/:instanceId/actions/:actionKey', ({ params }) =>
    HttpResponse.json({ ok: true, message: `${String(params['actionKey'])} succeeded.` }),
  ),

  // ---- Governance (console.yaml tag `governance`; integration plan 010) ----
  // `Health` is placement-scoped with NO top-level rollup (`OR-39`/`OR-55`). The
  // fixture's `ReplicaRow[]` is reused as the placement list — `r.name` stands in
  // for `placementId` (no placement concept exists in the fixture data model yet).
  http.get('*/dp/instances/:instanceId/health', ({ params }) => {
    const instance = findInstance(String(params['instanceId']));
    const healthKey = instance?.healthRollup;
    const checkedAt = new Date().toISOString();
    // A 'notfound' instance has no declared health block to report either —
    // there's nothing more honest to return than the same fallback an
    // unrecognised id gets.
    if (healthKey === undefined || healthKey === 'notfound') {
      return HttpResponse.json({
        placements: [{ placementId: 'default', state: 'RED', checkedAt, stateVersion: 1 }],
      });
    }
    const replicas = instance?.replicas ?? [];
    if (replicas.length === 0) {
      return HttpResponse.json({
        placements: [
          { placementId: 'default', state: HEALTH_KEY_TO_STATE[healthKey], checkedAt, stateVersion: 1 },
        ],
      });
    }
    return HttpResponse.json({
      placements: replicas.map((r, index) => ({
        placementId: r.name,
        state: HEALTH_KEY_TO_STATE[r.health],
        checkedAt,
        stateVersion: index + 1,
      })),
    });
  }),
  // `ConfigLadder`: instance-wide `rungs[]`, top-level `reached`, and one `reached`
  // per live placement (`OR-37` — the same five rungs everywhere).
  http.get('*/dp/instances/:instanceId/config-ladder', ({ params }) => {
    const instance = findInstance(String(params['instanceId']));
    const placementIds = (instance?.replicas ?? []).map((r) => r.name);
    return HttpResponse.json({
      stateVersion: 1,
      rungs: [
        { rung: 'desired', present: true, generation: 41 },
        { rung: 'rendered', present: true, generation: 41 },
        { rung: 'persisted', present: true, generation: 41 },
        { rung: 'restart-observed', present: true, generation: 41 },
        { rung: 'effective-verified', present: false },
      ],
      reached: 'restart-observed',
      placements:
        placementIds.length > 0
          ? placementIds.map((placementId) => ({ placementId, reached: 'restart-observed' }))
          : [{ placementId: 'default', reached: 'restart-observed' }],
    });
  }),
  http.get('*/dp/instances/:instanceId/log-levels/:component', ({ params, request }) => {
    const instanceId = String(params['instanceId']);
    const component = String(params['component']);
    const placementId = new URL(request.url).searchParams.get('placementId') ?? 'default';
    const level = logLevelState.get(logLevelKey(instanceId, component)) ?? 'INFO';
    return HttpResponse.json({
      component,
      placementId,
      liveLevel: level,
      configuredLevel: level,
      runtimeOverride: false,
    });
  }),
  // `202 ApplyAccepted` (`{ stateVersion, applyId }`) — a level change is an
  // ordinary configuration apply (`OR-103`), never a bare `204`.
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
    return HttpResponse.json(
      { stateVersion: 42, applyId: `apply-${String(Date.now())}` },
      { status: 202 },
    );
  }),
  // `LogPage`: `{ lines: [{ at, level, message }] }`, `availability` absent means
  // fully available. `after` is honoured so `LogViewer`'s poll never re-shows a line.
  http.get('*/dp/instances/:instanceId/logs/:component', ({ request }) => {
    const after = new URL(request.url).searchParams.get('after');
    const all = [
      { at: '2026-10-08T10:00:00.000Z', level: 'INFO', message: 'Started successfully.' },
      { at: '2026-10-08T10:00:01.000Z', level: 'WARN', message: 'Slow response from broker.' },
    ];
    const lines = after === null ? all : all.filter((line) => line.at > after);
    return HttpResponse.json({ lines });
  }),
];
