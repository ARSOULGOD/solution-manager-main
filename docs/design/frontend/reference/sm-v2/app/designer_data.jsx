// P5 · Designer view models. ONE Designer renders all of these — the console holds
// no per-solution form code. Shapes mirror the executable contract: fields carry
// controlKind, value{kind}, visible/enabled/virtual/advanced/masked/discriminator,
// validations[], hiddenBy[]; sections arrive pre-ordered.
const Vv = {
  val: (v, source = "draft") => ({ kind: "value", value: v, source }),
  unset: () => ({ kind: "unset" }),
  secretRef: (ref) => ({ kind: "secretRef", ref }),
  secretUnset: () => ({ kind: "secretUnset" }),
};
const REQUIRED = [{ severity: "block", code: "REQUIRED", message: "This field is required." }];

// field factory
function F(o) {
  const rp = o.rp === null ? null : (o.rp || `cache-engine.${o.key}`);
  return {
    uid: `${o.ik || "i1"}::${o.section}::${o.key}`,
    ik: o.ik || "i1", key: o.key, renderedPath: rp, controlKind: o.ck || "text",
    value: o.v || Vv.unset(), options: o.options, section: o.section,
    visible: o.visible !== false, enabled: o.enabled !== false, virtual: !!o.virtual,
    advanced: !!o.advanced, masked: o.ck === "secret", discriminator: !!o.disc,
    label: o.label || o.key, helperText: o.help, validations: o.val || [], hiddenBy: o.hiddenBy || [],
    when: o.when,
  };
}

// ---- CACHE (P5) — 26 fields across 8 pre-ordered sections; 4 discriminators ----
function cacheComponent() {
  const S = (title, extra) => ({ title, ...extra });
  const sections = [
    S("General"), S("Keying"), S("Expiry", { allAdvanced: true }), S("Sizing"),
    S("Topics"), S("Hit response", { allAdvanced: true }), S("Broker"), S("Management", { allAdvanced: true }),
  ];
  const solOn = (g) => !!g("solace.enabled");
  const fields = [
    F({ section: "General", key: "mode", ck: "select", options: ["READ", "READ_WRITE", "ADMIN"], v: Vv.val("READ", "default"), label: "Mode", help: "READ_WRITE exposes the write endpoint; ADMIN additionally exposes admin operations." }),
    F({ section: "General", key: "provider", ck: "select", options: ["caffeine", "redis", "jcache"], v: Vv.val("caffeine", "default"), disc: true, label: "Provider", help: "Only caffeine reports stats and size. Java type is String, no enum validation." }),
    F({ section: "General", key: "namespace", ck: "text", v: Vv.unset(), label: "Namespace", val: REQUIRED }),

    F({ section: "Keying", key: "key-source", ck: "select", options: ["TOPIC", "PAYLOAD", "HEADER", "COMPOSITE"], v: Vv.unset(), disc: true, label: "Key source", val: REQUIRED }),
    F({ section: "Keying", key: "topic-key-levels", ck: "text", v: Vv.unset(), label: "Topic key levels", help: "Java type is String, not int, and there is no field initializer.", hiddenBy: ["key-source"], when: (g) => ["TOPIC", "COMPOSITE"].includes(g("key-source")) }),
    F({ section: "Keying", key: "payload-key-json-pointer", ck: "text", v: Vv.unset(), label: "Payload key JSON pointer", hiddenBy: ["key-source"], when: (g) => ["PAYLOAD", "COMPOSITE"].includes(g("key-source")) }),

    F({ section: "Expiry", key: "ttl", ck: "duration", v: Vv.unset(), advanced: true, label: "TTL", help: "Spring Duration syntax, e.g. 120m or PT2H. No MI default." }),
    F({ section: "Expiry", key: "expiry-header-key-pointer", ck: "text", v: Vv.unset(), advanced: true, label: "Expiry header key pointer" }),
    F({ section: "Expiry", key: "expiry-header-format", ck: "select", options: ["ISO_8601", "EPOCH_MS"], v: Vv.unset(), advanced: true, label: "Expiry header format" }),

    F({ section: "Sizing", key: "max-size", ck: "number", v: Vv.unset(), label: "Max entries", help: "No MI default: Java int maxSize is 0, which evicts everything.", val: REQUIRED }),
    F({ section: "Sizing", key: "max-weight", ck: "number", v: Vv.unset(), label: "Max weight", help: "Optional weight-based bound; leave empty to bound by entry count only." }),

    F({ section: "Topics", key: "topic-subscriptions", ck: "string-list", v: Vv.unset(), label: "Topic subscriptions" }),
    F({ section: "Topics", key: "topic-prefix", ck: "text", v: Vv.val("acme/cache", "draft"), label: "Topic prefix" }),

    F({ section: "Hit response", key: "cache-hit-response.template-mappings", ck: "key-value", v: Vv.unset(), advanced: true, label: "Template mappings", help: "Map<String,String>. The MI's own sample ships an absolute path." }),
    F({ section: "Hit response", key: "cache-hit-response.include-metadata", ck: "switch", v: Vv.val(false, "default"), advanced: true, label: "Include metadata" }),

    F({ section: "Broker", key: "solace.enabled", ck: "switch", v: Vv.val(true, "draft"), disc: true, label: "Solace enabled", help: "Java default is false (no field initializer), so an instance is disconnected unless enabled." }),
    F({ section: "Broker", key: "solace.host", ck: "text", v: Vv.unset(), label: "Host", val: REQUIRED, when: solOn }),
    F({ section: "Broker", key: "solace.vpn", ck: "text", v: Vv.val("default", "default"), label: "Message VPN", when: solOn }),
    F({ section: "Broker", key: "solace.username", ck: "text", v: Vv.unset(), label: "Username", when: solOn }),
    F({ section: "Broker", key: "solace.password", ck: "secret", v: Vv.secretUnset(), label: "Password", help: "secretRef. Masked in form, diff, export and history.", when: solOn }),
    F({ section: "Broker", key: "solace.port", ck: "number", v: Vv.val(55555, "default"), label: "Port", when: solOn }),
    F({ section: "Broker", key: "solace.tls", ck: "switch", v: Vv.val(true, "draft"), label: "Use TLS", when: solOn }),
    F({ section: "Broker", key: "solace.messaging-mode", ck: "select", options: ["MESSAGING", "PERSISTENT"], v: Vv.val("MESSAGING", "draft"), label: "Messaging mode", when: solOn }),

    F({ section: "Management", key: "solace.management.enabled", ck: "switch", v: Vv.val(true, "draft"), disc: true, advanced: true, label: "Management plane enabled", when: solOn }),
    F({ section: "Management", key: "solace.management.management-queue", ck: "text", v: Vv.val("mgmt-q", "draft"), advanced: true, label: "Management queue", help: "Two-level nesting; the predicate carries the full ancestor chain.", when: (g) => solOn(g) && !!g("solace.management.enabled") }),
    F({ section: "Management", key: "solace.management.leader-election", ck: "select", options: ["STANDALONE", "ACTIVE_PASSIVE"], v: Vv.val("STANDALONE", "draft"), advanced: true, label: "Leader election", when: (g) => solOn(g) && !!g("solace.management.enabled") }),
  ];
  const actions = [{ key: "TEST_BROKER_CONNECTION", kind: "test-connection", section: "Broker", visible: true, enabled: true, inputs: ["solace.host", "solace.vpn", "solace.username", "solace.password"], label: "Test connection" }];
  return { instanceKey: "i1", type: "cache-engine", role: "none", sections, fields, actions };
}

function cacheModel() {
  return {
    solution: "cache", packageVersion: 7, single: true,
    components: [cacheComponent()],
    requiresUniformConfig: false, healthMappingDeclared: true, isFlagship: true,
    status: { version: { state: "OK", declared: "2.3.0", observed: "2.3.0", editingBlocked: false }, healthRollup: "GREEN", healthAvailability: "declared" },
  };
}

// ---- FILE MESH (P5e) — 2 composed components; keys repeat; 27 virtual ----
const CK_CYCLE = ["text", "number", "switch", "select", "duration", "string-list", "key-value", "text", "number", "select"];
function genComponent(ik, type, role, specs) {
  const sections = specs.map(s => ({ title: s.title, allAdvanced: !!s.allAdvanced }));
  const fields = [];
  let gi = 0;
  specs.forEach((s, si) => {
    const named = s.named || [];
    named.forEach(nf => fields.push(F({ ...nf, ik, section: s.title })));
    for (let i = named.length; i < s.count; i++) {
      const ck = CK_CYCLE[(si * 3 + i) % CK_CYCLE.length];
      const advanced = s.allAdvanced || i % 3 === 1;
      const virtual = gi % 4 === 3;
      const req = i % 6 === 0;
      const key = `${s.title.replace(/[^A-Za-z0-9]+/g, "_")}_${i}`.toUpperCase();
      fields.push(F({
        ik, section: s.title, key, ck,
        rp: virtual ? null : `${ik}.${s.title.toLowerCase().replace(/[^a-z0-9]+/g, "_")}.${key.toLowerCase()}`,
        v: ck === "switch" ? Vv.val(i % 2 === 0, "default") : (i % 3 === 0 ? Vv.unset() : Vv.val(sampleVal(ck, i), "default")),
        options: ck === "select" ? ["OPTION_A", "OPTION_B", "OPTION_C"] : undefined,
        advanced, virtual, label: key, val: req ? REQUIRED : [],
        help: virtual ? undefined : "Package-declared field; value written to the MI under its rendered path.",
      }));
      gi++;
    }
  });
  return { instanceKey: ik, type, role, sections, fields };
}
function sampleVal(ck, i) { return ck === "number" ? (i * 100) : ck === "duration" ? "30s" : ck === "select" ? "OPTION_A" : `value_${i}`; }

function fileMeshModel() {
  const src = genComponent("src", "COMP_SOURCE_FTP@FILETOFILE", "source", [
    { title: "FILE MESH MANAGER", count: 6, named: [{ key: "SELECT_BROKER", ck: "select", options: ["broker-east", "broker-west"], v: Vv.unset(), rp: null, virtual: true, advanced: true, label: "SELECT_BROKER", val: REQUIRED }] },
    { title: "SOLACE LVQ", count: 8, named: [
      { key: "AUTO_CREATE_TOPIC", ck: "switch", v: Vv.unset(), rp: null, virtual: true, enabled: false, label: "AUTO_CREATE_TOPIC" },
      { key: "AUTO_CREATE_QUEUE", ck: "switch", v: Vv.unset(), rp: null, virtual: true, enabled: false, label: "AUTO_CREATE_QUEUE" }] },
    { title: "DIRECTORY REPLICATION", count: 9, named: [{ key: "ENABLED", ck: "switch", v: Vv.val(true, "draft"), label: "ENABLED", help: "0 -> disabled, 1 -> enabled — replicate the source directory tree." }] },
    { title: "FTP_SETTINGS", count: 12, named: [{ key: "ENABLED", ck: "switch", v: Vv.val(true, "draft"), label: "ENABLED", help: "Enable the FTP transport for this source." }] },
    { title: "GENERAL", count: 10, named: [
      { key: "file.source.command_center.enabled", ck: "text", v: Vv.val("1", "default"), label: "ENABLED", help: "0 -> disabled, 1 -> enabled, sends data to Command Center." },
      { key: "file.source.command_center.useoutputdestinationsolacecredentials", ck: "text", v: Vv.val("true", "default"), advanced: true, label: "USEOUTPUTDESTINATIONSOLACECREDENTIALS", help: "true -> uses output destination solace credentials, false -> uses its own." },
      { key: "file.source.command_center.solace_base_publish_topic", ck: "text", v: Vv.unset(), label: "SOLACE_BASE_PUBLISH_TOPIC", help: "base publish topic for command center event." }] },
    { title: "INFO", count: 5 },
    { title: "DIRECTORY WILDCARD", count: 7 },
    { title: "SCHEDULER", count: 8, named: [{ key: "ENABLED", ck: "switch", v: Vv.val(false, "default"), label: "ENABLED", help: "Run this source on a schedule." }] },
  ]);
  const snk = genComponent("snk", "COMP_SINK_SOLACE@FILETOFILE", "sink", [
    { title: "BROKER_LIST", count: 10, named: [{ key: "SELECT_BROKER", ck: "select", options: ["broker-east", "broker-west"], v: Vv.unset(), rp: null, virtual: true, advanced: true, label: "SELECT_BROKER", val: REQUIRED }] },
    { title: "DATA_PLANE", count: 16, named: [{ key: "ENABLED", ck: "switch", v: Vv.val(true, "draft"), label: "ENABLED", help: "Enable the data-plane connection to the broker." }] },
    { title: "MANAGEMENT_PLANE", count: 12, named: [{ key: "ENABLED", ck: "switch", v: Vv.val(true, "draft"), label: "ENABLED", help: "Enable the management-plane connection." }] },
  ]);
  return {
    solution: "file-mesh", packageVersion: 3, single: false,
    palette: [
      { type: "COMP_SOURCE_FTP", role: "source", label: "FTP source", icon: "folder", color: "#3A4880" },
      { type: "COMP_SOURCE_SFTP", role: "source", label: "SFTP source", icon: "lock", color: "#3A4880" },
      { type: "COMP_SOURCE_S3", role: "source", label: "S3 source", icon: "database", color: "#3A4880" },
      { type: "COMP_SINK_SOLACE", role: "sink", label: "Solace sink", icon: "mesh", color: "#015B82" },
      { type: "COMP_SINK_FILE", role: "sink", label: "File sink", icon: "files", color: "#015B82" },
    ],
    nodes: [
      { instanceKey: "src", type: "COMP_SOURCE_FTP@FILETOFILE", role: "source", label: "FTP source", icon: "folder", color: "#3A4880", x: 60, y: 120 },
      { instanceKey: "snk", type: "COMP_SINK_SOLACE@FILETOFILE", role: "sink", label: "Solace sink", icon: "mesh", color: "#015B82", x: 420, y: 120 },
    ],
    components: [src, snk],
    requiresUniformConfig: false, healthMappingDeclared: false, isFlagship: false,
    status: { version: { state: "VERSION_UNVERIFIED", declared: "2.4", editingBlocked: false }, healthRollup: "NOT_APPLICABLE", healthAvailability: "not-declared" },
  };
}

// ---- Rollout / replica fixtures (P5d). renderedConfigVersion + restartObserved are INVENTED column names (no row schema in the contract). ----
const CONFIG_STATES = [
  { k: "desired", label: "Desired", tip: "The operator's intended config — the draft." },
  { k: "rendered", label: "Rendered", tip: "SM produced the config file." },
  { k: "persisted", label: "Persisted", tip: "The file landed on the target." },
  { k: "restart-observed", label: "Restart-observed", tip: "The process restarted and came back with the file — NOT proof it is using it." },
  { k: "effective-verified", label: "Effective-verified", tip: "The running process is confirmed using the config. Requires a protected endpoint the flagship MI does not expose — so this stays unreached (APPLY_INDETERMINATE)." },
];
const REPLICA_HEALTH = {
  GREEN: { label: "Healthy", tone: "ok", icon: "checkCircle" },
  RED: { label: "Not responding", tone: "err", icon: "xCircle" },
  RESTART_EXPECTED: { label: "Restart expected", tone: "info", icon: "restart", tip: "Platform-initiated restart — not an outage." },
  INACTIVE_REPLICA: { label: "Inactive replica", tone: "idle", icon: "pauseCircle", tip: "Standby: up and deliberately processing nothing. A health gate would pass it though it takes no traffic — why rolling-health-gated is unavailable for this MI." },
};
const ROLLOUT_REPLICAS = [
  { id: "ledger-0", gen: "gen-41", restartObserved: true, health: "GREEN" },
  { id: "ledger-1", gen: "gen-41", restartObserved: true, health: "GREEN" },
  { id: "ledger-2", gen: "gen-41", restartObserved: false, health: "RESTART_EXPECTED" },
  { id: "ledger-3", gen: "gen-40", restartObserved: false, health: "INACTIVE_REPLICA" },
];
const DIVERGED_REPLICAS = [
  { id: "ledger-0", gen: "gen-41", restartObserved: true, health: "GREEN" },
  { id: "ledger-1", gen: "gen-41", restartObserved: true, health: "GREEN" },
  { id: "ledger-2", gen: "gen-40", restartObserved: true, health: "GREEN" },
  { id: "ledger-3", gen: "gen-40", restartObserved: true, health: "RED" },
];

// diff (draft vs deployed) + a concurrent-save (409) "theirs" set. Secrets always masked.
const SECRET_MASK = "•••••••• (secret)";
const DIFF_DRAFT = [
  { path: "cache-engine.namespace", kind: "change", old: "ledger", new: "ledger-v2" },
  { path: "cache-engine.max-size", kind: "change", old: "100000", new: "5000000" },
  { path: "cache-engine.ttl", kind: "add", old: null, new: "120m" },
  { path: "cache-engine.solace.password", kind: "change", secret: true, old: SECRET_MASK, new: SECRET_MASK },
];
const CONFLICT_THEIRS = [
  { path: "cache-engine.namespace", kind: "change", old: "ledger-v2", new: "ledger-prod", note: "yours → theirs" },
  { path: "cache-engine.max-size", kind: "change", old: "5000000", new: "250000", note: "yours → theirs" },
  { path: "cache-engine.solace.password", kind: "change", secret: true, old: SECRET_MASK, new: SECRET_MASK, note: "yours → theirs" },
];

const RESTART_STRATEGIES = [
  { k: "manual", label: "Manual", def: true, consequence: "recommended", desc: "Stage config to every replica, then wait. Nothing restarts until you say so; the instance keeps serving on the current config." },
  { k: "rolling-health-gated", label: "Rolling · health-gated", consequence: "gated", desc: "Restart one replica at a time, advancing only when the last reports healthy.", needsHealthMapping: true, needsNonUniform: true },
  { k: "rolling-no-validation", label: "Rolling · no validation", consequence: "high", desc: "Restart replicas one at a time without waiting for a health signal. Faster, but a bad config rolls across every replica.", needsNonUniform: true, confirmEvenWhenAuthorized: true },
  { k: "restart-all", label: "Restart all", consequence: "outage", desc: "Restart every replica at once. Full outage for the instance until they return.", confirmEvenWhenAuthorized: true },
  { k: "scheduled", label: "Scheduled", consequence: "unavailable", desc: "Restart within a maintenance window.", outOfRelease: true },
];

Object.assign(window, {
  Vv, cacheModel, fileMeshModel, CONFIG_STATES, REPLICA_HEALTH, ROLLOUT_REPLICAS, DIVERGED_REPLICAS,
  DIFF_DRAFT, CONFLICT_THEIRS, RESTART_STRATEGIES, SECRET_MASK,
});
