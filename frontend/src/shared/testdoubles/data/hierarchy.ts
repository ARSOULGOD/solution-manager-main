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
  // Added for static (no-backend) cascade testing: more than one organization to switch between,
  // each with several sub-organizations and differing environment sets, so the dependent
  // selections visibly reload and reset. Their instances do not exist in `instances.ts`.
  {
    name: 'Initech',
    subOrgs: [
      { name: 'Retail', environments: ['PROD', 'TEST', 'DEV'] },
      { name: 'Wholesale', environments: ['PROD', 'TEST'] },
      { name: 'Support', environments: ['DEV'] },
    ],
  },
  {
    name: 'Umbrella Health',
    subOrgs: [
      { name: 'Clinical', environments: ['PROD', 'TEST'] },
      { name: 'Research', environments: ['TEST', 'DEV'] },
    ],
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

// ---- Wire-shaped views (id-bearing), for the MSW handlers ------------------------------------
//
// The real server (`console.yaml` `ScopeNodeList`) addresses each cascade level by an opaque id and
// returns `{ items: [{ id, name }] }`; the repository resolves names to ids and back. The fixture
// ids are DETERMINISTIC (a hash of the name path), so a reload, a remount and a second test run all
// see the same id for the same node — never a random one that would invalidate a cached selection.

export interface ScopeNodeFixture {
  id: string;
  name: string;
}

/** FNV-1a, 32-bit. Not cryptographic — only a stable, well-spread fixture id. */
function fnv1a(text: string, seed: number): number {
  let hash = seed >>> 0;
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash >>> 0;
}

/** A UUID-shaped id for a node, derived from its full name path. */
export function nodeId(...path: readonly string[]): string {
  const key = path.join('\u0000');
  const a = fnv1a(key, 0x811c9dc5).toString(16).padStart(8, '0');
  const b = fnv1a(key, 0x9747b28c).toString(16).padStart(8, '0');
  return `${a.slice(0, 8)}-${b.slice(0, 4)}-4${b.slice(4, 7)}-8${a.slice(1, 4)}-${a}${b.slice(0, 4)}`;
}

/** Level one, wire-shaped. */
export function organizationNodes(): readonly ScopeNodeFixture[] {
  return ORGANIZATIONS.map((org) => ({ id: nodeId(org.name), name: org.name }));
}

/** Level two, addressed by the organization's ID. Unknown id → `[]`. */
export function subOrgNodesOf(orgId: string): readonly ScopeNodeFixture[] {
  const org = ORGANIZATIONS.find((candidate) => nodeId(candidate.name) === orgId);
  return org === undefined
    ? []
    : org.subOrgs.map((sub) => ({ id: nodeId(org.name, sub.name), name: sub.name }));
}

/** Level three, addressed by the organization's and sub-organization's IDs. Unknown id → `[]`. */
export function environmentNodesOf(orgId: string, subOrgId: string): readonly ScopeNodeFixture[] {
  const org = ORGANIZATIONS.find((candidate) => nodeId(candidate.name) === orgId);
  const sub = org?.subOrgs.find((candidate) => nodeId(org.name, candidate.name) === subOrgId);
  return org === undefined || sub === undefined
    ? []
    : sub.environments.map((env) => ({ id: nodeId(org.name, sub.name, env), name: env }));
}
