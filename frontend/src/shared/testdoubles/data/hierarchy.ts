/**
 * The organization hierarchy fixture (task 133, `FE-D46`, §16 Q10).
 *
 * **THE single source of truth for `Organization → Sub-organization → Environment`.**
 * `SCOPE_TREE` (`rbac.ts`) is derived from this module rather than written
 * by hand, and `HierarchyRepository` (`shared/hierarchy/`) serves its three
 * cascade seams from it. Before this module the hierarchy was asserted in
 * two places that already disagreed — `SCOPE_TREE` knew Payments and
 * Trading while the instance rows carried six organizations — and the
 * cascade would have made a third.
 *
 * There is no separate Tenant entity (`D20`, `FE-D53`). Organization is
 * the top server-derived isolation and ownership level.
 *
 * INVENTED, like every other field in `instances.ts` — there is no
 * endpoint (§9.2, §16 Q10). Seeded from the org/sub-org/env triples the
 * instance rows already implied, so the two cannot drift; `hierarchy.test.ts`
 * asserts exactly that. `D20` maps the former Tenant value `Acme Financial`
 * to Organization, maps the old Organization values to Sub-organizations,
 * and removes the old Sub-org layer.
 */
import type { Env } from './instances';

export interface SubOrgNode {
  name: string;
  /**
   * Held as the `Env` union because that is what this console can render
   * today (`EnvChip`'s tone map). It is a LIST rather than the fixed three
   * so the third cascade step is a real load — when a backend supplies
   * customer-defined environments, widening `Env` to `string` is a change
   * to one type, not to every call site (`FE-D46`).
   */
  environments: readonly Env[];
}

export interface OrganizationNode {
  name: string;
  subOrgs: readonly SubOrgNode[];
}

export const ORGANIZATIONS: readonly OrganizationNode[] = [
  {
    name: 'Acme Financial',
    subOrgs: [
      { name: 'Payments', environments: ['PROD', 'TEST'] },
      { name: 'Trading', environments: ['PROD', 'TEST'] },
      { name: 'Risk', environments: ['TEST', 'DEV'] },
      { name: 'Onboarding', environments: ['TEST', 'DEV'] },
      { name: 'Logistics', environments: ['PROD'] },
      { name: 'Sandbox', environments: ['DEV'] },
    ],
  },
  {
    name: 'Globex',
    subOrgs: [{ name: 'Platform', environments: ['DEV'] }],
  },
] as const;

/** Level one of the cascade. */
export function organizationNames(): readonly string[] {
  return ORGANIZATIONS.map((org) => org.name);
}

/** Level two. Unknown organization yields `[]` — never a throw, because a
 * stale selection under a reloaded catalog is a normal state, not a fault. */
export function subOrgNamesOf(org: string): readonly string[] {
  const found = ORGANIZATIONS.find((candidate) => candidate.name === org);
  return found === undefined ? [] : found.subOrgs.map((subOrg) => subOrg.name);
}

/** Level three. Unknown organization or sub-org yields `[]`, same reasoning. */
export function environmentsOf(org: string, subOrg: string): readonly Env[] {
  const foundOrg = ORGANIZATIONS.find((candidate) => candidate.name === org);
  if (foundOrg === undefined) return [];
  const foundSubOrg = foundOrg.subOrgs.find((candidate) => candidate.name === subOrg);
  return foundSubOrg === undefined ? [] : foundSubOrg.environments;
}
