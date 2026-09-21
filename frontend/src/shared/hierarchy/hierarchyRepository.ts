/**
 * `HierarchyRepository` (task 134, `D20`, `FE-D53`). CONTRACT: TBD — like every
 * other seam in §6.0.5, these three routes are fixture-only; `contracts/`
 * does not exist yet.
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
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

/**
 * Segments are `encodeURIComponent`-escaped: organization and sub-org names
 * are free-form display strings (`EU-West`, and nothing stops a real one
 * containing a slash or a space), so interpolating them raw would build a
 * URL that addresses the wrong resource.
 */
export function createHierarchyRepository(cpClient: PlaneClient): HierarchyRepository {
  return {
    organizations(signal) {
      return cpClient.get<string[]>('/hierarchy/organizations', opts(signal));
    },
    subOrgs(org, signal) {
      return cpClient.get<string[]>(
        `/hierarchy/organizations/${encodeURIComponent(org)}/sub-orgs`,
        opts(signal),
      );
    },
    environments(org, subOrg, signal) {
      return cpClient.get<Env[]>(
        `/hierarchy/organizations/${encodeURIComponent(org)}/sub-orgs/${encodeURIComponent(subOrg)}/environments`,
        opts(signal),
      );
    },
  };
}
