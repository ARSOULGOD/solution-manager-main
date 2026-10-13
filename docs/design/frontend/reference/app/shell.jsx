// App shell: navy Drawer (Home + Solutions only — Targets/Admin excluded in v1),
// white AppBar with breadcrumbs, tenant display string (NOT a switcher), the
// demo state toggle, help + user menu. No bell / no alerting affordance (rules).
const { useEffect, useRef } = React;

const DEMO_STATES = [
  { v: "populated", label: "Populated" },
  { v: "loading", label: "Loading" },
  { v: "empty", label: "Empty / first-run" },
  { v: "partial", label: "Partial data" },
  { v: "error", label: "Error" },
];

function crumbsFor(screen, route) {
  const solId = route.parts[1];
  const instId = route.parts[3];
  const sol = SOLUTIONS.find(s => s.id === solId);
  const inst = (window.CACHE_INSTANCES || []).find(i => i.id === instId);
  const base = [{ label: "Home", to: "#/home" }];
  if (screen === "home") return [{ label: "Home", to: null }];
  if (screen === "solutions") return [...base, { label: "Solutions", to: null }];
  if (screen === "instances") return [...base, { label: "Solutions", to: "#/solutions" }, { label: (sol && sol.name) || solId, to: null }, { label: "Instances", to: null }];
  const CATS = { designer: "Designer", governance: "Governance", monitoring: "Monitoring", explore: "Explore" };
  if (CATS[screen]) return [...base, { label: "Solutions", to: "#/solutions" }, { label: (sol && sol.name) || solId, to: null }, { label: "Instances", to: `#/solutions/${solId}/instances` }, { label: (inst && inst.name) || instId, to: `#/solutions/${solId}/instances/${instId}/designer` }, { label: CATS[screen], to: null }];
  if (screen === "targets") return [...base, { label: "Targets", to: null }];
  if (screen === "admin") return [...base, { label: "Admin", to: null }];
  return base;
}

const NavItem = ({ icon, label, count, on, onClick }) => (
  <button className={`nav-item ${on ? "on" : ""}`} onClick={onClick} aria-current={on ? "page" : undefined}>
    <span className="nic"><Ic name={icon} size={19} /></span><span>{label}</span>
    {count != null && <span className="ncount">{count}</span>}
  </button>
);

const AppShell = ({ screen, route, nav, onSignOut, children }) => {
  const demoCtx = window.useDemo();
  const mainRef = useRef(null);
  const [theme, setTheme] = React.useState(() => (window.LS ? window.LS.get("smv2_theme", "light") : "light"));
  const corr = React.useMemo(() => "sess-" + Math.random().toString(36).slice(2, 10), []);
  useEffect(() => { document.documentElement.setAttribute("data-theme", theme); if (window.LS) window.LS.set("smv2_theme", theme); }, [theme]);
  useEffect(() => { if (mainRef.current) mainRef.current.focus({ preventScroll: true }); mainRef.current && mainRef.current.scrollTo(0, 0); }, [screen, route.path]);
  const crumbs = crumbsFor(screen, route);
  const solCount = new Set(SOLUTIONS.map(s => s.solution)).size;

  return (
    <div className="shell">
      <aside className="drawer">
        <div className="drawer-brand">
          <img src="../assets/solace-logo-s.svg" alt="" style={{ width: 26, height: 26, flex: "none" }} />
          <div className="col" style={{ gap: 1 }}><span className="bn">Solution Manager</span><span className="bs">Solace Console</span></div>
        </div>
        <nav className="drawer-nav" aria-label="Primary">
          <div className="nav-cap">Platform</div>
          <NavItem icon="home" label="Home" on={screen === "home"} onClick={() => nav("/home")} />
          <NavItem icon="grid" label="Solutions" count={solCount} on={["solutions", "instances", "designer", "governance", "monitoring", "explore"].includes(screen)} onClick={() => nav("/solutions")} />
          <div className="nav-sub">
            {SOLUTIONS.filter(s => !s.devMode).map(s => {
              const active = ["instances", "designer", "governance", "monitoring", "explore"].includes(screen) && route.parts[1] === s.id;
              return (
                <button key={s.id} className={`nav-subitem ${active ? "on" : ""}`} onClick={() => nav(`/solutions/${s.id}/instances`)} aria-current={active ? "page" : undefined}>
                  <span className="dotm" /><span className="slabel">{s.name}</span><span className="ncount">{s.instances}</span>
                </button>
              );
            })}
          </div>
        </nav>
        <div className="drawer-foot">
          <div className="who">
            <span className="avatar" style={{ width: 34, height: 34, borderRadius: "50%", background: "var(--primary)", color: "#fff", display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 600, fontSize: 13, flex: "none" }}>DR</span>
            <div className="col grow" style={{ gap: 1, minWidth: 0 }}><span className="wn">{CURRENT_USER.name}</span><span className="wr">{CURRENT_USER.role}{CURRENT_USER.superAdmin ? " · Solace super-admin" : ""}</span></div>
          </div>
        </div>
      </aside>

      <div className="main">
        <header className="appbar">
          <nav className="crumbs" aria-label="Breadcrumb">
            {crumbs.map((c, i) => (
              <React.Fragment key={i}>
                {i > 0 && <span className="csep"><Ic name="chevRight" size={15} /></span>}
                {c.to ? <span className="cz" onClick={() => (location.hash = c.to.replace("#", ""))}>{c.label}</span> : <span className="cnow">{c.label}</span>}
              </React.Fragment>
            ))}
          </nav>
          <div className="appbar-sp" />

          <div className="row" style={{ gap: 8 }}>
            <span className="t-cap muted nowrap">Demo state</span>
            <select className="bare" value={demoCtx.demo} onChange={e => demoCtx.setDemo(e.target.value)} aria-label="Demo state — preview each screen state">
              {DEMO_STATES.map(s => <option key={s.v} value={s.v}>{s.label}</option>)}
            </select>
            <InfoI tip="Prototype control. Switches the current screen between its loading, empty/first-run, populated, partial-data and error states so every state is reviewable." />
          </div>

          <div className="tenant" title="Tenant is a display string the server returns — it is not switchable here.">
            <span className="tk">Tenant</span><span className="tv">{TENANT}</span>
          </div>

          <div className="theme-toggle" role="group" aria-label="Theme">
            <button className={theme === "light" ? "on" : ""} onClick={() => setTheme("light")} aria-label="Light theme" title="Light"><Ic name="sun" size={16} /></button>
            <button className={theme === "dark" ? "on" : ""} onClick={() => setTheme("dark")} aria-label="Dark theme" title="Dark"><Ic name="moon" size={15} /></button>
          </div>
          <Menu align="right" width={260} trigger={(o, t) => <button className="iconbtn" onClick={t} aria-label="Support and version"><Ic name="help" size={18} /></button>}>
            {() => (<>
              <div className="mi-cap">Support</div>
              <div style={{ padding: "2px 11px 10px" }}>
                <div className="row between t-sm"><span className="muted">UI version</span><span className="mono">v1.6.0</span></div>
                <div className="row between t-sm" style={{ marginTop: 6 }}><span className="muted">Correlation id</span><span className="mono">{corr}</span></div>
                <div className="t-cap muted" style={{ marginTop: 8, lineHeight: 1.5 }}>Quote the correlation id on a support ticket.</div>
                <Btn size="sm" variant="outlined" className="btn-block" style={{ marginTop: 10 }} icon="copy" onClick={() => navigator.clipboard && navigator.clipboard.writeText(corr)}>Copy id</Btn>
              </div>
            </>)}
          </Menu>
          <Menu align="right" width={230} trigger={(o, t) => (
            <button onClick={t} className="iconbtn" style={{ width: 40, height: 40 }} aria-label="Account">
              <span className="avatar" style={{ width: 32, height: 32, borderRadius: "50%", background: "var(--primary)", color: "#fff", display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 600, fontSize: 12 }}>DR</span>
            </button>
          )}>
            {(close) => (<>
              <div style={{ padding: "8px 11px 10px" }}>
                <div className="strong">{CURRENT_USER.name}</div>
                <div className="t-cap muted">{CURRENT_USER.email}</div>
                <div style={{ marginTop: 8 }}><Chip tone="info" sm>{CURRENT_USER.role}</Chip></div>
              </div>
              <hr className="divider" style={{ margin: "4px 0" }} />
              <div className="mi"><Ic name="user" size={16} /> Profile</div>
              <div className="mi-cap">Later increments · not in v1 nav</div>
              <div className="mi" onClick={() => { close(); nav("/targets"); }}><Ic name="mapPin" size={16} /> Targets<span className="ncount" style={{ marginLeft: "auto" }}>preview</span></div>
              <div className="mi" onClick={() => { close(); nav("/admin"); }}><Ic name="shield" size={16} /> Admin<span className="ncount" style={{ marginLeft: "auto" }}>preview</span></div>
              <hr className="divider" style={{ margin: "4px 0" }} />
              <div className="mi danger" onClick={onSignOut}><Ic name="logout" size={16} /> Sign out</div>
            </>)}
          </Menu>
        </header>

        <main className="content" id="main" tabIndex={-1} ref={mainRef} aria-label={screen}>
          {children}
        </main>
      </div>
      <Toaster />
    </div>
  );
};
window.AppShell = AppShell;
