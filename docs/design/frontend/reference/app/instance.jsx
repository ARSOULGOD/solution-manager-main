// Instance context (P12): shared header + Designer/Governance/Monitoring tabs.
// A mutation outcome persists on the header (a toast is acknowledgement, not a
// record) and also fires a transient toast. Focus moves into the panel on tab
// change (the shell refocuses <main> on route change).
const { useState: uSi, useEffect: uEi } = React;

const OUTCOME_KEY = (id) => `smv2_outcome_${id}`;
function getOutcome(id) { return window.LS ? window.LS.get(OUTCOME_KEY(id), null) : null; }
let _toastFns = [];
function pushOutcome(instId, o) {
  const rec = { ...o, ts: Date.now(), corr: "req-" + Math.random().toString(36).slice(2, 10) };
  if (instId && window.LS) window.LS.set(OUTCOME_KEY(instId), rec);
  _toastFns.forEach(fn => fn(rec));
  return rec;
}
function onToast(fn) { _toastFns.push(fn); return () => { _toastFns = _toastFns.filter(f => f !== fn); }; }

const Toaster = () => {
  const [items, setItems] = uSi([]);
  uEi(() => onToast((rec) => {
    const id = Math.random().toString(36).slice(2);
    setItems(x => [...x, { ...rec, id }]);
    setTimeout(() => setItems(x => x.filter(i => i.id !== id)), 6000);
  }), []);
  if (!items.length) return null;
  return (
    <div className="toaster" role="status" aria-live="polite">
      {items.map(t => (
        <div key={t.id} className={`toast ${t.sev || "info"}`}>
          <Ic name={t.sev === "ok" ? "checkCircle" : t.sev === "err" ? "xCircle" : t.sev === "warn" ? "alert" : "info"} size={18} style={{ flex: "none", marginTop: 1, color: `var(--${t.sev === "ok" ? "success" : t.sev === "err" ? "error" : t.sev === "warn" ? "warning" : "info"}-wMain)` }} />
          <div className="grow"><div className="strong t-sm">{t.title}</div>{t.detail && <div className="t-cap muted" style={{ marginTop: 2 }}>{t.detail}</div>}<div className="corr" style={{ marginTop: 5 }}>correlation {t.corr}</div></div>
          <IconBtn icon="x" small onClick={() => setItems(x => x.filter(i => i.id !== t.id))} aria-label="Dismiss" />
        </div>
      ))}
    </div>
  );
};

const INSTANCE_TABS = [
  { k: "designer", label: "Designer", icon: "sliders" },
  { k: "governance", label: "Governance", icon: "shield" },
  { k: "monitoring", label: "Monitoring", icon: "activity" },
];

const GOV_HEALTH = {
  GREEN: { label: "Healthy", tone: "ok", icon: "checkCircle", dot: "ok" },
  RED: { label: "Not responding", tone: "err", icon: "xCircle", dot: "err" },
  RESTART_EXPECTED: { label: "Restart expected", tone: "info", icon: "restart", dot: "info" },
  INACTIVE_REPLICA: { label: "Standby · idle", tone: "idle", icon: "pauseCircle", dot: "idle" },
  NOT_APPLICABLE: { label: "No health declared", tone: "idle", icon: "ban", dot: "idle" },
};

const InstanceHeader = ({ solId, instId, active, nav, roll, availability, versionState }) => {
  const inst = (window.CACHE_INSTANCES || []).find(i => i.id === instId) || { name: instId, env: "PROD", roll: "healthy", version: { state: "ok", declared: "2.3.0", observed: "2.3.0" } };
  const rollup = roll || "GREEN";
  const h = GOV_HEALTH[rollup] || GOV_HEALTH.GREEN;
  const outcome = getOutcome(instId);
  const blocked = versionState === "VERSION_DRIFT";
  const go = (k) => nav(`/solutions/${solId}/instances/${instId}/${k}`);
  return (
    <div>
      <div className="ihead">
        <div className="ihead-row">
          <span className="iname">{inst.name}</span>
          <EnvChip env={inst.env} />
          <span className="row gap6"><Chip tone={h.tone} sm icon={h.icon}>{h.label}</Chip>{availability === "not-declared" && <InfoI tip="This instance declares no health block — a normal condition. It is never treated as an outage and never renders red." />}</span>
          {blocked && <Chip tone="warn" sm icon="alert">Version drift · Designer read-only</Chip>}
          <span className="grow" />
          {outcome && (
            <span className={`outcome outcome-${outcome.sev === "ok" ? "ok" : outcome.sev === "warn" ? "warn" : "info"}`} title={`correlation ${outcome.corr}`}>
              <Ic name={outcome.sev === "ok" ? "checkCircle" : outcome.sev === "warn" ? "alert" : "info"} size={15} />
              {outcome.title}
              <span className="corr" style={{ marginLeft: 4 }}>· {outcome.corr}</span>
            </span>
          )}
        </div>
        {blocked && <div className="t-cap muted" style={{ margin: "8px 0 2px" }}>Two of the three categories still work — Governance and Monitoring are available while the version state blocks configuration edits.</div>}
      </div>
      <div className="itabs" role="tablist" aria-label="Instance sections">
        {INSTANCE_TABS.map(t => (
          <button key={t.k} role="tab" aria-selected={active === t.k} className={`itab ${active === t.k ? "on" : ""}`} onClick={() => go(t.k)}>
            <Ic name={t.icon} size={16} />{t.label}
          </button>
        ))}
      </div>
    </div>
  );
};

Object.assign(window, { InstanceHeader, Toaster, pushOutcome, getOutcome, onToast, GOV_HEALTH });
