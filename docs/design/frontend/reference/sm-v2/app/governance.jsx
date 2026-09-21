// P6 · Governance — deliberately simple health view. The platform reports whether
// the MI's health endpoint says it is up; it does NOT judge whether it is working.
// No synthetic score, no composite gauge. NA never renders red; a standby is
// distinguishable from "healthy and working". Five config states, never collapsed,
// with honest "we cannot see this" for stages the flagship can't confirm.
const { useState: uSg, useEffect: uEg, useRef: uRg } = React;

const GOV_SCENARIOS = [
  { v: "healthy", label: "Healthy", roll: "GREEN", avail: "declared", replicas: [{ id: "ledger-0", h: "GREEN" }, { id: "ledger-1", h: "GREEN" }, { id: "ledger-2", h: "INACTIVE_REPLICA" }] },
  { v: "standby", label: "Two standbys", roll: "INACTIVE_REPLICA", avail: "declared", replicas: [{ id: "ledger-0", h: "INACTIVE_REPLICA" }, { id: "ledger-1", h: "INACTIVE_REPLICA" }] },
  { v: "restarting", label: "Restarting", roll: "RESTART_EXPECTED", avail: "declared", replicas: [{ id: "ledger-0", h: "GREEN" }, { id: "ledger-1", h: "RESTART_EXPECTED" }, { id: "ledger-2", h: "GREEN" }] },
  { v: "down", label: "Not responding", roll: "RED", avail: "declared", replicas: [{ id: "ledger-0", h: "RED" }, { id: "ledger-1", h: "RED" }] },
  { v: "notdeclared", label: "No health declared", roll: "NOT_APPLICABLE", avail: "not-declared", replicas: [] },
];
const LADDER = [
  { k: "desired", label: "Desired", note: "Your intended configuration (the saved draft).", st: "done" },
  { k: "rendered", label: "Rendered", note: "SM produced the config file.", st: "done" },
  { k: "persisted", label: "Persisted", note: "The file landed on the target.", st: "done" },
  { k: "restart-observed", label: "Restart-observed", note: "The process restarted and came back with the file. This is as far as the platform can see for this MI.", st: "cur" },
  { k: "effective-verified", label: "Effective-verified", note: "We cannot see this. The MI exposes no protected config endpoint, so the running process is permanently effective-unverified — an honest gap, not a pass.", st: "unknown" },
];

const LogViewer = ({ instId }) => {
  const [streaming, setStreaming] = uSg(false);
  const [comp, setComp] = uSg("cache-engine");
  const [level, setLevel] = uSg("INFO");
  const [noEndpoint, setNoEndpoint] = uSg(false);
  const [lines, setLines] = uSg([]);
  const [confirm, setConfirm] = uSg(false);
  const ref = uRg(null);
  uEg(() => {
    if (!streaming || noEndpoint) return;
    const mk = () => { const lv = level === "DEBUG" && Math.random() < 0.5 ? "DEBUG" : ["INFO", "INFO", "INFO", "WARN"][Math.floor(Math.random() * 4)]; const msgs = { INFO: ["cache hit ratio 0.82 over 5m window", "eviction sweep complete (142 entries)", "broker heartbeat ok", "management queue drained"], WARN: ["broker reconnect backoff 2s", "eviction pressure rising"], DEBUG: ["get key=acct:8821 -> HIT ttl=118m", "topic map lookup levels=2", "solace ack seq=44192", "caffeine stats snapshot taken"] }; const pool = msgs[lv] || msgs.INFO; return { ts: fmtTime(Date.now(), false), lvl: lv, msg: `[${comp}] ` + pool[Math.floor(Math.random() * pool.length)] }; };
    const iv = setInterval(() => setLines(l => [...l.slice(-80), mk()]), 900);
    return () => clearInterval(iv);
  }, [streaming, level, comp, noEndpoint]);
  uEg(() => { if (ref.current) ref.current.scrollTop = ref.current.scrollHeight; }, [lines]);

  return (
    <Paper className="mt16">
      <div className="row between" style={{ padding: "16px 20px 6px" }}>
        <div><div className="t-h6">Logs</div><div className="t-cap muted" style={{ marginTop: 2 }}>Streamed on request — never stored by the platform.</div></div>
        <label className="row gap8" style={{ fontSize: 12.5 }}><Sw on={noEndpoint} onChange={setNoEndpoint} label="Simulate no log endpoint" /> <span className="muted">no-endpoint (demo)</span></label>
      </div>
      <div style={{ padding: "6px 20px 20px" }}>
        {noEndpoint ? (
          <Alert severity="neutral" icon="wifiOff" title="This component exposes no log endpoint">
            <span className="mono">{comp}</span> does not expose a streamable log endpoint, so logs can’t be tailed here. This is a stated degraded condition — not an error, and not an empty stream implying silence.
          </Alert>
        ) : (
          <>
            <div className="logbar">
              <select className="bare" value={comp} onChange={e => setComp(e.target.value)} aria-label="Component">
                {["cache-engine", "management", "telemetry-http"].map(c => <option key={c} value={c}>{c}</option>)}
              </select>
              <div className="row gap6"><span className="t-cap muted">Level</span>
                <select className="bare" value={level} onChange={e => { if (e.target.value === "DEBUG" && level !== "DEBUG") { setConfirm(true); } else setLevel(e.target.value); }} aria-label="Log level">
                  {["ERROR", "WARN", "INFO", "DEBUG"].map(l => <option key={l} value={l}>{l}</option>)}
                </select>
                {level === "DEBUG" && <Chip tone="warn" sm icon="alert">DEBUG live · audited</Chip>}
              </div>
              <span className="grow" />
              <Btn size="sm" variant={streaming ? "outlined" : "contained"} icon={streaming ? "pause" : "play"} onClick={() => setStreaming(s => !s)}>{streaming ? "Pause" : "Stream logs"}</Btn>
            </div>
            <div className="logview" ref={ref} aria-label="Log output">
              {lines.length === 0 ? <div className="muted" style={{ color: "#6f8ba0" }}>{streaming ? "Waiting for output…" : "Press Stream logs to tail this component."}</div>
                : lines.map((l, i) => <div className="logline" key={i}><span className="logts">{l.ts}</span><span className={`loglvl ${l.lvl}`}>{l.lvl}</span><span>{l.msg}</span></div>)}
            </div>
          </>
        )}
      </div>
      {confirm && (
        <Modal title="Raise log level to DEBUG" onClose={() => setConfirm(false)}
          foot={<><Btn variant="text" onClick={() => setConfirm(false)}>Cancel</Btn><Btn className="btn-danger-c" onClick={() => { setLevel("DEBUG"); setConfirm(false); setStreaming(true); pushOutcome(instId, { sev: "warn", title: "Log level raised to DEBUG", detail: `${comp} is emitting DEBUG on a production process — this action is recorded in the audit log.` }); }}>Raise to DEBUG</Btn></>}>
          <Alert severity="warn" title="Privileged, audited action on a production process">
            DEBUG logging on a live process is verbose and can affect performance. The change and your identity are written to the audit log. Lower it again when you’re done.
          </Alert>
        </Modal>
      )}
    </Paper>
  );
};

const GovernanceScreen = ({ route, nav }) => {
  const { demo } = window.useDemo();
  const solId = route.parts[1], instId = route.parts[3];
  const [scenario, setScenario] = uSg("healthy");
  const sc = GOV_SCENARIOS.find(s => s.v === scenario);
  const h = GOV_HEALTH[sc.roll];
  const toMonitoring = () => nav(`/solutions/${solId}/instances/${instId}/monitoring`);

  const body = () => {
    if (demo === "loading") return <div className="page page-wide"><Sk w={260} h={80} r={12} /><div className="rep-grid mt24">{[0, 1, 2].map(i => <Sk key={i} h={90} r={10} />)}</div></div>;
    if (demo === "error") return <div className="page page-wide"><Alert severity="err" title="Couldn’t load health for this instance" action={<Btn size="sm" variant="outlined" icon="refresh">Retry</Btn>}>The console reached the server but the status query failed — distinct from “not responding”. Retry.</Alert></div>;
    const notDeclared = sc.avail === "not-declared";
    return (
      <div className="page page-wide">
        <div className="row between wrap gap12" style={{ marginBottom: 16 }}>
          <div><h1 className="t-h5">Governance</h1><p className="t-cap muted" style={{ margin: "4px 0 0" }}>Health &amp; configuration state for this instance.</p></div>
          <div className="col" style={{ gap: 6, alignItems: "flex-end" }}><span className="t-cap muted">Scenario (prototype)</span><Tbg value={scenario} onChange={setScenario} options={GOV_SCENARIOS.map(s => ({ v: s.v, label: s.label }))} /></div>
        </div>

        <div className="gv-hero">
          <span className="gv-badge" style={{ background: `var(--${h.tone === "ok" ? "success" : h.tone === "err" ? "error" : h.tone === "info" ? "info" : "secondary"}-w10)`, color: `var(--${h.tone === "ok" ? "success" : h.tone === "err" ? "error" : h.tone === "info" ? "info" : "secondary"}-w100)` }}><Ic name={h.icon} size={30} /></span>
          <div className="grow">
            <div className="row gap10" style={{ alignItems: "center" }}><span className="t-h4">{h.label}</span><Chip tone={h.tone} sm dot>rollup</Chip></div>
            <div className="t-body muted" style={{ marginTop: 4, maxWidth: 620 }}>
              {notDeclared ? "All components of this solution declare no health block, so health is not applicable. This is normal — it is never an outage and never red."
                : sc.roll === "INACTIVE_REPLICA" ? "Every replica is a standby: up, and deliberately processing nothing. The instance is fine — a standby is not a failure and its zero KPIs are expected."
                  : sc.roll === "RED" ? "The health endpoint is not answering. This is the platform reporting the endpoint is down — open Monitoring to confirm with the graphs."
                    : sc.roll === "RESTART_EXPECTED" ? "A platform-initiated restart is in progress. This is expected activity, not an outage."
                      : "The health endpoint reports up. The platform does not judge whether the MI is actually working — if it reports healthy while malfunctioning, that is the MI’s defect."}
            </div>
          </div>
          <Btn variant={sc.roll === "RED" ? "contained" : "outlined"} icon="activity" onClick={toMonitoring}>Open in Monitoring</Btn>
        </div>

        <Alert severity="neutral" icon="info" style={{ marginTop: 14 }}>
          <strong>Why this screen is simple:</strong> it answers one question — is the endpoint up? The workflow is <em>red badge → open the graphs → no data confirms it is genuinely down.</em> The cross-check only works because a gap renders as a gap, so there is deliberately no synthetic health score here.
        </Alert>

        {!notDeclared && (<>
          <div className="sec-head"><span className="t-h6">Replicas</span><span className="sc">{sc.replicas.length} · standby excluded from availability math</span></div>
          <div className="rep-grid">
            {sc.replicas.map(r => { const rh = GOV_HEALTH[r.h]; const isStandby = r.h === "INACTIVE_REPLICA"; return (
              <div className={`rep-card ${isStandby ? "inactive" : ""}`} key={r.id}>
                <div className="row between"><span className="mono strong t-sm">{r.id}</span><Chip tone={rh.tone} sm icon={rh.icon}>{rh.label}</Chip></div>
                <div className="t-cap muted" style={{ marginTop: 8 }}>{isStandby ? "Up · processing nothing. Excluded from averages; not a healthy zero." : r.h === "RED" ? "Endpoint not answering." : r.h === "RESTART_EXPECTED" ? "Restart in progress (expected)." : "Endpoint reports up."}</div>
              </div>
            ); })}
          </div>
        </>)}

        <div className="sec-head"><span className="t-h6">Configuration state</span><span className="sc">five states, never collapsed</span></div>
        <Paper pad>
          <div className="ladder">
            {LADDER.map((r, i) => (
              <div className={`lrung ${r.st}`} key={r.k}>
                <div className="lcol"><span className="ldot">{r.st === "done" ? <Ic name="check" size={15} /> : r.st === "cur" ? <Ic name="dot" size={13} /> : <Ic name="help" size={14} />}</span>{i < LADDER.length - 1 && <span className="lbar" style={r.st === "done" ? { background: "var(--success-wMain,#009a80)" } : null} />}</div>
                <div className="lbody"><div className="row gap8"><span className="strong t-sm">{r.label}</span>{r.st === "cur" && <Chip tone="info" sm>current</Chip>}{r.st === "unknown" && <Chip tone="idle" sm icon="ban">effective-unverified</Chip>}</div><div className="t-cap muted" style={{ marginTop: 3, maxWidth: 640 }}>{r.note}</div></div>
              </div>
            ))}
          </div>
          <Alert severity="warn" icon="info" style={{ marginTop: 8 }}>A completed restart reaches <strong>restart-observed</strong> — never presented as convergence. The final stage stays honestly unknown.</Alert>
        </Paper>

        <div className="sec-head"><span className="t-h6">Logs</span></div>
        <LogViewer instId={instId} />
        <div style={{ height: 20 }} />
      </div>
    );
  };

  return (
    <div className="inst-shell">
      <InstanceHeader solId={solId} instId={instId} active="governance" nav={nav} roll={sc.roll} availability={sc.avail} />
      <div className="inst-scroll">{body()}</div>
    </div>
  );
};
window.GovernanceScreen = GovernanceScreen;
