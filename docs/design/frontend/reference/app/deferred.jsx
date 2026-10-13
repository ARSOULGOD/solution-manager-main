// P10 · Targets and P11 · Admin — later increments, NOT in v1 nav. Reachable only
// by URL / account-menu preview. Honest "deferred" screens: they surface the model
// and the open blockers rather than inventing UI on top of unresolved specs.

const TARGETS_SAMPLE = [
  { id: "k8s-payments", location: "aks/eu-payments", driver: "k8s", locus: "dp-remote", scope: "Payments / PROD" },
  { id: "vm-quotes", location: "vm/quotes-01", driver: "systemd", locus: "host-local", scope: "Trading / PROD" },
  { id: "edge-eu", location: "edge/eu-west", driver: "docker", locus: "observe-only", scope: "Logistics / PROD" },
];

const TargetsScreen = () => (
  <div className="page">
    <div className="row gap8" style={{ marginBottom: 6 }}><Chip tone="neutral" sm icon="clock">Later increment</Chip><Chip tone="idle" sm>Not in v1 navigation</Chip></div>
    <PageHead title="Targets" sub="The inventory of places micro-integrations run. Previewing the model — reachable by URL only until this increment ships." />
    <Alert severity="info" icon="mapPin" style={{ marginBottom: 18 }}>
      <strong>control_locus is a security boundary worth surfacing.</strong> <span className="mono">dp-remote</span> means the platform stores host credentials centrally; <span className="mono">host-local</span> means it stores none and an outbound agent reports in; <span className="mono">observe-only</span> means SM never writes here.
    </Alert>
    <div className="dg"><div className="dg-scroll"><table className="dgt" style={{ minWidth: 720 }}>
      <thead><tr><th>Target</th><th>Driver</th><th>Control locus</th><th>Ownership scope</th></tr></thead>
      <tbody>
        {TARGETS_SAMPLE.map(t => (
          <tr key={t.id}>
            <td><div className="col" style={{ gap: 1 }}><span className="cell-name">{t.id}</span><span className="cell-sub">{t.location}</span></div></td>
            <td><span className="row gap6"><Ic name={DRIVER[t.driver].icon} size={15} style={{ color: "var(--secondary-text-wMain)" }} />{DRIVER[t.driver].label}</span></td>
            <td><span className="row gap6"><Chip tone={t.locus === "dp-remote" ? "warn" : "idle"} sm icon={t.locus === "dp-remote" ? "key" : t.locus === "observe-only" ? "eye" : "server"}>{t.locus}</Chip><InfoI tip={LOCUS[t.locus].tip} /></span></td>
            <td className="t-sm muted">{t.scope}</td>
          </tr>
        ))}
      </tbody>
    </table></div></div>
    <div className="sec-head"><span className="t-h6">Model</span></div>
    <div className="row gap16 wrap">
      <Paper pad style={{ flex: 1, minWidth: 300 }}><div className="t-over">Target</div><div className="mono t-sm" style={{ marginTop: 8, lineHeight: 1.9 }}>location · driver (docker|k8s|systemd)<br />tenant / trust domain<br />control_locus · ownership_scope</div></Paper>
      <Paper pad style={{ flex: 1, minWidth: 300 }}><div className="t-over">Binding</div><div className="mono t-sm" style={{ marginTop: 8, lineHeight: 1.9 }}>infra_telemetry_source · config_mechanism<br />render_outputs[] · runtime_attachments[]<br />selectors · lifecycle_owner (sm|customer)<br />restart_strategy</div></Paper>
    </div>
    <div className="finding" style={{ marginTop: 18 }}><Ic name="lock" size={17} style={{ flex: "none", color: "var(--warning-w100)" }} /><div><div className="strong t-sm">Not designed here — blocked</div><div className="t-cap">Credential entry is intentionally absent: the apply protocol it depends on is an open blocker. Nothing on this screen writes to a target.</div></div></div>
    <div style={{ height: 24 }} />
  </div>
);

const AdminScreen = () => (
  <div className="page">
    <div className="row gap8" style={{ marginBottom: 6 }}><Chip tone="neutral" sm icon="clock">Later increment</Chip><Chip tone="idle" sm>Not in v1 navigation</Chip><Chip tone="warn" sm icon="alert">Weakest spec</Chip></div>
    <PageHead title="Admin" sub="Local users, roles and scoped assignments. Previewing the model — most of this screen is unspecified and shown as open items rather than invented UI." />
    <Alert severity="warn" icon="alert" style={{ marginBottom: 18 }} title="The permission vocabulary itself is unresolved">
      The locked RBAC decision names <strong>two</strong> verbs. Six more proposed for dashboards and metrics <strong>cannot be granted</strong> until the locked catalog is amended — so this screen can’t list the v1 permission set yet. The permission catalog is policy-as-code; there is deliberately no catalog editor here.
    </Alert>
    <div className="row gap16 wrap">
      <Paper pad style={{ flex: 1, minWidth: 260 }}><div className="t-over">Model</div><div className="t-sm" style={{ marginTop: 8, lineHeight: 1.8 }}>permissions <span className="mono">(resource.action)</span> → role definitions (built-in + custom) → scoped assignments → inheritance</div></Paper>
      <Paper pad style={{ flex: 1, minWidth: 260 }}><div className="t-over">Locked verbs (v1)</div><div className="row gap8 mt8 wrap"><span className="pill mono">solution.read</span><span className="pill mono">solution.configure</span></div><div className="t-cap muted mt8">The only two currently grantable.</div></Paper>
    </div>
    <div className="sec-head"><span className="t-h6">Open items — not designed</span></div>
    {[["Local-user CRUD", "Create / edit / disable users — format and API unspecified."], ["Role builder", "Composing custom roles from the catalog — blocked on the catalog amendment."], ["Assignment scope picker", "Binding a principal to a role at a scope with inheritance — unspecified."], ["Audit log", "Record of privileged actions — unspecified shape."], ["403 page", "Named twice, specified nowhere — including whether it may name the missing permission (an information-leak question)."]].map(([t, d]) => (
      <div className="blocker" key={t}><Ic name="lock" size={17} style={{ flex: "none", color: "var(--secondary-text-wMain)" }} /><div><div className="strong t-sm">{t}</div><div className="t-cap muted">{d}</div></div></div>
    ))}
    <div style={{ height: 24 }} />
  </div>
);

Object.assign(window, { TargetsScreen });
