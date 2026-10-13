/**
 * `HierarchyRepository` (task 134, `D20`, `FE-D53`; rewritten task 7, plan
 * 132, for the real backend). The backend returns a `ScopeNodeList`
 * envelope — `{ stateVersion, availability?, items: [{ id, name }] }` —
 * and its path segments are UUIDs, not display names. This module unwraps
 * that envelope and resolves the id cascade internally, so its PUBLIC
 * interface (`organizations()`, `subOrgs(org)`, `environments(org,
 * subOrg)`) stays exactly what it was: every existing caller
 * (`InstancesRoute.tsx`, `DeclareInstanceDialog.tsx`, `InstancesPage.tsx`)
 * keeps addressing levels by display name and is untouched by this
 * change.
 *
 * **Three seams, not one tree fetch.** `FE-D46` mandates a dependent
 * cascade: an operator picks an Organization, its Sub-orgs load, they pick
 * one, its Environments load. Serving that from a single hierarchy blob
 * filtered on the client would render the same screen while making the
 * "loads" a fiction — and would be the wrong shape the moment a real
 * deployment has more organizations than fit in one response.
 *
 * **Why this lives in `shared/` rather than a feature.** Both `inventory`
 * (the declare cascade) and, in due course, `admin` need it, and the
 * boundary policy in `eslint.config.js` forbids feature→feature imports
 * except through a public barrel. A hierarchy is not the property of any
 * one feature.
 *
 * **Id resolution is entirely internal.** The cascade is always driven
 * top-down (an operator cannot pick a sub-org before its organization), so
 * by the time a lower level is requested, the level above has already been
 * loaded and cached its ids here, keyed by display name. Calling a lower
 * level for a name that was never in the last loaded list of the level
 * above — a stale cached query re-selecting a name no longer present, or
 * simply calling out of order — throws a clear, catchable `Error` rather
 * than silently building a URL containing "undefined".
 *
 * `environments()` returns `Env[]` — the union is only the ELEMENT type,
 * and the list is genuinely loaded. When environments become
 * customer-defined, widening `Env` to `string` is a change to one type and
 * `EnvChip`'s tone map, not to every call site (`FE-D46`).
 */
import type { PlaneClient } from '@shared/api';
import type { Env } from '@shared/testdoubles/data/instances';

export interface HierarchyRepository {
  /** Level one of the cascade. */
  organizations(signal?: AbortSignal): Promise<string[]>;
  /** Level two. Loaded only once an organization is chosen. */
  subOrgs(org: string, signal?: AbortSignal): Promise<string[]>;
  /** Level three. Loaded only once a sub-org is chosen. */
  environments(org: string, subOrg: string, signal?: AbortSignal): Promise<Env[]>;
  /**
   * The environment's id — `declareInstance`'s path is environment-addressed
   * (`OR-52`), so this is the one id the cascade's name-only public
   * interface still needs to surface. Resolves the same self-healing way
   * `subOrgs`/`environments` resolve their own parent id: `environments()`
   * must have been called at least once for this (org, subOrg) pair first
   * (it populates the cache this reads), the same ordering constraint the
   * rest of the cascade already has.
   */
  environmentId(org: string, subOrg: string, env: string, signal?: AbortSignal): Promise<string>;
  /**
   * The reverse of `environmentId`: given an environment's id (what
   * `Instance.environmentId` carries), its (org, subOrg, env) display
   * names, for the instance grid's name/env column (plan 007). The
   * cascade has no id-indexed reads of its own, so this walks the whole
   * visible tree once per unknown id and caches the result — cheap at the
   * scale this console targets (a handful of organizations), and it only
   * pays the walk once per environment, not once per instance row.
   */
  environmentPath(
    environmentId: string,
    signal?: AbortSignal,
  ): Promise<{ org: string; subOrg: string; env: string } | undefined>;
}

interface ScopeNode {
  id: string;
  name: string;
}

interface ScopeNodeList {
  stateVersion: number;
  availability?: unknown;
  items: ScopeNode[];
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

/**
 * Looks up the id cached for `name` at the given cascade level. Throws a
 * real `Error` — never returns `undefined` or lets a caller build a URL
 * with "undefined" in it — when `name` was not present in the last loaded
 * list at that level, e.g. a stale cached selection re-firing against a
 * freshly-fetched (and now different) parent list. This is the true
 * last-resort case `idForSelfHealing` falls through to.
 */
function idFor(cache: Map<string, string>, name: string, levelLabel: string): string {
  const id = cache.get(name);
  if (id === undefined) {
    throw new Error(
      `${levelLabel} "${name}" was not present in the last loaded list — the cascade was called out of order or with a stale selection.`,
    );
  }
  return id;
}

/**
 * `idFor`, but self-healing an EMPTY cache first (final review finding,
 * plan 132). A `HierarchyRepository` instance is closure-private state —
 * `InstancesRoute.tsx` builds one via `useMemo` keyed on `apiBasePath`, so
 * it is destroyed on route unmount — but React Query's `QueryClient`
 * outlives route navigation and, at `staleTime: 0`, serves a cached parent
 * NAME synchronously while it background-refetches. Navigating away from
 * Instances and back can therefore hand a fresh (empty-cache) repository
 * instance a name from React Query's cache with nothing behind it here.
 *
 * `refetchParent` reloads the level ABOVE `cache` (repopulating it) and
 * `idFor` is retried exactly once. If the name is still missing after that
 * refetch, this is the genuine case `idFor`'s throw exists for — a stale
 * selection naming something no longer in the freshly-fetched list — and
 * it throws with the same clear message as before.
 */
async function idForSelfHealing(
  cache: Map<string, string>,
  name: string,
  levelLabel: string,
  refetchParent: () => Promise<unknown>,
): Promise<string> {
  if (cache.has(name)) {
    return idFor(cache, name, levelLabel);
  }
  await refetchParent();
  return idFor(cache, name, levelLabel);
}

export function createHierarchyRepository(cpClient: PlaneClient): HierarchyRepository {
  // Populated by each level's own call, keyed by display name — see the
  // module doc for why top-down ordering makes this safe, and
  // `idForSelfHealing`'s doc for the one case (a remounted instance) where
  // it briefly isn't.
  const orgIds = new Map<string, string>();
  const subOrgIds = new Map<string, string>();
  // Keyed by `${org}\u0000${subOrg}\u0000${env}` — environment names are
  // only unique within one (org, subOrg) pair, unlike org/sub-org names.
  const envIds = new Map<string, string>();
  const envKey = (org: string, subOrg: string, env: string): string => `${org}\u0000${subOrg}\u0000${env}`;

  async function organizations(signal?: AbortSignal): Promise<string[]> {
    const list = await cpClient.get<ScopeNodeList>('/hierarchy/organizations', opts(signal));
    orgIds.clear();
    list.items.forEach((item) => orgIds.set(item.name, item.id));
    return list.items.map((item) => item.name);
  }

  async function subOrgs(org: string, signal?: AbortSignal): Promise<string[]> {
    const orgId = await idForSelfHealing(orgIds, org, 'Organization', () => organizations(signal));
    const list = await cpClient.get<ScopeNodeList>(
      `/hierarchy/organizations/${encodeURIComponent(orgId)}/sub-orgs`,
      opts(signal),
    );
    subOrgIds.clear();
    list.items.forEach((item) => subOrgIds.set(item.name, item.id));
    return list.items.map((item) => item.name);
  }

  async function environments(org: string, subOrg: string, signal?: AbortSignal): Promise<Env[]> {
    const orgId = await idForSelfHealing(orgIds, org, 'Organization', () => organizations(signal));
    const subOrgId = await idForSelfHealing(subOrgIds, subOrg, 'Sub-organization', () =>
      subOrgs(org, signal),
    );
    const list = await cpClient.get<ScopeNodeList>(
      `/hierarchy/organizations/${encodeURIComponent(orgId)}/sub-orgs/${encodeURIComponent(subOrgId)}/environments`,
      opts(signal),
    );
    list.items.forEach((item) => envIds.set(envKey(org, subOrg, item.name), item.id));
    // `Env` was widened by task 8 to accept any server-defined name
    // (`string & {}`), so this list-of-names is already assignable to
    // `Env[]` with no cast needed.
    return list.items.map((item) => item.name);
  }

  async function environmentId(
    org: string,
    subOrg: string,
    env: string,
    signal?: AbortSignal,
  ): Promise<string> {
    const key = envKey(org, subOrg, env);
    try {
      return await idForSelfHealing(envIds, key, 'Environment', () =>
        environments(org, subOrg, signal),
      );
    } catch {
      // `idFor`'s own message embeds the raw composite cache key; this
      // throws the same case with the (org, subOrg, env) names instead.
      throw new Error(
        `Environment "${env}" of "${org}" / "${subOrg}" was not present in the last loaded list — the cascade was called out of order or with a stale selection.`,
      );
    }
  }

  const pathById = new Map<string, { org: string; subOrg: string; env: string }>();

  async function environmentPath(
    envId: string,
    signal?: AbortSignal,
  ): Promise<{ org: string; subOrg: string; env: string } | undefined> {
    const cached = pathById.get(envId);
    if (cached !== undefined) {
      return cached;
    }
    const orgNames = await organizations(signal);
    for (const org of orgNames) {
      const subOrgNames = await subOrgs(org, signal);
      for (const subOrg of subOrgNames) {
        const envNames = await environments(org, subOrg, signal);
        for (const env of envNames) {
          pathById.set(idFor(envIds, envKey(org, subOrg, env), 'Environment'), { org, subOrg, env });
        }
      }
    }
    return pathById.get(envId);
  }

  return { organizations, subOrgs, environments, environmentId, environmentPath };
}
