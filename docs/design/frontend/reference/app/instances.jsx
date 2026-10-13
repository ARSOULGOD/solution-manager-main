// P4 · Instance inventory — MUI X Data Grid (Community). driver and control_locus
// are row DATA, never a UI branch. Health is five values; "declared but not found"
// splits into four + a bootstrap case; version state is four (unverified is the
// permanent-normal VM state, editable); config state is five, never collapsed.
// Selection lives in the URL, so a link reproduces it.
const { useState } = React;

function healthCell(r) {
  if (r.roll === "notfound") {
    const s = NOTFOUND[r.notFound];
    return <span className="col" style={{ gap: 3 }}><StateChip s={s} sm /><span className="t-cap muted">{s.scope}</span></span>;
  }
  const s = HEALTH[r.roll];
  const counts = {}; r.replicas.forEach(x => counts[x.h] = (counts[x.h] || 0) + 1);
  const summ = Object.entries(counts).map(([k, v]) => `${v} ${HEALTH[k].label.toLowerCase()}`).join(" · ");
  return <span className="col" style={{ gap: 3 }}><StateChip s={s} sm /><span className="t-cap muted">{summ}</span></span>;
}
function versionCell(r) {
  const v = VERSION[r.version.state];
  const edit = v.blocked ? " Config editing is blocked." : v.edit ? " Config editing is allowed." : " Config is read-only until reconciled.";
  return <span className="col" style={{ gap: 3 }}>
    <span className="row gap6"><Chip tone={v.tone} icon={v.icon} sm>{v.label}</Chip><InfoI tip={v.note + edit} /></span>
    <span className="t-cap muted mono">obs {r.version.observed} · decl {r.version.declared}</span>
  </span>;
}
function configCell(r) {
  const c = CONFIG[r.config];
  if (r.config === "na") return <span className="row gap6"><span className="dash">—</span><InfoI tip={c.tip} /></span>;
  return <span className="row gap6 wrap">
    <Chip tone={c.tone} icon={c.icon} sm>{c.label}</Chip>
    {r.config === "in_sync"
      ? <Tip tip="restart-observed only. Cache exposes no protected config endpoint, so effective values can’t be verified — an honest state, not a silent pass."><Chip tone="idle" sm>effective-unverified</Chip></Tip>
      : <InfoI tip={c.tip} />}
  </span>;
}
function expandRow(r) {
  return (<div>
    <div className="repl">
      {r.replicas.map(rp => {
        const h = HEALTH[rp.h];
        return <div className="repl-card" key={rp.n}>
          <div className="row between"><span className="mono t-sm strong">{rp.n}</span><Chip tone={h.tone} icon={h.icon} sm>{h.label}</Chip></div>
          {h.tip && <div className="t-cap muted" style={{ marginTop: 6 }}>{h.tip}</div>}
        </div>;
      })}
    </div>
    <div className="repl-meta">
      <div><div className="t-over">Version</div><div className="t-sm">{VERSION[r.version.state].note}</div></div>
      <div><div className="t-over">Configuration</div><div className="t-sm">{CONFIG[r.config].tip}</div></div>
    </div>
  </div>);
}

const COLUMNS = [
  { field: "name", header: "Instance", width: 220, sortable: true, sortVal: r => r.name, hideable: false,
    render: r => <div className="col" style={{ gap: 1 }}><a className="cell-name" href={`#/solutions/cache/instances/${r.id}/designer`} style={{ color: "var(--primary)" }}>{r.name}</a><span className="cell-sub">{r.org} / {r.sub}</span></div>, csv: r => r.name },
  { field: "env", header: "Env", width: 82, sortable: true, sortVal: r => r.env, render: r => <EnvChip env={r.env} />, csv: r => r.env },
  { field: "driver", header: "Where it runs", width: 156, sortable: true, sortVal: r => r.driver, info: "driver is data on the row — not a separate view. Same table for Docker, Kubernetes and systemd.",
    render: r => <span className="row gap6"><Ic name={DRIVER[r.driver].icon} size={15} style={{ color: "var(--secondary-text-wMain)" }} />{DRIVER[r.driver].label}</span>, csv: r => DRIVER[r.driver].label },
  { field: "locus", header: "How it’s reached", width: 168, sortable: true, sortVal: r => r.locus, info: "control_locus is data on the row — same table, different value. There is no agent view or Kubernetes view.",
    render: r => <span className="row gap6"><Chip tone="idle" sm icon={LOCUS[r.locus].icon}>{LOCUS[r.locus].label}</Chip><InfoI tip={LOCUS[r.locus].tip} /></span>, csv: r => r.locus },
  { field: "health", header: "Health", width: 236, sortable: true, sortVal: r => r.roll === "notfound" ? r.notFound : r.roll, render: healthCell, csv: r => r.roll === "notfound" ? r.notFound : r.roll },
  { field: "version", header: "Version", width: 186, sortable: true, sortVal: r => r.version.state, render: versionCell, csv: r => r.version.state },
  { field: "config", header: "Config", width: 220, sortable: true, sortVal: r => r.config, render: configCell, csv: r => r.config },
  { field: "replicas", header: "Replicas", width: 94, align: "center", sortable: true, sortVal: r => r.replicas.length, render: r => <span className="mono">{r.replicas.length}</span>, csv: r => r.replicas.length },
  { field: "lastSeen", header: "Last seen", width: 116, sortable: true, sortVal: r => r.lastSeen,
    render: r => r.lastSeen === "never" ? <Tip tip="Never observed — awaiting its first restart."><span className="dash">—</span></Tip> : <span className="t-sm muted nowrap">{r.lastSeen}</span>, csv: r => r.lastSeen },
];

const InstancesScreen = ({ nav, route }) => {
  const { demo } = window.useDemo();
  const solId = route.parts[1];
  const sol = SOLUTIONS.find(s => s.id === solId) || SOLUTIONS[0];
  const allRows = solId === "cache" ? CACHE_INSTANCES : [];
  const focusId = route.params.get("focus") || null;
  const sel = (route.params.get("sel") || "").split(",").filter(Boolean);
  const [envF, setEnvF] = useState("ALL");
  const [confirm, setConfirm] = useState(false);

  const setSel = (arr) => {
    const p = new URLSearchParams();
    if (arr.length) p.set("sel", arr.join(","));
    if (focusId) p.set("focus", focusId);
    const qs = p.toString();
    location.hash = `/solutions/${solId}/instances${qs ? "?" + qs : ""}`;
  };
  const rows = envF === "ALL" ? allRows : allRows.filter(r => r.env === envF);
  const selRows = allRows.filter(r => sel.includes(r.id));

  const right = <Btn variant="outlined" size="sm" icon="plus">Declare instance</Btn>;
  const head = (
    <PageHead
      title={<span className="row gap12">{sol.name} <Chip tone="neutral" sm><span className="mono">pkg v{sol.packageVersion}</span></Chip></span>}
      sub="Instances of this solution. An instance is declared here; the artifact is deployed by your own CI/CD."
      right={right} />
  );

  let body;
  if (demo === "loading") {
    body = <div className="dg"><div className="dg-toolbar"><Sk w={260} h={36} /><div className="grow" /><Sk w={120} h={36} /></div>
      <div style={{ padding: 0 }}>{[0, 1, 2, 3, 4, 5].map(i => <div key={i} className="row" style={{ height: 56, padding: "0 16px", borderBottom: "1px solid var(--line-soft)", gap: 24 }}><Sk w={180} h={14} /><Sk w={70} h={14} /><Sk w={130} h={14} /><Sk w={180} h={14} /><Sk w={140} h={14} /></div>)}</div></div>;
  } else if (demo === "error") {
    body = <Alert severity="err" title="Couldn’t load instances" action={<Btn size="sm" variant="outlined" icon="refresh">Retry</Btn>}>
      The console reached the server but the inventory query failed. This is an error — distinct from “no instances” and from “all healthy”. Retry, or check the data plane.
    </Alert>;
  } else if (demo === "empty" || allRows.length === 0) {
    body = <Paper><EmptyState icon="server" title="No instances declared yet"
      action={<Btn icon="plus">Declare an instance</Btn>}>
      An instance is <strong>declared here</strong> and deployed by your own CI/CD — Solution Manager applies configuration, never artifacts. After you declare one, the sequence is <strong>declare → apply → restart → observable</strong>: it stays unobservable until after that first restart, so an early “missing” is expected, not a fault.
    </EmptyState></Paper>;
  } else {
    body = (<>
      {demo === "partial" && <Alert severity="warn" icon="wifiOff" style={{ marginBottom: 14 }} title="Live state for one target is unavailable">
        cache-edge-01’s host hasn’t reported for 8 minutes; its row may be stale. Unknown values render as “—” or a named not-found state — never a healthy default.
      </Alert>}
      <DataGrid columns={COLUMNS} rows={rows} getId={r => r.id} selection={sel} onSelectionChange={setSel}
        renderExpand={expandRow} focusId={focusId} exportName={`${solId}-instances.csv`}
        search={r => `${r.name} ${r.org} ${r.sub} ${r.env} ${r.driver} ${r.locus} ${r.roll} ${r.version.state} ${r.config}`}
        toolbarExtras={<Tbg value={envF} onChange={setEnvF} options={[{ v: "ALL", label: "All" }, { v: "DEV", label: "DEV" }, { v: "TEST", label: "TEST" }, { v: "PROD", label: "PROD" }]} />}
        rightToolbar={sel.length > 0 ? <Btn size="sm" variant="outlined" icon="restart" onClick={() => setConfirm(true)}>Restart {sel.length}…</Btn> : null}
        empty={<div style={{ padding: 44, textAlign: "center" }} className="muted">No instances match this filter.</div>} />
    </>);
  }

  return (
    <div className="page page-wide">
      {head}
      <div className="mt16">{body}</div>
      {confirm && (
        <Modal title="Restart selected instances" onClose={() => setConfirm(false)}
          foot={<><Btn variant="text" onClick={() => setConfirm(false)}>Cancel</Btn><Btn className="btn-danger-c" onClick={() => setConfirm(false)}>Restart {selRows.length}</Btn></>}>
          <Alert severity="warn" title="Applying config requires a restart">
            Writing configuration is not the same as applying it. These instances will restart to pick up staged config.
          </Alert>
          <ul className="t-sm" style={{ lineHeight: 1.7, margin: "14px 0 0", paddingLeft: 18 }}>
            {selRows.map(r => <li key={r.id}><span className="mono">{r.name}</span> · {r.replicas.length} replica{r.replicas.length === 1 ? "" : "s"} · expect ~10–20s unavailability per replica</li>)}
          </ul>
          <p className="t-cap muted" style={{ marginTop: 14 }}>Restart is operator-initiated. The console confirms only that it wrote config and observed a restart — never that the process is using it.</p>
        </Modal>
      )}
    </div>
  );
};
window.InstancesScreen = InstancesScreen;
