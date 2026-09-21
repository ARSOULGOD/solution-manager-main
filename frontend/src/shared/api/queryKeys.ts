/**
 * THE single query-key factory (task 029, §6.0.5's twelve repository
 * seams). Every `useQuery`/`useMutation` call in the app gets its key from
 * here — never an inline array — so two widgets asking the same question
 * share one cache entry and one in-flight request.
 *
 * One namespace per repository seam, seeded now so Phase 3+ tasks extend
 * this file instead of inventing their own local key arrays. Each factory
 * returns a `readonly` tuple (`as const`) so TanStack Query's key-prefix
 * invalidation (`queryClient.invalidateQueries({ queryKey: queryKeys.instances.all() })`)
 * works correctly.
 */

export const queryKeys = {
  auth: {
    identity: () => ['auth', 'identity'] as const,
  },

  home: {
    all: () => ['home'] as const,
    summary: () => ['home', 'summary'] as const,
    attention: () => ['home', 'attention'] as const,
  },

  /**
   * The `Organization → Sub-org → Environment` cascade (`FE-D46`). Each
   * level is keyed by everything above it, so choosing a different
   * organization is a different cache entry rather than a stale hit.
   */
  hierarchy: {
    all: () => ['hierarchy'] as const,
    organizations: () => ['hierarchy', 'organizations'] as const,
    subOrgs: (org: string) => ['hierarchy', 'organizations', org, 'sub-orgs'] as const,
    environments: (org: string, subOrg: string) =>
      ['hierarchy', 'organizations', org, 'sub-orgs', subOrg, 'environments'] as const,
  },

  solutions: {
    all: () => ['solutions'] as const,
    list: () => ['solutions', 'list'] as const,
  },

  instances: {
    all: () => ['instances'] as const,
    list: (solutionId: string) => ['instances', 'list', solutionId] as const,
    detail: (instanceId: string) => ['instances', 'detail', instanceId] as const,
  },

  config: {
    all: (instanceId: string) => ['config', instanceId] as const,
    designer: (instanceId: string) => ['config', instanceId, 'designer'] as const,
    history: (instanceId: string) => ['config', instanceId, 'history'] as const,
  },

  apply: {
    outcome: (instanceId: string) => ['apply', instanceId, 'outcome'] as const,
    observe: (instanceId: string) => ['apply', instanceId, 'observe'] as const,
  },

  actions: {
    run: (instanceId: string, actionKey: string) => ['actions', instanceId, actionKey] as const,
  },

  governance: {
    health: (instanceId: string) => ['governance', instanceId, 'health'] as const,
    configLadder: (instanceId: string) => ['governance', instanceId, 'config-ladder'] as const,
    logLevels: (instanceId: string, component: string) =>
      ['governance', instanceId, 'log-levels', component] as const,
  },

  metrics: {
    all: () => ['metrics'] as const,
    capabilities: () => ['metrics', 'capabilities'] as const,
    entityTypes: () => ['metrics', 'entity-types'] as const,
    instances: (entityType: string) => ['metrics', 'instances', entityType] as const,
    catalog: () => ['metrics', 'catalog'] as const,
    labelValues: (labelKey: string) => ['metrics', 'label-values', labelKey] as const,
    /**
     * `queryHash` is the CANONICAL hash from task 070's `canonicalQueryHash()`
     * (key order normalised, ids sorted) — that is what makes two widgets
     * asking the identical question collapse to one request. Task 070 does
     * not exist yet in Phase 2; callers must pass their own stable, already-
     * normalised string until then. Do not `JSON.stringify` a `QueryRequest`
     * ad hoc here — that would silently reintroduce key-order-dependent
     * cache misses that task 070 exists specifically to prevent.
     */
    query: (queryHash: string) => ['metrics', 'query', queryHash] as const,
  },

  dashboards: {
    detail: (dashboardId: string) => ['dashboards', dashboardId] as const,
  },

  admin: {
    permissionCatalog: () => ['admin', 'permission-catalog'] as const,
    roles: () => ['admin', 'roles'] as const,
    principals: () => ['admin', 'principals'] as const,
    scopeTree: () => ['admin', 'scope-tree'] as const,
    assignments: () => ['admin', 'assignments'] as const,
    effectivePermissions: (principalId: string, scope: readonly string[]) =>
      ['admin', 'effective-permissions', principalId, ...scope] as const,
    users: () => ['admin', 'users'] as const,
    groups: () => ['admin', 'groups'] as const,
    group: (groupId: string) => ['admin', 'groups', groupId] as const,
    groupMembers: (groupId: string) => ['admin', 'groups', groupId, 'members'] as const,
    groupAssignments: (groupId: string) => ['admin', 'groups', groupId, 'assignments'] as const,
  },

  targets: {
    list: () => ['targets', 'list'] as const,
  },
} as const;
