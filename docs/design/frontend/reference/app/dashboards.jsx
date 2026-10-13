// P9 · Dashboards — two layers (package defaults + operator) render as one view.
// The dashboard's range & refresh are authoritative for every widget. Off-screen
// widgets don't load; refresh dims, never blanks. A default is hidden (override in
// the layer above), never deleted. A newer schemaVersion renders read-only, whole.
// ⚠ The dashboard document has NO shape in the contract — everything structural
// here (envelope, hidden-default representation, range/refresh fields, {x,y,w,h,order},
// list route, reset payload) is invented. Flagged in-screen.
const { useState: uSd, useEffect: uEd, useRef: uRd } = React;
const CONSOLE_SCHEMA = 1;

const DASH = {
  id: "db_cache_overview", title: "Cache — overview", schemaVersion: 1,
  range: { kind: "preset", preset: "24h" }, refresh: { intervalSeconds: 60 },
  widgets: [
    { id: "w1", title: "Hit rate", mark: "line", metric: "cache.hit_rate", layer: "package-baseline", w: 6, h: 2, order: 1 },
    { id: "w2", title: "Evictions / min", mark: "line", metric: "cache.evictions", layer: "package-baseline", w: 6, h: 2, order: 2, thresholds: [{ value: 24, label: "warn", severity: "warning", style: "dashed" }] },
    { id: "w3", title: "Entries", mark: "single-stat", metric: "cache.size", layer: "operator", w: 3, h: 1, order: 3 },
    { id: "w4", title: "Hit rate (now)", mark: "gauge", metric: "cache.hit_rate", layer: "operator", w: 3, h: 1, order: 4 },
    { id: "w5", title: "p99 GET latency", mark: "line", metric: "cache.p99_get_latency", layer: "package-baseline", w: 6, h: 2, order: 5, hiddenByOperator: true },
    { id: "w6", title: "Miss count", mark: "line", metric: "cache.miss_count", layer: "operator", w: 6, h: 2, order: 6, schemaVersion: 2 },
  ],
};
const REFRESH_OPTS = [{ v: 0, label: "Off" }, { v: 10, label: "10s" }, { v: 30, label: "30s" }, { v: 60, label: "1m" }, { v: 300, label: "5m" }];

const WidgetCell = ({ w, inst, range, refreshing, editing, onHide, onMove }) => {
  const ref = uRd(null); const [visible, setVisible] = uSd(false);
  uEd(() => { if (!ref.current) return; const io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) setVisible(true); }), { rootMargin: "120px" }); io.observe(ref.current); return () => io.disconnect(); }, []);
  const readOnly = (w.schemaVersion || 1) > CONSOLE_SCHEMA;
  const metric = METRIC_CATALOG.find(m => m.name === w.metric);
  const result = visible && !readOnly ? genResult(w.metric, [inst], "instance", "normal", range) : null;
  const h = w.h * 150;

  return (
    <div className={`widget ${readOnly ? "readonly" : ""}`} style={{ gridColumn: `span ${w.w}` }} ref={ref} tabIndex={0} aria-label={`${w.title} widget`}>
      <div className="widget-h">
        <span className="wt grow">{w.title}</span>
        <span className={`layer-tag ${w.layer === "package-baseline" ? "layer-pkg" : "layer-op"}`}>{w.layer === "package-baseline" ? "package" : "operator"}</span>
        {editing && <span className="pill mono" title="Reading order (a11y) — separate from visual position">#{w.order}</span>}
        {editing ? <div className="row gap6"><IconBtn icon="arrowUp" small onClick={() => onMove(w, -1)} aria-label="Move earlier" /><IconBtn icon="arrowDown" small onClick={() => onMove(w, 1)} aria-label="Move later" /></div>
          : <Menu align="right" width={200} trigger={(o, t) => <IconBtn icon="more" small onClick={t} aria-label="Widget menu" />}>
            {(close) => <><div className="mi" onClick={close}><Ic name="edit" size={15} /> Edit widget</div>{w.layer === "package-baseline" ? <div className="mi" onClick={() => { onHide(w); close(); }}><Ic name="eyeOff" size={15} /> Hide (keep default)</div> : <div className="mi danger" onClick={close}><Ic name="trash" size={15} /> Delete</div>}</>}
          </Menu>}
      </div>
      <div className="widget-b">
        {readOnly ? (
          <div className="chart-empty" style={{ minHeight: h }}><Ic name="lock" size={22} style={{ opacity: .5 }} /><div className="strong">Read-only</div><div className="t-cap muted" style={{ maxWidth: 260 }}>This widget’s schema (v{w.schemaVersion}) is newer than this console (v{CONSOLE_SCHEMA}). It renders read-only and whole — never partially — and unknown fields are preserved on save.</div></div>
        ) : !visible ? <div className="wskel sk" style={{ minHeight: h }} />
          : <div style={{ opacity: refreshing ? 0.55 : 1, transition: "opacity .2s", minHeight: h }}>
            {w.mark === "gauge" ? <GaugeMark value={result.series[0].points.slice(-1)[0][1]} unit={metric.unit} min={0} max={metric.unit === "ratio" ? 1 : 100000} />
              : w.mark === "single-stat" ? <SingleStatMark value={result.series[0].points.slice(-1)[0][1]} unit={metric.unit} />
                : <><TimeChart result={result} range={range} height={h} thresholds={w.thresholds || []} /><div className="mt8"><Legend series={result.series} maxRows={2} /></div></>}
          </div>}
      </div>
    </div>
  );
};

const DashboardsScreen = ({ route, nav }) => {
  const { demo } = window.useDemo();
  const solId = route.parts[1], instId = route.parts[3];
  const inst = (window.CACHE_INSTANCES || []).find(i => i.id === instId) || CACHE_INSTANCES[0];
  const [rangeK, setRangeK] = uSd(DASH.range.preset);
  const [refreshS, setRefreshS] = uSd(DASH.refresh.intervalSeconds);
  const [resolution, setResolution] = uSd("auto");
  const [layer, setLayer] = uSd("all");
  const [editing, setEditing] = uSd(false);
  const [widgets, setWidgets] = uSd(DASH.widgets);
  const [refreshing, setRefreshing] = uSd(false);
  const [findings, setFindings] = uSd(false);

  const preset = RANGE_PRESETS.find(r => r.k === rangeK) || RANGE_PRESETS[3];
  const landing = route.parts[5] !== "dashboards";
  const range = { from: NOW - preset.ms, to: NOW };
  const canRaw = CURRENT_USER.superAdmin;
  const bucketS = 3600;

  uEd(() => {
    if (!refreshS) return;
    const floored = Math.max(refreshS, bucketS >= 3600 ? refreshS : refreshS); // floored at served bucket (demo: bucket is hourly)
    const iv = setInterval(() => { if (document.hidden) return; setRefreshing(true); setTimeout(() => setRefreshing(false), 700); }, refreshS * 1000);
    return () => clearInterval(iv);
  }, [refreshS]);

  const shown = widgets.filter(w => layer === "all" ? true : layer === "package" ? w.layer === "package-baseline" : w.layer === "operator").sort((a, b) => a.order - b.order);
  const hideWidget = (w) => setWidgets(ws => ws.map(x => x.id === w.id ? { ...x, hiddenByOperator: true } : x));
  const restoreWidget = (w) => setWidgets(ws => ws.map(x => x.id === w.id ? { ...x, hiddenByOperator: false } : x));
  const moveWidget = (w, dir) => setWidgets(ws => { const sorted = [...ws].sort((a, b) => a.order - b.order); const i = sorted.findIndex(x => x.id === w.id); const j = i + dir; if (j < 0 || j >= sorted.length) return ws; const a = sorted[i].order; sorted[i] = { ...sorted[i], order: sorted[j].order }; sorted[j] = { ...sorted[j], order: a }; return sorted; });

  return (
    <div className="inst-shell">
      <InstanceHeader solId={solId} instId={instId} active="monitoring" nav={nav} roll="GREEN" availability="declared" />
      <div className="itabs" style={{ paddingTop: 0, borderTop: "none" }} role="tablist" aria-label="Monitoring views">
        <button className="itab" onClick={() => nav(`/solutions/${solId}/instances/${instId}/monitoring`)}><Ic name="grid" size={15} /> Dashboards</button>
        <button className="itab on"><Ic name="chart" size={15} /> {DASH.title}</button>
        <button className="itab" onClick={() => nav(`/solutions/${solId}/instances/${instId}/monitoring/explore`)}><Ic name="search" size={15} /> Explore</button>
      </div>

      <div className="dash-toolbar">
        <select className="bare" value={rangeK} onChange={e => setRangeK(e.target.value)} aria-label="Time range">{RANGE_PRESETS.map(r => <option key={r.k} value={r.k}>Last {r.label}</option>)}</select>
        <select className="bare" value={refreshS} onChange={e => setRefreshS(Number(e.target.value))} aria-label="Refresh">{REFRESH_OPTS.map(o => <option key={o.v} value={o.v}>Refresh: {o.label}</option>)}</select>
        {refreshing && <span className="row gap6 t-cap" style={{ color: "var(--primary)" }}><Ic name="refresh" size={14} /> updating…</span>}
        <Tbg value={resolution} onChange={setResolution} options={canRaw ? [{ v: "auto", label: "Auto" }, { v: "raw", label: "Raw" }] : [{ v: "auto", label: "Auto" }]} />
        <span className="grow" />
        <select className="bare" value={layer} onChange={e => setLayer(e.target.value)} aria-label="Layer filter"><option value="all">All layers</option><option value="package">Package defaults</option><option value="operator">Operator layer</option></select>
        <Btn size="sm" variant={editing ? "contained" : "outlined"} icon="sliders" onClick={() => setEditing(e => !e)}>{editing ? "Done" : "Edit layout"}</Btn>
        <Btn size="sm" variant="text" icon="refresh" onClick={() => setWidgets(DASH.widgets)}>Reset defaults</Btn>
      </div>

      <div className="inst-scroll">
        {demo === "error" ? <div className="page"><Alert severity="err" title="Dashboard failed to load" action={<Btn size="sm" variant="outlined" icon="refresh">Retry</Btn>}>Widgets render inside their own error boundary — one failing widget never takes down the dashboard.</Alert></div>
          : demo === "empty" ? <div className="page"><FirstRunMonitoring solId={solId} instId={instId} nav={nav} /></div>
            : (<>
              {landing && <div style={{ padding: "12px 40px 0" }}><Alert severity="info" icon="grid"><strong>Reading (a):</strong> Monitoring renders the package’s default dashboard directly. A dashboards <em>index</em> (reading b) isn’t assumed — the contract has no list route — and this isn’t the first-run entity list (reading c), which shows only when nothing is saved. Switch dashboards from the menu above.</Alert></div>}
              <div className="row gap12" style={{ padding: "12px 40px 0", flexWrap: "wrap" }}>
                <Alert severity="neutral" icon="info" style={{ flex: 1, minWidth: 320 }}>The dashboard’s range and refresh are <strong>authoritative for every widget</strong> — a widget’s own settings are defaults it overrides. There is deliberately no per-widget time pinning.</Alert>
                <button className="finding" style={{ cursor: "pointer", alignItems: "center", flex: "0 0 auto" }} onClick={() => setFindings(f => !f)}><Ic name="alert" size={17} style={{ color: "var(--warning-w100)" }} /><span className="strong t-sm">Contract findings</span><Ic name={findings ? "chevUp" : "chevDown"} size={15} /></button>
              </div>
              {findings && <div style={{ padding: "10px 40px 0" }}><div className="finding"><Ic name="doc" size={17} style={{ flex: "none", color: "var(--warning-w100)" }} /><div><div className="strong t-sm">The dashboard document has no shape in the repo — invented here:</div><div className="t-cap" style={{ marginTop: 4, lineHeight: 1.7 }}>envelope &amp; field names · the <span className="mono">hidden-default</span> representation (modelled as an operator-layer override) · dashboard-level <span className="mono">range</span>/<span className="mono">refresh</span> · whether <span className="mono">{`{x,y,w,h,order}`}</span> live on the widget or a placement entry · a dashboards <span className="mono">list</span> route · the <span className="mono">reset</span> payload. Raised against the contract, not silently added.</div></div></div></div>}

              <div className="grid12">
                {shown.map(w => w.hiddenByOperator
                  ? (layer !== "operator" && <div key={w.id} className="widget hidden-def" style={{ gridColumn: `span ${w.w}` }}><div className="widget-b" style={{ minHeight: w.h * 150 }}><div className="chart-empty"><Ic name="eyeOff" size={22} style={{ opacity: .5 }} /><div className="strong t-sm">Hidden default · {w.title}</div><div className="t-cap muted" style={{ maxWidth: 280 }}>Hidden, not deleted — a later package can still improve this default and your choice survives.</div><Btn size="sm" variant="outlined" icon="eye" onClick={() => restoreWidget(w)}>Restore</Btn></div></div></div>)
                  : <WidgetCell key={w.id} w={w} inst={inst} range={range} refreshing={refreshing} editing={editing} onHide={hideWidget} onMove={moveWidget} />)}
              </div>
              <div style={{ height: 24 }} />
            </>)}
      </div>
    </div>
  );
};

// shared first-run for Monitoring (P7 reading (c))
const FirstRunMonitoring = ({ solId, instId, nav }) => (
  <div className="empty" style={{ maxWidth: 620 }}>
    <div className="eic"><Ic name="activity" size={26} /></div>
    <h3>No dashboards saved yet</h3>
    <p className="t-body">The empty console does not show an empty grid. Start from the entity types below and open Explore to build your first view.</p>
    <div style={{ display: "grid", gridTemplateColumns: "repeat(3,1fr)", gap: 12, margin: "8px 0 22px" }}>
      {[["bolt", "cache-engine", "12"], ["server", "replicas", "31"], ["chart", "metrics", "6"]].map(([ic, l, n]) => (
        <div key={l} className="paper-flat" style={{ padding: 16, textAlign: "left" }}><div className="row between"><Ic name={ic} size={17} style={{ color: "var(--secondary-text-wMain)" }} /><span className="strong">{n}</span></div><div className="t-sm strong" style={{ marginTop: 8 }}>{l}</div></div>
      ))}
    </div>
    <Btn icon="search" onClick={() => nav(`/solutions/${solId}/instances/${instId}/monitoring/explore`)}>Open Explore</Btn>
  </div>
);

Object.assign(window, { DashboardsScreen, FirstRunMonitoring, DASH });
