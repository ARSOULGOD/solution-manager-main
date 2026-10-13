// Sample data + state vocabularies. Console hard-codes nothing per solution;
// this is demo content drawn from architecture.md (Cache = v1 target, File Mesh = phase 2).

// Tenant is a display string the server returns — NOT a switcher, not in the route.
const TENANT = "Acme Financial · self-hosted";
const CURRENT_USER = { name: "Devon Ramirez", email: "devon.ramirez@acme.example", role: "Operator", superAdmin: true };

// ---- STATE VOCABULARIES (colour is never the only signal: each carries icon + text) ----
// Health — five values, not two.
const HEALTH = {
  healthy:          { label: "Healthy",           tone: "ok",      dot: "ok",   icon: "checkCircle" },
  not_responding:   { label: "Not responding",    tone: "err",     dot: "err",  icon: "xCircle",
                      tip: "The instance stopped answering health checks. Investigate." },
  restart_expected: { label: "Restart expected",  tone: "info",    dot: "info", icon: "restart",
                      tip: "The platform restarted this instance on purpose — this is not an outage." },
  inactive_replica: { label: "Inactive replica",  tone: "idle",    dot: "idle", icon: "pauseCircle",
                      tip: "A standby: up and deliberately processing nothing. The instance is fine — its KPIs read zero because it takes no traffic." },
  not_applicable:   { label: "Not applicable",    tone: "idle",    dot: "idle", icon: "ban",
                      tip: "This solution exposes no health endpoint — a normal condition. It is never treated as an outage." },
};
// "Declared but not found" — four conditions, plus the bootstrap case. Never one "missing" badge.
const NOTFOUND = {
  restart_expected: HEALTH.restart_expected,
  unreachable:      { label: "Host unreachable",  tone: "warn",    dot: "warn", icon: "wifiOff",  scope: "Host / target alert",
                      tip: "Transport-level failure — a host or network problem, not an instance problem." },
  not_exposed:      { label: "Health not exposed", tone: "warn",   dot: "warn", icon: "alert",    scope: "Configuration finding",
                      tip: "Reachable, but the health endpoint is absent or unexposed. A configuration finding — fixable." },
  missing:          { label: "Missing",           tone: "err",     dot: "err",  icon: "xCircle",  scope: "Instance alert",
                      tip: "Reachable and exposed, yet the instance is genuinely absent beyond N intervals." },
  bootstrap:        { label: "Awaiting first restart", tone: "info", dot: "info", icon: "hourglass", scope: "Bootstrap",
                      tip: "Newly declared — unobservable until after its first restart. Sequence: declare → apply → restart → observable." },
};
// Version — four values; 'unverified' is the permanent-normal state on a plain VM (editable).
const VERSION = {
  ok:         { label: "OK",         tone: "ok",   icon: "check",  edit: true,  note: "Observed matches declared." },
  drift:      { label: "Drift",      tone: "warn", icon: "alert",  edit: false, note: "Deployed and declared disagree. Config is read-only until reconciled." },
  unknown:    { label: "Unknown",    tone: "err",  icon: "ban",    edit: false, blocked: true, note: "No package covers the running version. Config editing is blocked; monitoring continues." },
  unverified: { label: "Unverified", tone: "idle", icon: "info",   edit: true,  note: "Nothing corroborates the declared version — normal for an MI on a plain VM. Editing is allowed." },
};
// Config — five states, never collapsed. Cache is permanently effective-unverified.
const CONFIG = {
  in_sync:  { label: "Applied",                tone: "ok",   icon: "checkCircle",
              tip: "Restart observed after the config was written (restart-observed). Not proof the values are effective — Cache exposes no protected config endpoint, so effective values stay unverified." },
  staged:   { label: "Staged · restart req'd", tone: "warn", icon: "hourglass",
              tip: "Config written and persisted, but the process has not restarted. Written is not working — restart to apply." },
  rendered: { label: "Rendered · not persisted", tone: "info", icon: "doc",
              tip: "SM produced the file but has not confirmed it landed on the target yet." },
  diverged: { label: "Config diverged",        tone: "err",  icon: "alert",
              tip: "Replicas are split across config versions after a halted rollout. Explicit remediation required." },
  drift:    { label: "Drift detected",         tone: "warn", icon: "edit",
              tip: "The on-target file differs from SM's rendered config — a hand-edit. Alert-only; reconcile manually (never auto-reverted)." },
  na:       { label: "—",                       tone: "idle", icon: "ban",
              tip: "observe-only target — Solution Manager writes no configuration here." },
};
const DRIVER = { docker: { label: "Docker", icon: "package" }, k8s: { label: "Kubernetes", icon: "network" }, systemd: { label: "systemd · VM", icon: "hardDrive" } };
const LOCUS  = {
  "dp-remote":   { label: "dp-remote",   icon: "globe",    tip: "The data plane reaches into the runtime (SSH / Docker API / K8s API). Holds credentials; needs inbound reach." },
  "host-local":  { label: "host-local",  icon: "server",   tip: "An agent on the host acts locally. Outbound-only, no stored host credentials." },
  "observe-only":{ label: "observe-only",icon: "eye",      tip: "SM never writes to this target — telemetry only." },
};

// ---- SOLUTIONS CATALOG (P3) — real package schema fields ----
const SOLUTIONS = [
  { id: "cache", solution: "cache", name: "Cache Engine", color: "#015B82", icon: "bolt",
    kind: "Distributed cache · Spring Boot", packageVersion: 7, appliesTo: ">=2.2 <2.5",
    provenance: { author: "solace", signature: "3af9…c210" },
    authoring: { reviewedRows: 1494, totalRows: 1494, source: "curated + contract-tested" },
    components: ["cache-engine", "management", "telemetry-http"],
    instances: 12, entitled: true, telemetry: "HTTP poll · actuator", published: "2026-08-04" },
  { id: "filemesh", solution: "file-mesh", name: "File Mesh", color: "#3A4880", icon: "files",
    kind: "Micro-integration · connector framework", packageVersion: 3, appliesTo: ">=1.4 <2.0",
    provenance: { author: "solace", signature: "9b12…7ee4" },
    authoring: { reviewedRows: 1287, totalRows: 1494, source: "FM_CONFIGURATION_TEMPLATE" },
    components: ["file-connector", "flow", "command-center"],
    instances: 0, entitled: true, telemetry: "Broker push · command-center", published: "2026-07-22" },
  { id: "cache-nightly", solution: "cache", name: "Cache Engine · nightly", color: "#8a5a1e", icon: "beaker",
    kind: "Distributed cache · unsigned build", packageVersion: "dev-2026.08.17", appliesTo: "dev",
    provenance: { author: "dev-mode" },
    authoring: { reviewedRows: 0, totalRows: 1494, source: "extracted, unreviewed" },
    components: ["cache-engine", "management", "telemetry-http"],
    instances: 1, entitled: true, telemetry: "HTTP poll · actuator", published: "2026-08-17", devMode: true },
];

// ---- INSTANCE INVENTORY (P4) — Cache; spans every health / version / config state ----
const CACHE_INSTANCES = [
  { id: "cache-prod-01", name: "cache-prod-01", env: "PROD", org: "Payments", sub: "Ledger", driver: "k8s", locus: "dp-remote",
    replicas: [{ n: "ledger-0", h: "healthy" }, { n: "ledger-1", h: "healthy" }, { n: "ledger-2", h: "inactive_replica" }],
    roll: "healthy", version: { state: "ok", observed: "2.3.0", declared: "2.3.0" }, config: "in_sync", lastSeen: "12s ago" },
  { id: "cache-prod-02", name: "cache-prod-02", env: "PROD", org: "Payments", sub: "Ledger", driver: "k8s", locus: "dp-remote",
    replicas: [{ n: "ledger-b-0", h: "healthy" }, { n: "ledger-b-1", h: "healthy" }],
    roll: "healthy", version: { state: "drift", observed: "2.4.1", declared: "2.3.0" }, config: "in_sync", lastSeen: "9s ago" },
  { id: "cache-prod-03", name: "cache-prod-03", env: "PROD", org: "Trading", sub: "Quotes", driver: "systemd", locus: "host-local",
    replicas: [{ n: "app01", h: "healthy" }],
    roll: "healthy", version: { state: "unverified", observed: "—", declared: "2.3.0" }, config: "in_sync", lastSeen: "20s ago" },
  { id: "cache-prod-04", name: "cache-prod-04", env: "PROD", org: "Trading", sub: "Quotes", driver: "k8s", locus: "dp-remote",
    replicas: [{ n: "quotes-0", h: "healthy" }, { n: "quotes-1", h: "healthy" }, { n: "quotes-2", h: "healthy" }, { n: "quotes-3", h: "healthy" }],
    roll: "healthy", version: { state: "ok", observed: "2.3.0", declared: "2.3.0" }, config: "in_sync", lastSeen: "3s ago" },
  { id: "cache-stage-01", name: "cache-stage-01", env: "TEST", org: "Trading", sub: "Quotes", driver: "docker", locus: "dp-remote",
    replicas: [{ n: "c1", h: "healthy" }, { n: "c2", h: "not_responding" }],
    roll: "not_responding", version: { state: "ok", observed: "2.3.0", declared: "2.3.0" }, config: "staged", lastSeen: "6s ago" },
  { id: "cache-stage-02", name: "cache-stage-02", env: "TEST", org: "Risk", sub: "Batch", driver: "k8s", locus: "host-local",
    replicas: [{ n: "batch-0", h: "healthy" }, { n: "batch-1", h: "restart_expected" }, { n: "batch-2", h: "healthy" }],
    roll: "restart_expected", version: { state: "ok", observed: "2.3.0", declared: "2.3.0" }, config: "in_sync", lastSeen: "4s ago" },
  { id: "cache-qa-01", name: "cache-qa-01", env: "TEST", org: "Onboarding", sub: "KYC", driver: "docker", locus: "dp-remote",
    replicas: [{ n: "kyc-a", h: "healthy" }, { n: "kyc-b", h: "healthy" }],
    roll: "healthy", version: { state: "ok", observed: "2.3.0", declared: "2.3.0" }, config: "diverged", lastSeen: "5s ago" },
  { id: "cache-edge-01", name: "cache-edge-01", env: "PROD", org: "Logistics", sub: "EU-West", driver: "docker", locus: "dp-remote",
    replicas: [{ n: "eu-1", h: "unreachable" }, { n: "eu-2", h: "unreachable" }],
    roll: "notfound", notFound: "unreachable", version: { state: "unknown", observed: "?", declared: "2.2.0" }, config: "in_sync", lastSeen: "8m ago" },
  { id: "cache-edge-02", name: "cache-edge-02", env: "PROD", org: "Logistics", sub: "EU-West", driver: "k8s", locus: "observe-only",
    replicas: [{ n: "obs-0", h: "not_applicable" }, { n: "obs-1", h: "not_applicable" }],
    roll: "not_applicable", version: { state: "unverified", observed: "—", declared: "2.3.0" }, config: "na", lastSeen: "15s ago" },
  { id: "cache-dev-01", name: "cache-dev-01", env: "DEV", org: "Risk", sub: "Batch", driver: "docker", locus: "dp-remote",
    replicas: [{ n: "dev-0", h: "restart_expected" }],
    roll: "notfound", notFound: "bootstrap", version: { state: "unverified", observed: "—", declared: "2.4.0" }, config: "rendered", lastSeen: "never" },
  { id: "cache-dev-02", name: "cache-dev-02", env: "DEV", org: "Onboarding", sub: "KYC", driver: "systemd", locus: "host-local",
    replicas: [{ n: "vm-dev", h: "not_responding" }],
    roll: "notfound", notFound: "missing", version: { state: "ok", observed: "2.3.0", declared: "2.3.0" }, config: "drift", lastSeen: "41m ago" },
  { id: "cache-dev-03", name: "cache-dev-03", env: "DEV", org: "Sandbox", sub: "—", driver: "systemd", locus: "host-local",
    replicas: [{ n: "sbx", h: "not_responding" }],
    roll: "notfound", notFound: "not_exposed", version: { state: "unverified", observed: "—", declared: "2.3.0" }, config: "in_sync", lastSeen: "30s ago" },
];

const ENVS = ["DEV", "TEST", "PROD"];
const ENV_TONE = { DEV: "neutral", TEST: "info", PROD: "purple" };

// ---- HOME (P2) attention feed ----
const ATTENTION = [
  { sev: "err",  icon: "xCircle",   title: "cache-stage-01 · replica not responding", detail: "1 of 2 replicas stopped answering health checks — TEST · Trading / Quotes.", to: "#/solutions/cache/instances?focus=cache-stage-01" },
  { sev: "err",  icon: "wifiOff",   title: "cache-edge-01 · host unreachable", detail: "Both replicas unreachable for 8m in PROD · Logistics / EU-West. A host or network problem — not the instance.", to: "#/solutions/cache/instances?focus=cache-edge-01" },
  { sev: "err",  icon: "alert",     title: "cache-qa-01 · configuration diverged", detail: "Replicas are split across config versions after a halted rollout. Remediation required.", to: "#/solutions/cache/instances?focus=cache-qa-01" },
  { sev: "warn", icon: "hourglass", title: "Configuration staged, not applied", detail: "1 instance has config written but awaiting restart. Written is not working — a restart is required to apply it.", to: "#/solutions/cache/instances?focus=cache-stage-01" },
  { sev: "warn", icon: "alert",     title: "Version drift · cache-prod-02", detail: "Deployed 2.4.1, declared 2.3.0. Config is read-only for this instance until reconciled.", to: "#/solutions/cache/instances?focus=cache-prod-02" },
  { sev: "warn", icon: "ban",       title: "Version unknown · cache-edge-01", detail: "No published package covers the running version. Config editing is blocked; monitoring continues.", to: "#/solutions/cache/instances?focus=cache-edge-01" },
  { sev: "info", icon: "sparkle",   title: "12 metric series arriving unclassified", detail: "Ingest is receiving series that match no declared metric. Classify them into the contract, or they are dropped and counted.", to: "#/solutions/cache/instances" },
];

Object.assign(window, { TENANT, CURRENT_USER, HEALTH, NOTFOUND, VERSION, CONFIG, DRIVER, LOCUS, SOLUTIONS, CACHE_INSTANCES, ENVS, ENV_TONE, ATTENTION });
