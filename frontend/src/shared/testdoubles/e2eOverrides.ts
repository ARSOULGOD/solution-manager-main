/**
 * DEV-only session overrides, read from the URL, for the `FE-099` e2e
 * journeys that cannot be expressed as fixture *data*.
 *
 * Three of the seven journeys need to vary something the fixture layer
 * does not own:
 *
 *   · **Permission** (journey 6) — the console must be seen omitting a
 *     control for a missing verb and disabling one for a capability
 *     limit. `devMockAdapter`'s identity is a single hard-coded
 *     `superAdmin`, so with it there is no such thing as a missing verb.
 *   · **Idle lock** (journey 7) — `config.json` ships
 *     `idleTimeoutMinutes: 30`. A journey cannot wait thirty minutes, and
 *     MSW's service worker sits between Playwright's `page.route` and
 *     that fetch, so the test cannot rewrite the response either.
 *   · Journey 2's `409` is **not** here: a save conflict is a thing that
 *     happens to a particular record, so it is modelled as fixture data
 *     (`CONFLICTED_INSTANCE_ID` in `handlers/dp.ts`) rather than as a
 *     switch. Prefer that shape whenever the scenario has a natural home
 *     in the data — a flag is the last resort, not the first.
 *
 * **Why the URL and not a test-only global.** A journey that opens
 * `?e2e_grants=none` states its own premise in a line a human can paste
 * into a browser and see for themselves. `addInitScript` would hide that
 * premise inside the test file and make the scenario unreproducible by
 * hand.
 *
 * **It lives in `shared/testdoubles/`, not `app/dev/`, because of
 * boundaries.** Two callers need it — `shared/auth/devMockAdapter.ts` and
 * `app/routes/AuthenticatedLayout.tsx` — and `shared` may import only
 * `contracts` (`eslint.config.js`), so a home under `app/` would be
 * unreachable from the adapter. `shared/testdoubles/` is already the
 * fixture-and-double layer the app boots from in DEV.
 *
 * **Production safety — the same rule `devPackageHandlers.ts` documents
 * (`FE-D24`).** Every caller reaches this module from inside a literal
 * `if (import.meta.env.DEV)` branch, which Vite folds to `false` in a
 * production build and drops the module graph beneath. The guard in
 * `readE2EOverrides` is defence in depth for a direct import, not the
 * primary mechanism, and `FE-101` asserts the built bundle contains none
 * of it.
 *
 * ⚠️ **This file must never grow a switch that changes shipped
 * behaviour.** It exists to make a *scenario* reachable, never to make a
 * feature behave differently. If a journey needs something here that
 * isn't "put the session into a state a real deployment could also be
 * in", the journey is testing the wrong thing.
 */

/** Grants the console runs with, when a journey overrides them. */
export type E2EGrantProfile =
  /** Every verb, `superAdmin` — the `devMockAdapter` default. */
  | 'all'
  /** No verbs at all, and not a super-admin: controls gated on a verb are OMITTED. */
  | 'none'
  /** Verbs granted, but the deployment's capability limits still apply: controls DISABLED with a reason. */
  | 'limited';

export interface E2EOverrides {
  /** `?e2e_grants=none|limited|all` */
  grants: E2EGrantProfile | undefined;
  /** `?e2e_idle_seconds=2` — expressed in seconds because a journey cannot wait minutes. */
  idleSeconds: number | undefined;
}

const GRANT_PROFILES: readonly E2EGrantProfile[] = ['all', 'none', 'limited'];

function parseGrants(raw: string | null): E2EGrantProfile | undefined {
  if (raw === null) {
    return undefined;
  }
  return GRANT_PROFILES.find((profile) => profile === raw);
}

function parseIdleSeconds(raw: string | null): number | undefined {
  if (raw === null) {
    return undefined;
  }
  const seconds = Number(raw);
  // Rejects NaN, negatives and 0 alike: `useIdleTimer` treats a
  // non-positive timeout as "disabled", so letting 0 through here would
  // silently turn the idle journey into a no-op that still passes.
  return Number.isFinite(seconds) && seconds > 0 ? seconds : undefined;
}

/**
 * Reads the overrides off the current URL. Returns everything `undefined`
 * outside DEV, so a caller that forgets its own guard degrades to the
 * shipped behaviour rather than to a broken one.
 */
export function readE2EOverrides(search: string = window.location.search): E2EOverrides {
  if (!import.meta.env.DEV) {
    return { grants: undefined, idleSeconds: undefined };
  }
  const params = new URLSearchParams(search);
  return {
    grants: parseGrants(params.get('e2e_grants')),
    idleSeconds: parseIdleSeconds(params.get('e2e_idle_seconds')),
  };
}
