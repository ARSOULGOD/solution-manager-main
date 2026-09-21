// P2 · Home — answers "what needs my attention?" First-run is the important state
// (the Solution Package prerequisite). Populated surfaces the abnormal, not an
// aggregate KPI dashboard. Reuses the sanctioned first-run shape (entity types +
// counts + one primary action); with nothing loaded, counts read "—", never 0.
const { useState } = React;

const Stat = ({ v, l, onClick, tone }) => (
  <div className="stat" onClick={onClick} role={onClick ? "button" : undefined} tabIndex={onClick ? 0 : undefined}
       onKeyDown={onClick ? (e) => e.key === "Enter" && onClick() : undefined}>
    <div className="sv" style={tone ? { color: `var(--${tone === "err" ? "error" : "warning"}-w100)` } : null}>{v}</div>
    <div className="sl">{l}</div>
  </div>
);

const FirstRun = ({ nav }) => (
  <div className="page">
    <PageHead title="Home" sub="Set up your first solution." />
    <Paper pad style={{ maxWidth: 720, margin: "8px auto", textAlign: "center", padding: "44px 40px" }}>
      <div style={{ width: 60, height: 60, margin: "0 auto 18px", borderRadius: 16, background: "var(--primary-100)", color: "var(--primary)", display: "flex", alignItems: "center", justifyContent: "center" }}><Ic name="package" size={28} /></div>
      <h2 className="t-h5">Load a Solution Package to begin</h2>
      <p className="t-body muted" style={{ maxWidth: 520, margin: "10px auto 0" }}>Every screen here renders from a <strong>Solution Package</strong> — published by Solace, loaded by you. Until one is loaded, there’s nothing to configure, govern or monitor.</p>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(3,1fr)", gap: 12, margin: "26px 0" }}>
        {[["grid", "Solutions"], ["server", "Instances"], ["mapPin", "Targets"]].map(([ic, l]) => (
          <div key={l} className="paper-flat" style={{ padding: 16 }}>
            <div className="row between"><Ic name={ic} size={18} style={{ color: "var(--secondary-text-wMain)" }} /><span className="dash" style={{ fontSize: 22 }}>—</span></div>
            <div className="t-sm strong" style={{ marginTop: 8, textAlign: "left" }}>{l}</div>
          </div>
        ))}
      </div>
      {CURRENT_USER.superAdmin
        ? <div className="row gap12" style={{ justifyContent: "center" }}><Btn icon="upload" onClick={() => nav("/solutions")}>Load a package</Btn><Btn variant="text">Learn about packages</Btn></div>
        : <Alert severity="neutral" icon="lock" style={{ textAlign: "left" }}>Loading a package is a Solace super-admin action. Ask your administrator to publish and load one.</Alert>}
      <p className="t-cap muted" style={{ marginTop: 22 }}>Reuses the sanctioned first-run pattern — entity types + instance counts + one primary action. With nothing loaded, counts read “—”, never 0.</p>
    </Paper>
  </div>
);

const HomeScreen = ({ nav }) => {
  const { demo } = window.useDemo();
  const rows = CACHE_INSTANCES;
  const staged = rows.filter(i => i.config === "staged" || i.config === "rendered").length;
  const errs = ATTENTION.filter(a => a.sev === "err").length;

  if (demo === "loading") return (
    <div className="page">
      <Sk w={150} h={30} /><div className="mt8"><Sk w={300} h={15} /></div>
      <div className="stat-strip mt24">{[0, 1, 2, 3].map(i => <Paper key={i} pad><Sk w={54} h={30} /><div className="mt16"><Sk w={120} h={13} /></div></Paper>)}</div>
      <Paper pad className="mt24">{[0, 1, 2, 3].map(i => <div key={i} style={{ padding: "14px 0", borderBottom: "1px solid var(--line-soft)" }}><Sk w="42%" h={15} /><div className="mt8"><Sk w="72%" h={12} /></div></div>)}</Paper>
    </div>
  );
  if (demo === "empty") return <FirstRun nav={nav} />;
  if (demo === "error") return (
    <div className="page"><PageHead title="Home" sub="What needs your attention right now." />
      <Alert severity="err" title="Couldn’t load your home view" action={<Btn size="sm" variant="outlined" icon="refresh">Retry</Btn>}>
        The console reached the server but the state query failed. This is an error — not an empty console and not “all clear”. Retry, or check the data-plane status.
      </Alert>
    </div>
  );

  const partial = demo === "partial";
  const attn = ATTENTION;
  return (
    <div className="page">
      <PageHead title="Home" sub="What needs your attention right now — not an aggregate dashboard. You’re here to find the one broken thing." />
      {partial && <Alert severity="warn" icon="wifiOff" style={{ marginBottom: 18 }} title="Live state for one target is unavailable">
        cache-edge-01’s host hasn’t reported for 8 minutes, so any figure including it may be stale. Missing values read “—”, never 0.
      </Alert>}
      <div className="stat-strip">
        <Stat v="2" l="Solutions managed" onClick={() => nav("/solutions")} />
        <Stat v={partial ? "11 +1?" : "12"} l="Instances declared" onClick={() => nav("/solutions/cache/instances")} />
        <Stat v={errs} l="Need attention now" tone="err" />
        <Stat v={staged} l="Config staged · not applied" tone="warn" onClick={() => nav("/solutions/cache/instances?focus=cache-stage-01")} />
      </div>
      <Paper className="mt24">
        <div className="row between" style={{ padding: "18px 22px 6px" }}>
          <div className="t-h6">Needs attention</div>
          <span className="t-cap muted">{attn.length} items · newest first</span>
        </div>
        <div style={{ padding: "0 22px 10px" }}>
          {attn.length === 0
            ? <div style={{ padding: "30px 0" }}><div className="row gap8" style={{ justifyContent: "center", color: "var(--success-w100)" }}><Ic name="checkCircle" size={20} /><span className="strong">Nothing needs attention.</span></div><p className="t-sm muted" style={{ textAlign: "center", marginTop: 6 }}>All declared instances are healthy, applied and on a known version.</p></div>
            : attn.map((a, i) => (
              <div className="attn-item" key={i} role="button" tabIndex={0}
                   onClick={() => { location.hash = a.to.replace("#", ""); }}
                   onKeyDown={(e) => e.key === "Enter" && (location.hash = a.to.replace("#", ""))}>
                <span className="attn-ic" style={{ background: `var(--${a.sev === "err" ? "error" : a.sev === "warn" ? "warning" : "info"}-w10)`, color: `var(--${a.sev === "err" ? "error" : a.sev === "warn" ? "warning" : "info"}-w100)` }}><Ic name={a.icon} size={19} /></span>
                <div className="grow"><div className="strong t-sm">{a.title}</div><div className="t-cap muted" style={{ marginTop: 2 }}>{a.detail}</div></div>
                <Ic name="chevRight" size={18} style={{ color: "var(--secondary-w40)", flex: "none" }} />
              </div>
            ))}
        </div>
      </Paper>
      <div className="row gap8 mt16" style={{ color: "var(--secondary-text-wMain)" }}>
        <Ic name="info" size={15} style={{ flex: "none" }} /><span className="t-cap">Solution Manager surfaces these here. It does not notify or page anyone — no alerting is configured in this release.</span>
      </div>
    </div>
  );
};
window.HomeScreen = HomeScreen;
