// P13/P14 · RBAC data. The MODEL is locked; the VERBS are not. This candidate
// catalog is unratified placeholder so the screen has something to lay out — the
// architect group owns it. Status: grantable (exists today) · proposed (◇, not
// grantable) · absent (candidate only). Adding a verb is DATA, not a layout change.
// principal is a DISCRIMINATED reference (user | group), never a user id — so the
// IdP migration is a data change, and roles never reference principals.

const PERM_GROUPS = [
  { res: "solution", label: "Solution", perms: [{ id: "solution.view", status: "absent" }, { id: "package.load", status: "absent", hc: true }] },
  { res: "instance", label: "Instance", perms: [{ id: "instance.view", status: "absent" }, { id: "instance.create", status: "absent" }, { id: "instance.bindTarget", status: "grantable", hc: true, twoSided: true }] },
  { res: "config", label: "Configuration", perms: [{ id: "config.view", status: "absent" }, { id: "config.edit", status: "absent" }, { id: "config.apply", status: "absent", hc: true }] },
  { res: "lifecycle", label: "Lifecycle", perms: [{ id: "instance.restart", status: "absent", hc: true }, { id: "config.rollback", status: "absent", hc: true }, { id: "config.overrideWarning", status: "absent", hc: true }] },
  { res: "observability", label: "Health & logs", perms: [{ id: "health.view", status: "absent" }, { id: "logs.read", status: "absent" }, { id: "logs.setLevel", status: "absent", hc: true }] },
  { res: "metrics", label: "Metrics", perms: [{ id: "metrics.query", status: "proposed" }, { id: "metrics.export", status: "proposed" }, { id: "metrics.forceRaw", status: "proposed", hc: true }, { id: "metric.declare", status: "absent" }] },
  { res: "dashboard", label: "Dashboards", perms: [{ id: "dashboard.view", status: "proposed" }, { id: "dashboard.edit", status: "proposed" }, { id: "dashboard.reset", status: "proposed" }] },
  { res: "target", label: "Target", perms: [{ id: "target.view", status: "absent" }, { id: "target.create", status: "absent" }, { id: "target.use", status: "grantable", twoSided: true }] },
  { res: "role", label: "Roles", perms: [{ id: "role.view", status: "absent" }, { id: "role.edit", status: "absent", hc: true }, { id: "role.assign", status: "absent", hc: true }] },
  { res: "user", label: "Users", perms: [{ id: "user.view", status: "absent" }, { id: "user.create", status: "absent" }, { id: "user.deactivate", status: "absent" }] },
];
const ALL_PERMS = PERM_GROUPS.flatMap(g => g.perms.map(p => p.id));
const PERM_BY_ID = Object.fromEntries(PERM_GROUPS.flatMap(g => g.perms.map(p => [p.id, { ...p, group: g.label }])));
const STATUS_META = {
  grantable: { tone: "ok", label: "grantable", tip: "Exists in the ratified catalog and can be granted today." },
  proposed: { tone: "warn", label: "proposed · not grantable", tip: "Proposed (◇) but the catalog hasn't been amended, so it can't be granted yet." },
  absent: { tone: "idle", label: "candidate · absent", tip: "Not in the catalog at all — a placeholder name so the screen has something to lay out." },
};

const RBAC_ROLES = [
  { id: "super", name: "Solace Super-admin", kind: "built-in", desc: "Every permission, at the tenant root.", perms: "*" },
  { id: "org-op", name: "Org Operator", kind: "built-in", desc: "Operate instances within one organization.", perms: ["instance.view", "config.view", "config.edit", "config.apply", "instance.restart", "health.view", "logs.read"] },
  { id: "placement", name: "Placement Manager", kind: "built-in", desc: "Create instances and place them on targets.", perms: ["instance.view", "instance.create", "instance.bindTarget", "target.view", "target.use"] },
  { id: "auditor", name: "Read-only Auditor", kind: "built-in", desc: "See everything, change nothing.", perms: ["solution.view", "instance.view", "config.view", "health.view", "logs.read", "metrics.query", "dashboard.view", "role.view"] },
  { id: "ledger-op", name: "Ledger Operator (no restart)", kind: "custom", clonedFrom: "Org Operator", desc: "The built-in Org Operator, restart removed — the common real request.", perms: ["instance.view", "config.view", "config.edit", "config.apply", "health.view", "logs.read"] },
];

// principals: discriminated. In this release most resolve to local users; two are IdP groups (forward-compat demo).
const RBAC_PRINCIPALS = [
  { id: "u-devon", kind: "user", name: "Devon Ramirez", sub: "devon.ramirez@acme.example", source: "local" },
  { id: "u-mira", kind: "user", name: "Mira Chen", sub: "mira.chen@acme.example", source: "local" },
  { id: "u-sam", kind: "user", name: "Sam Okoye", sub: "sam.okoye@acme.example", source: "local" },
  { id: "g-pay-ops", kind: "group", name: "payments-operators", sub: "IdP group · OIDC claim", source: "idp" },
  { id: "g-sre", kind: "group", name: "platform-sre", sub: "IdP group · SCIM", source: "idp" },
];

const SCOPE_TREE = {
  name: "Acme Financial", level: "Tenant", children: [
    { name: "Payments", level: "Organization", children: [
      { name: "Ledger", level: "Sub-org", children: [
        { name: "PROD", level: "Environment", children: [{ name: "cache-prod-01", level: "Instance" }, { name: "cache-prod-02", level: "Instance" }] },
        { name: "TEST", level: "Environment", children: [{ name: "cache-stage-01", level: "Instance" }] }] }] },
    { name: "Trading", level: "Organization", children: [
      { name: "PROD", level: "Environment", children: [{ name: "cache-prod-03", level: "Instance" }, { name: "cache-prod-04", level: "Instance" }] }] },
  ],
};

const RBAC_ASSIGNMENTS = [
  { id: "a1", principal: "u-devon", role: "super", scope: ["Acme Financial"] },
  { id: "a2", principal: "u-mira", role: "org-op", scope: ["Acme Financial", "Payments"] },
  { id: "a3", principal: "g-pay-ops", role: "placement", scope: ["Acme Financial", "Payments", "Ledger", "PROD"] },
  { id: "a4", principal: "u-sam", role: "auditor", scope: ["Acme Financial", "Trading"] },
  { id: "a5", principal: "g-sre", role: "org-op", scope: ["Acme Financial", "Trading", "PROD"] },
];

const LOCAL_USERS = [
  { id: "u-devon", name: "Devon Ramirez", email: "devon.ramirez@acme.example", status: "active", lastSignIn: "2m ago", created: "installer bootstrap" },
  { id: "u-mira", name: "Mira Chen", email: "mira.chen@acme.example", status: "active", lastSignIn: "1h ago", created: "Devon Ramirez" },
  { id: "u-sam", name: "Sam Okoye", email: "sam.okoye@acme.example", status: "active", lastSignIn: "yesterday", created: "Devon Ramirez" },
  { id: "u-priya", name: "Priya Nair", email: "priya.nair@acme.example", status: "deactivated", lastSignIn: "3mo ago", created: "Devon Ramirez" },
];

const roleById = (id) => RBAC_ROLES.find(r => r.id === id);
const principalById = (id) => RBAC_PRINCIPALS.find(p => p.id === id);
const expandPerms = (role) => role.perms === "*" ? ALL_PERMS.slice() : role.perms;
const pathStr = (arr) => arr.join(" / ");
const isPrefix = (a, b) => a.length <= b.length && a.every((x, i) => x === b[i]);

Object.assign(window, { PERM_GROUPS, ALL_PERMS, PERM_BY_ID, STATUS_META, RBAC_ROLES, RBAC_PRINCIPALS, SCOPE_TREE, RBAC_ASSIGNMENTS, LOCAL_USERS, roleById, principalById, expandPerms, pathStr, isPrefix });
