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
  ATTENTION_FEED,
  CACHE_INSTANCES,
  type InstanceRow,
} from '@shared/testdoubles/data/instances';
import { observedStateFor } from '@shared/testdoubles/data/packages';
import {
  SOLUTIONS,
  withInstanceCounts,
  type SolutionPackageSummary,
} from '@shared/testdoubles/data/solutions';

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

function allInstances(): readonly InstanceRow[] {
  // Undeclared ids are filtered from the MERGED list, so a fixture row and
  // a session declaration are removable on the same terms (task 117).
  const removed = devDeclaredInstances.removedIds();
  return [
    ...CACHE_INSTANCES,
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
  // `instanceCount` derived over the MERGED list (FE-120), so declaring
  // AND undeclaring both move the number the Solutions card and the
  // sidebar show — the same list `/dp/solutions/:solutionId/instances`
  // serves, so the card can never disagree with the grid it links to.
  http.get('*/cp/solutions', () =>
    HttpResponse.json(withInstanceCounts(allSolutions(), allInstances())),
  ),
  http.get('*/cp/home/summary', () => {
    const solutions = allSolutions();
    const instances = allInstances();
    return HttpResponse.json({
      solutionsManaged: new Set(solutions.map((s) => s.solution)).size,
      instancesDeclared: instances.length,
      needAttentionNow: ATTENTION_FEED.filter((a) => a.severity === 'err').length,
      configStagedNotApplied: instances.filter((i) => i.configState === 'staged').length,
    });
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
    const outcome = devDeclaredInstances.declare(solutionId, body, taken);
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
  http.get('*/dp/instances/:instanceId/designer', ({ params }) => {
    const instanceId = String(params['instanceId']);
    // A DECLARED instance has no package of its own — it resolves through
    // its solution, so declaring against a dropped package opens that
    // package's Designer. A declaration against a baked-in fixture
    // solution finds nothing here and falls through to the default
    // handler, which is the correct answer for it.
    const declared = devDeclaredInstances.byId(instanceId);
    const loadedInstance = devPackageStore.instanceById(instanceId) ?? declared;
    const loadedPackage =
      devPackageStore.packageForInstance(instanceId) ??
      (declared === undefined ? undefined : devPackageStore.get(declared.solutionId));
    if (loadedPackage === undefined || loadedInstance === undefined) {
      return undefined;
    }
    return HttpResponse.json({
      package: projectable(loadedPackage),
      packageVersion: loadedInstance.packageVersion,
      // Nothing generic can know which discriminator to seed or what to
      // seed it with, so a dev-loaded package's Designer is exactly what
      // its own `default`s and `when:` predicates produce — never guessed
      // draft values (`viewModelFor.ts`'s own rule, carried over by task 122).
      values: {},
      observed: observedStateFor(loadedInstance),
      // No config has ever been saved for a just-loaded package, so there
      // is no generation to report. `gen-0` is the baseline the save bar
      // compares against (`FE-D18` clause A) — never a fabricated history.
      configGeneration: 'gen-0',
    });
  }),
];
