/**
 * DEV-only: makes Solution Packages loaded at runtime visible in a running
 * console, so `REQ-001`'s acceptance gate — "onboard a solution from a
 * package file alone, with zero commits to SM" — can be seen rather than
 * only asserted.
 *
 * The package comes from the operator, through the Solutions catalog's
 * load dialog, and lives in `devPackageStore.ts` (task FE-114) for the
 * session. The handlers below read the store on EVERY request, not at
 * module load, so a package loaded after boot appears without a reload.
 *
 * Nothing here is a hand-written fixture. The Designer route below serves
 * the raw package, the instance's version and its observed state — the
 * BROWSER runs `project()` over them (`FE-D33`, task 122), never this
 * handler. Change a field in the package file, re-drop it, and the
 * Designer changes with it — nothing in this file names a field.
 *
 * A dropped package has no fixture author, so its catalog card and its
 * declared instance are DERIVED rather than authored
 * (`derivePackageCatalog.ts`).
 *
 * **Production safety.** `main.tsx` imports this inside a literal
 * `if (import.meta.env.DEV)` branch, the same rule `gallery.routes.ts`
 * documents (`FE-D24`) — Vite folds that check to `false` in a production
 * build and drops the module graph below it, `msw` included.
 */
import { HttpResponse, http } from 'msw';

import type { SolutionPackage } from '@contracts/view-model/types';
import {
  CACHE_INSTANCES,
  FILE_MESH_INSTANCES,
  type InstanceRow,
} from '@shared/testdoubles/data/instances';
import {
  connectionSelectorValues,
  observedStateFor,
  packageForSolution,
} from '@shared/testdoubles/data/packages';
import {
  SOLUTIONS,
  withInstanceCounts,
  type SolutionPackageSummary,
} from '@shared/testdoubles/data/solutions';
import { buildWireDesigner } from '@shared/testdoubles/data/wireViewModel';
import { getConnectionRows } from '@shared/testdoubles/handlers/cp';

import { devDeclaredInstances } from './devDeclaredInstances';
import { devPackageStore } from './devPackageStore';
import type { LoadedPackage } from './parsePackage';

/** Defaults + whatever the operator loaded, at request time. */
function allSolutions(): readonly SolutionPackageSummary[] {
  return [...SOLUTIONS, ...devPackageStore.summaries()];
}

/**
 * `project()` takes a `SolutionPackage`, whose `provenance.signature` is
 * mandatory — an unsigned dropped package (`LoadedPackage`) has none. It
 * reads `components` and nothing else, so the projection input is rebuilt
 * with the field filled rather than asserting a type the file does not
 * satisfy.
 */
function projectable(pkg: LoadedPackage): SolutionPackage {
  return {
    ...pkg,
    provenance: { author: pkg.provenance.author, signature: pkg.provenance.signature ?? '' },
  };
}

// Same mapping `shared/testdoubles/handlers/cp.ts`'s `healthWireKeyOf`
// applies for the default (non-dev) fixture set — duplicated rather than
// imported, since `app/dev` already depends on `shared/testdoubles` one
// way and a cross-import back would make the two fixture layers couple.
function devHealthWireKeyOf(instance: InstanceRow): string {
  if (instance.healthRollup === 'notfound') {
    return instance.notFoundReason ?? 'missing';
  }
  switch (instance.healthRollup) {
    case 'healthy':
      return 'GREEN';
    case 'not_responding':
      return 'RED';
    case 'restart_expected':
      return 'RESTART_EXPECTED';
    case 'inactive_replica':
      return 'INACTIVE_REPLICA';
    case 'not_applicable':
    default:
      return 'NOT_APPLICABLE';
  }
}

function allInstances(): readonly InstanceRow[] {
  // Undeclared ids are filtered from the MERGED list, so a fixture row and
  // a session declaration are removable on the same terms (task 117).
  const removed = devDeclaredInstances.removedIds();
  return [
    ...CACHE_INSTANCES,
    ...FILE_MESH_INSTANCES,
    ...devPackageStore.instances(),
    ...devDeclaredInstances.list(),
  ].filter((instance) => !removed.has(instance.id));
}

/**
 * Prepended to the default fixture handlers, so these win for the routes
 * they name. The `msw/node` unit suite never loads this module, which is
 * what keeps the default fixture set exactly as the reference HTML shows
 * it.
 */
export const devPackageHandlers = [
  // Mapped to the real `SolutionList` envelope (plan 005 Task 2) — see
  // `shared/testdoubles/handlers/cp.ts`'s default `/cp/solutions` handler
  // for why only `Solution`'s own fields survive the mapping. The richer
  // `SolutionPackageSummary` (`withInstanceCounts(allSolutions(), …)`) stays
  // the input to the Designer/declare routes below, which still need it.
  http.get('*/cp/solutions', () =>
    HttpResponse.json({
      items: withInstanceCounts(allSolutions(), allInstances()).map((pkg) => ({
        solutionId: pkg.id,
        solution: pkg.solution,
        name: pkg.name,
        packageVersions: pkg.packageVersion,
        latestPackageVersion: pkg.packageVersion[pkg.packageVersion.length - 1],
        signature: 'unsigned' as const,
        stateVersion: 1,
      })),
    }),
  ),
  /**
   * Raw package by solutionId + version (`FE-D56`, design §10.3) — the
   * declare dialog's `Role` options only. Same resolution order as the
   * `/designer` route below: a dropped package first, the bundled fixture
   * second. No grouping happens here — `FE-D33` keeps derivation in the
   * browser; this is a passive read.
   */
  http.get('*/cp/solutions/:solutionId/package', ({ params, request }) => {
    const solutionId = String(params['solutionId']);
    const packageVersionRaw = new URL(request.url).searchParams.get('packageVersion');
    const loadedPackage = devPackageStore.get(solutionId) ?? packageForSolution(solutionId);
    if (loadedPackage === undefined || packageVersionRaw === null) {
      return new HttpResponse(null, { status: 404 });
    }
    // `getSolutionPackage` (D-4, resolved 2026-10-09): the real response is the `SolutionPackageDocument`
    // envelope (`Versioned` + `package`), not the bare document — `solutionsRepository.ts`'s `getPackage()`
    // unwraps `.package`, so this override must produce the same shape as the real backend.
    const pkg = projectable(loadedPackage);
    return HttpResponse.json({
      stateVersion: 1,
      solutionId,
      packageVersion: pkg.packageVersion[pkg.packageVersion.length - 1] ?? 1,
      package: pkg,
    });
  }),
  // plan 004: real `HomeSummary` envelope (`contracts/openapi/console.yaml`)
  // — `homeRepository.ts` derives every counter from `solutions[]` itself,
  // so this dev override only has to shape the data, not pre-aggregate it.
  http.get('*/cp/home/summary', () => {
    const instances = allInstances();
    const solutions = allSolutions().map((solution) => {
      const solutionInstances = instances.filter((i) => i.solutionId === solution.id);
      const byHealth: Record<string, number> = {};
      for (const instance of solutionInstances) {
        const key = devHealthWireKeyOf(instance);
        byHealth[key] = (byHealth[key] ?? 0) + 1;
      }
      return {
        solutionId: solution.id,
        name: solution.name,
        instanceCount: solutionInstances.length,
        byHealth,
        stateVersion: 1,
      };
    });
    return HttpResponse.json({ solutions });
  }),
  http.get('*/dp/solutions/:solutionId/instances', ({ params }) => {
    const items = allInstances().filter((i) => i.solutionId === params['solutionId']);
    return HttpResponse.json({ items, total: items.length });
  }),

  /**
   * Declare (task 115). INVENTED route and field set — §16 Q10 defines
   * neither. It stands here, in the DEV handlers, rather than in the
   * default `dpHandlers`, for the reason the header gives: the shared
   * fixture set stays exactly what the reference HTML shows, and every
   * other suite is unaffected by declarations made in a browser session.
   *
   * `taken` is every instance the merged grid would show, so a
   * declaration cannot collide with a Cache fixture row either — the
   * routes key on the id, and two rows sharing one is ambiguous.
   */
  http.post('*/dp/solutions/:solutionId/instances', async ({ params, request }) => {
    const solutionId = String(params['solutionId']);
    let body: unknown;
    try {
      body = await request.json();
    } catch {
      body = undefined;
    }

    const taken = allInstances().map((instance) => instance.name);
    // The LIVE catalog at the moment this declare is accepted (`UI-D27`) —
    // never the static `CONNECTIONS` import, which would miss an edit made
    // through the Connections screen earlier this session. `declare()`
    // keeps only the revisions of whatever this declaration actually
    // selects, and freezes them (`UI-D29`) — see its own doc.
    const liveConnectionRevisions = Object.fromEntries(
      getConnectionRows().map((row) => [row.id, row.revision]),
    );
    const outcome = devDeclaredInstances.declare(solutionId, body, taken, liveConnectionRevisions);
    if (!outcome.ok) {
      return HttpResponse.json(
        { code: outcome.rejection.code, message: outcome.rejection.message },
        { status: outcome.rejection.code === 'duplicate' ? 409 : 400 },
      );
    }
    return HttpResponse.json(outcome.instance, { status: 201 });
  }),
  // Returning `undefined` leaves the request unhandled BY THIS HANDLER, so
  // MSW falls through to the default one (msw 2.x resolver contract). That
  // is what keeps every Cache route behaving exactly as before.
  /**
   * Undeclare (task 117). Removes SM's record only — there is no workload
   * to stop here and would be none in production either; see
   * `instancesRepository.ts`'s header on why no lifecycle verb is offered.
   */
  http.delete('*/dp/instances/:instanceId', ({ params }) => {
    const instanceId = String(params['instanceId']);
    const known = allInstances().some((instance) => instance.id === instanceId);
    if (!known) {
      return new HttpResponse(null, { status: 404 });
    }
    devDeclaredInstances.undeclare(instanceId);
    return new HttpResponse(null, { status: 204 });
  }),
  http.get('*/dp/instances/:instanceId', ({ params }) => {
    const instanceId = String(params['instanceId']);
    if (devDeclaredInstances.removedIds().has(instanceId)) {
      return new HttpResponse(null, { status: 404 });
    }
    const found = devPackageStore.instanceById(instanceId) ?? devDeclaredInstances.byId(instanceId);
    return found === undefined ? undefined : HttpResponse.json(found);
  }),
  /**
   * Plan 008 Tasks 1/2 follow-up: the real console.yaml `Designer` envelope
   * (`{stateVersion, generation, viewModel, layout}`), built the same way
   * `handlers/dp.ts`'s own `/designer` route builds it — `buildWireDesigner`
   * running the SAME local `project()` engine — so `configRepository.ts`'s
   * `loadDesigner()` has one real wire shape to reverse-map regardless of
   * which route served it. `connectionCatalog`/`appliedConnectionRevisions`
   * (`UI-D27`/`UI-D28`/`UI-D29`) are gone from this response for the same
   * reason they are gone from `dp.ts`'s: `console.yaml`'s `ViewModel` schema
   * has no counterpart for either, and `configRepository.ts`'s own
   * `loadDesigner()` never reads them off the wire even when present — see
   * that module's header. The connection SELECTOR itself (e.g. a chosen
   * `solaceBroker` id) is not fabricated metadata, though — it is the
   * declared instance's own recorded literal, so it is still seeded as a
   * draft value exactly as `componentTypes` is.
   */
  http.get('*/dp/instances/:instanceId/designer', ({ params }) => {
    const instanceId = String(params['instanceId']);
    // A DECLARED instance has no package of its own — it resolves through
    // its solution. Declaring against a dropped package opens that
    // package's Designer; declaring against a baked-in fixture solution
    // (e.g. `cache`, `file-mesh`) has no `devPackageStore` entry to find,
    // so it falls back to the same real package `dp.ts`'s default handler
    // reads for a FIXTURE instance of that solution (`packages.ts`'s
    // `PACKAGES_BY_SOLUTION`) — falling through to the default handler
    // instead would 404, since its `findInstance()` only searches the
    // static `CACHE_INSTANCES` rows, never a session declaration.
    const declared = devDeclaredInstances.byId(instanceId);
    const loadedInstance = devPackageStore.instanceById(instanceId) ?? declared;
    const loadedPackage =
      devPackageStore.packageForInstance(instanceId) ??
      (declared === undefined ? undefined : devPackageStore.get(declared.solutionId)) ??
      (declared === undefined ? undefined : packageForSolution(declared.solutionId));
    if (loadedPackage === undefined || loadedInstance === undefined) {
      return undefined;
    }
    const projectedPackage = projectable(loadedPackage);
    // Nothing generic can know which discriminator to seed or what to seed
    // it with, so a dev-loaded package's Designer is exactly what its own
    // `default`s and `when:` predicates produce — never guessed draft
    // values (`viewModelFor.ts`'s own rule, carried over by task 122). The
    // one exception is a connection SELECTION (`UI-D27`): that is not a
    // guess, it is the declared instance's own recorded choice.
    const draftValues =
      loadedInstance.connections === undefined
        ? {}
        : connectionSelectorValues(projectedPackage, loadedInstance.connections);
    const wire = buildWireDesigner({
      pkg: projectedPackage,
      packageVersion: loadedInstance.packageVersion,
      draftValues,
      observed: observedStateFor(loadedInstance),
      componentTypes: loadedInstance.componentTypes,
      // No config has ever been saved for a just-loaded/declared instance,
      // so there is no generation to report — `null` is the real wire's own
      // "no generation yet" value (`console.yaml Designer.generation`),
      // never a fabricated `'gen-0'` string.
      stateVersion: 1,
      generation: null,
    });
    return HttpResponse.json(wire);
  }),
];
